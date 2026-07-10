package com.booking.reservation.service.standard

import com.booking.reservation.domain.LockStrategy
import com.booking.reservation.domain.OutboxEventType
import com.booking.reservation.domain.Reservation
import com.booking.reservation.domain.ReservationOutbox
import com.booking.reservation.repository.ReservationOutboxRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** reaper/보상 후 늦은 APPROVED에 대한 환불 요청 reason. */
const val REFUND_REASON_LATE_APPROVAL_AFTER_CANCEL = "LATE_APPROVAL_AFTER_CANCEL"

/**
 * 예약 도메인 Outbox 적재 (Transactional Outbox 패턴의 “쓰기” 절반).
 *
 * ## 선제 개념
 * - **Dual-write 문제**: 같은 요청에서 DB 커밋과 Kafka produce를 각각 하면 한쪽만 성공할 수 있다.
 *   Outbox는 **비즈니스 행과 이벤트 행을 한 TX**에 넣어 원자성을 DB에 위임한다.
 * - **발행은 비동기**: [StandardOutboxPublisher]가 unpublished 행을 폴링해 Kafka로 보낸다.
 *
 * ## 작업 흐름
 * - [enqueue]: payment off 또는 Saga 승인 후 → `CONFIRMED` → 토픽 `reservation.confirmed`
 * - [enqueuePending]: payment on 생성 시 → `PENDING` + amount → 토픽 `reservation.pending`
 * - [enqueueRefundRequested]: 늦은 APPROVED after CANCELLED → `payment.refund`
 *
 * ## 트레이드오프
 * - **폴링 지연(기본 2s)**: E2E에서 PENDING→CONFIRMED까지 수 초 걸린다. 즉시성↓, 구현·운영 단순↑.
 *   CDC(Debezium)는 지연↓·인프라 복잡도↑ — 이 랩에서는 폴링 선택.
 */
@Service
@ConditionalOnProperty(name = ["app.mode"], havingValue = "standard", matchIfMissing = true)
class StandardOutboxService(
    private val outboxRepository: ReservationOutboxRepository,
) {
    @Transactional
    fun enqueue(reservation: Reservation, lockStrategy: LockStrategy) {
        outboxRepository.save(
            ReservationOutbox(
                reservationId = reservation.id,
                eventId = reservation.eventId,
                userId = reservation.userId,
                lockStrategy = lockStrategy.name,
                eventType = OutboxEventType.CONFIRMED,
                confirmedAt = reservation.createdAt,
            ),
        )
    }

    @Transactional
    fun enqueuePending(reservation: Reservation, lockStrategy: LockStrategy, amount: Long) {
        outboxRepository.save(
            ReservationOutbox(
                reservationId = reservation.id,
                eventId = reservation.eventId,
                userId = reservation.userId,
                lockStrategy = lockStrategy.name,
                eventType = OutboxEventType.PENDING,
                amount = amount,
                confirmedAt = reservation.createdAt,
            ),
        )
    }

    @Transactional
    fun enqueueRefundRequested(
        paymentId: UUID,
        reservation: Reservation,
        amount: Long,
    ) {
        if (outboxRepository.existsByReservationIdAndEventType(reservation.id, OutboxEventType.REFUND_REQUESTED)) {
            return
        }
        outboxRepository.save(
            ReservationOutbox(
                reservationId = reservation.id,
                eventId = reservation.eventId,
                userId = reservation.userId,
                lockStrategy = "",
                eventType = OutboxEventType.REFUND_REQUESTED,
                amount = amount,
                paymentId = paymentId,
                confirmedAt = reservation.createdAt,
            ),
        )
    }
}
