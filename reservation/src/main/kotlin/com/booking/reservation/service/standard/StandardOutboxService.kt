package com.booking.reservation.service.standard

import com.booking.reservation.domain.LockStrategy
import com.booking.reservation.domain.OutboxEventType
import com.booking.reservation.domain.Reservation
import com.booking.reservation.domain.ReservationOutbox
import com.booking.reservation.repository.ReservationOutboxRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
}
