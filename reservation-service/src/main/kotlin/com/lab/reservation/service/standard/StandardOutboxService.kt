package com.lab.reservation.service.standard

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.domain.Reservation
import com.lab.reservation.domain.ReservationOutbox
import com.lab.reservation.repository.ReservationOutboxRepository
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
                confirmedAt = reservation.createdAt,
            ),
        )
    }
}
