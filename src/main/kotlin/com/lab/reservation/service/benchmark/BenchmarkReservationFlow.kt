package com.lab.reservation.service.benchmark

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.kafka.ReservationConfirmedEvent
import com.lab.reservation.kafka.ReservationEventPublisher
import com.lab.reservation.repository.EventRepository
import com.lab.reservation.service.ReservationCreationFlow
import com.lab.reservation.service.ReservationLockExecutor
import com.lab.reservation.service.ReservationResult
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * benchmark 모드: Lock Handler → DB → Kafka 직접 publish.
 *
 * 멱등·중복검사·Redis 선차감·Outbox 없이 4가지 락 전략 비교에 집중합니다.
 */
@Component
@ConditionalOnProperty(name = ["app.mode"], havingValue = "benchmark")
class BenchmarkReservationFlow(
    private val lockExecutor: ReservationLockExecutor,
    private val eventRepository: EventRepository,
    private val reservationEventPublisher: ReservationEventPublisher,
) : ReservationCreationFlow {

    @Transactional
    override fun create(
        eventId: Long,
        userId: String,
        lockStrategy: LockStrategy,
        idempotencyKey: String?,
    ): ReservationResult {
        val reservation = lockExecutor.reserve(eventId, userId, lockStrategy)
        reservationEventPublisher.publish(
            ReservationConfirmedEvent(
                reservationId = reservation.id,
                eventId = reservation.eventId,
                userId = reservation.userId,
                lockStrategy = lockStrategy.name,
                confirmedAt = reservation.createdAt,
            ),
        )
        val event = eventRepository.findById(eventId).orElseThrow()
        return ReservationResult(
            reservation = reservation,
            lockStrategy = lockStrategy,
            remainingCapacity = event.remainingCapacity,
        )
    }
}
