package com.booking.reservation.service.basic

import com.booking.reservation.domain.LockStrategy
import com.booking.contracts.ReservationConfirmedEvent
import com.booking.reservation.kafka.ReservationEventPublisher
import com.booking.reservation.repository.EventRepository
import com.booking.reservation.service.ReservationCreationFlow
import com.booking.reservation.service.ReservationLockExecutor
import com.booking.reservation.service.ReservationResult
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
