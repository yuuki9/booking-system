package com.booking.reservation.service.standard

import com.booking.contracts.PaymentResultEvent
import com.booking.contracts.PaymentResultStatus
import com.booking.reservation.config.AppModeProperties
import com.booking.reservation.domain.LockStrategy
import com.booking.reservation.domain.Reservation
import com.booking.reservation.domain.ReservationStatus
import com.booking.reservation.repository.EventRepository
import com.booking.reservation.repository.ReservationOutboxRepository
import com.booking.reservation.repository.ReservationRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 결제 Saga의 reservation 측 오케스트레이션(실제로는 choreography 참여자).
 *
 * ## 선제 개념
 * - **Choreography Saga**: 중앙 오케스트레이터 없이 Kafka 이벤트로 상태를 맞춘다.
 *   reservation ↔ payment는 동기 HTTP를 쓰지 않는다 (실패 모델·타임아웃이 달라 로컬 TX로 묶을 수 없음).
 * - **보상 트랜잭션(Compensation)**: 결제 실패 시 이미 선점한 좌석(DB `reserved_count` + Redis remaining)을 되돌린다.
 * - **Idempotent consumer**: Kafka at-least-once이므로 동일 `payment.result`가 여러 번 올 수 있다.
 *   `WHERE status = PENDING_PAYMENT` 가드 UPDATE로 한 번만 전이한다.
 *
 * ## 작업 흐름
 * ```
 * payment.result(APPROVED)
 *   → PENDING_PAYMENT → CONFIRMED (가드 UPDATE)
 *   → reservation.confirmed Outbox 적재
 *
 * payment.result(FAILED)
 *   → PENDING_PAYMENT → CANCELLED
 *   → events.reserved_count - 1
 *   → (해당 락 전략이면) Redis INCR
 *
 * reaper / compensateTimeout
 *   → payment.result 유실 시 동일 보상 (lockStrategy 미저장 → Redis는 enabled면 무조건 rollback)
 * ```
 *
 * ## 트레이드오프
 * - **가드 UPDATE vs 엔티티 더티체킹**: 영향 행 수로 “누가 이겼는지”를 원자적으로 알 수 있다.
 *   `@Modifying` 후 영속성 컨텍스트는 stale일 수 있어, 전이 성공 시에만 재조회한다.
 * - **늦은 APPROVED**: reaper가 먼저 CANCELLED로 만든 뒤 APPROVED가 오면 0행 → WARN만.
 *   “결제는 됐는데 좌석은 없음” 불일치는 의도적으로 문서화만 하고, 환불 보상 이벤트는 범위 밖.
 * - **Redis rollback 판정**: `onFailed`는 이벤트의 `lockStrategy`로 `shouldApply` 판정.
 *   reaper는 예약에 lockStrategy가 없어 `redis-inventory.enabled`면 무조건 rollback (데모는 REDIS/OPTIMISTIC만 사용).
 */
@Service
@ConditionalOnProperty(name = ["app.mode"], havingValue = "standard", matchIfMissing = true)
class StandardPaymentSagaService(
    private val appModeProperties: AppModeProperties,
    private val reservationRepository: ReservationRepository,
    private val eventRepository: EventRepository,
    private val outboxService: StandardOutboxService,
    private val redisInventoryService: StandardRedisInventoryService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 결제 승인 → 예약 확정.
     *
     * 가드 UPDATE가 0행이면 이미 CONFIRMED/CANCELLED이거나 중복 소비 → no-op.
     * 성공 시에만 CONFIRMED Outbox를 쌓아 downstream(`reservation.confirmed`)을 이어간다.
     */
    @Transactional
    fun onApproved(event: PaymentResultEvent) {
        val updated = reservationRepository.transitionStatus(
            event.reservationId,
            ReservationStatus.PENDING_PAYMENT,
            ReservationStatus.CONFIRMED,
        )
        if (updated == 0) {
            log.warn("Late or duplicate approval ignored reservationId={}", event.reservationId)
            return
        }
        val reservation = reservationRepository.findById(event.reservationId).orElseThrow()
        val lockStrategy = LockStrategy.valueOf(event.lockStrategy)
        outboxService.enqueue(reservation, lockStrategy)
    }

    /**
     * 결제 실패 → 보상 (좌석 반환).
     * 이벤트에 실린 lockStrategy로 Redis 선차감 여부를 정확히 판정한다.
     */
    @Transactional
    fun onFailed(event: PaymentResultEvent) {
        compensate(event.reservationId, event.eventId, LockStrategy.valueOf(event.lockStrategy))
    }

    /**
     * [StandardPendingReservationReaper] 전용: `payment.result` 유실 시 안전망.
     *
     * ## 트레이드오프
     * 예약 행에 lockStrategy가 없어 Redis rollback을 “해당 요청이 선차감했는지”로 판별할 수 없다.
     * → `redis-inventory.enabled`면 무조건 INCR. NONE 전략 실험과 섞이면 over-increment 가능하나,
     *   Compose/k6 데모는 REDIS·OPTIMISTIC만 쓰므로 실용상 문제없다.
     */
    @Transactional
    fun compensateTimeout(reservationId: UUID, eventId: Long) {
        if (!cancelPendingReservation(reservationId, eventId)) {
            return
        }
        if (appModeProperties.standard.redisInventory.enabled) {
            redisInventoryService.rollback(eventId)
        }
    }

    private fun compensate(reservationId: UUID, eventId: Long, lockStrategy: LockStrategy) {
        if (!cancelPendingReservation(reservationId, eventId)) {
            return
        }
        if (appModeProperties.standard.redisInventory.shouldApply(lockStrategy)) {
            redisInventoryService.rollback(eventId)
        }
    }

    /**
     * PENDING_PAYMENT → CANCELLED + DB 좌석 1 감소.
     * @return true면 이 호출이 전이를 이긴 경우 (보상 계속), false면 이미 처리됨.
     */
    private fun cancelPendingReservation(reservationId: UUID, eventId: Long): Boolean {
        val updated = reservationRepository.transitionStatus(
            reservationId,
            ReservationStatus.PENDING_PAYMENT,
            ReservationStatus.CANCELLED,
        )
        if (updated == 0) {
            log.warn("Compensation skipped reservationId={} (already processed)", reservationId)
            return false
        }
        eventRepository.releaseSeat(eventId)
        return true
    }
}
