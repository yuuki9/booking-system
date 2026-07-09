package com.booking.reservation.service.standard

import com.booking.reservation.config.AppModeProperties
import com.booking.contracts.ReservationConfirmedEvent
import com.booking.contracts.ReservationPendingEvent
import com.booking.reservation.domain.OutboxEventType
import com.booking.reservation.kafka.ReservationEventPublisher
import com.booking.reservation.repository.ReservationOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Outbox → Kafka 발행 폴러 (Transactional Outbox의 “읽기/발행” 절반).
 *
 * ## 선제 개념
 * - **At-least-once**: publish 성공 후 `publishedAt` 마킹 전에 크래시하면 재발행될 수 있다.
 *   다운스트림(payment UNIQUE, reservation 가드 UPDATE)이 중복을 흡수한다.
 * - **event_type 라우팅**: 한 테이블에 PENDING/CONFIRMED를 넣고 토픽만 갈라 쓴다
 *   (테이블 분리 대비 스키마 단순, 폴러 분기 비용은 작음).
 *
 * ## 작업 흐름
 * ```
 * 매 poll-interval-ms
 *   → findUnpublished()
 *   → PENDING  → ReservationPendingEvent  → reservation.pending
 *   → CONFIRMED → ReservationConfirmedEvent → reservation.confirmed
 *   → publishedAt = now
 *   → 실패 행은 publishedAt 비움 → 다음 틱 재시도
 * ```
 *
 * ## 트레이드오프
 * - **같은 @Transactional에서 produce + 마킹**: produce 성공·DB 마킹 실패 시 중복 produce 가능
 *   (의도된 at-least-once). produce 실패 시 마킹 안 함 → 재시도.
 * - **consumer 프로파일 제외**: API 인스턴스만 발행. 로그 consumer 프로세스와 역할 분리.
 */
@Component
@Profile("!consumer")
@ConditionalOnProperty(name = ["app.mode"], havingValue = "standard", matchIfMissing = true)
class StandardOutboxPublisher(
    private val appModeProperties: AppModeProperties,
    private val outboxRepository: ReservationOutboxRepository,
    private val reservationEventPublisher: ReservationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.standard.outbox.poll-interval-ms:2000}")
    @Transactional
    fun publishPending() {
        if (!appModeProperties.standard.outbox.enabled) {
            return
        }

        val pending = outboxRepository.findUnpublished()
        if (pending.isEmpty()) {
            return
        }

        pending.forEach { row ->
            try {
                when (row.eventType) {
                    OutboxEventType.PENDING -> {
                        val amount = row.amount
                            ?: error("Outbox PENDING row missing amount reservationId=${row.reservationId}")
                        reservationEventPublisher.publishPending(
                            ReservationPendingEvent(
                                reservationId = row.reservationId,
                                eventId = row.eventId,
                                userId = row.userId,
                                amount = amount,
                                lockStrategy = row.lockStrategy,
                                occurredAt = row.confirmedAt,
                            ),
                        )
                    }
                    OutboxEventType.CONFIRMED -> {
                        reservationEventPublisher.publish(
                            ReservationConfirmedEvent(
                                reservationId = row.reservationId,
                                eventId = row.eventId,
                                userId = row.userId,
                                lockStrategy = row.lockStrategy,
                                confirmedAt = row.confirmedAt,
                            ),
                        )
                    }
                    else -> error("Unknown outbox event_type=${row.eventType} reservationId=${row.reservationId}")
                }
                row.publishedAt = Instant.now()
                log.debug("Outbox published reservationId={} eventType={}", row.reservationId, row.eventType)
            } catch (ex: Exception) {
                log.error("Outbox publish failed reservationId={} eventType={}", row.reservationId, row.eventType, ex)
            }
        }
    }
}
