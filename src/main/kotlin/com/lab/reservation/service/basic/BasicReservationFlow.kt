package com.lab.reservation.service.basic

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
 * basic 모드: Lock Handler → DB → Kafka 직접 publish.
 *
 */
@Component
@ConditionalOnProperty(name = ["app.mode"], havingValue = "basic")
class BasicReservationFlow(
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
