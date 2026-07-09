package com.booking.reservation.service.standard

import com.booking.reservation.config.AppModeProperties
import com.booking.reservation.domain.ReservationStatus
import com.booking.reservation.repository.ReservationRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * `PENDING_PAYMENT` 고아 예약 회수기 (Saga 타임아웃 안전망).
 *
 * ## 선제 개념
 * - **이벤트 유실 / 소비자 다운**: payment가 `payment.result`를 못 보내거나 reservation이 못 받으면
 *   좌석이 영구히 PENDING에 묶인다. Outbox·Kafka만으로는 “영원히 안 오는 메시지”를 감지할 수 없다.
 * - **Timeout-based compensation**: 시간 기반 휴리스틱으로 보상을 트리거한다 (정확 합의가 아님).
 *
 * ## 작업 흐름
 * ```
 * 매 reaper-interval-ms (기본 10s)
 *   → payment.enabled 아니면 return
 *   → PENDING_PAYMENT && created_at < now - timeout-seconds (기본 60s) 조회
 *   → 건별 sagaService.compensateTimeout (CANCELLED + 좌석 반환)
 * ```
 *
 * ## 트레이드오프
 * - **timeout(60s) ≫ Mock PG 최대 지연**: 정상 결제가 reaper에 먹히지 않게 여유를 둔다.
 *   너무 짧으면 정상 APPROVED와 경합이 늘고, 너무 길면 좌석 점유 시간이 길어진다.
 * - **reaper vs 늦은 payment.result**: 둘 다 가드 UPDATE를 쓰므로 한쪽만 이긴다 (이중 보상 없음).
 * - **알려진 한계**: reaper 취소 후 늦은 APPROVED → 결제 성공·좌석 없음. 환불 보상은 미구현 (README 명시).
 * - **consumer 프로파일 제외**: 로그 전용 consumer 프로세스에서 스케줄이 돌지 않게 `@Profile("!consumer")`.
 */
@Component
@Profile("!consumer")
@ConditionalOnProperty(name = ["app.mode"], havingValue = "standard", matchIfMissing = true)
class StandardPendingReservationReaper(
    private val appModeProperties: AppModeProperties,
    private val reservationRepository: ReservationRepository,
    private val sagaService: StandardPaymentSagaService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.standard.payment.reaper-interval-ms:10000}")
    fun reapStalePending() {
        if (!appModeProperties.standard.payment.enabled) {
            return
        }

        val cutoff = Instant.now().minusSeconds(appModeProperties.standard.payment.timeoutSeconds)
        val stale = reservationRepository.findByStatusAndCreatedAtBefore(
            ReservationStatus.PENDING_PAYMENT,
            cutoff,
        )
        stale.forEach { reservation ->
            log.info(
                "Reaping stale PENDING_PAYMENT reservationId={} eventId={} reason=SAGA_TIMEOUT",
                reservation.id,
                reservation.eventId,
            )
            sagaService.compensateTimeout(reservation.id, reservation.eventId)
        }
    }
}
