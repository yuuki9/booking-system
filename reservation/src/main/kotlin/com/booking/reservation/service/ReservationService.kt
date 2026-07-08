package com.booking.reservation.service

import com.booking.reservation.domain.LockStrategy
import com.booking.reservation.domain.Reservation
import com.booking.reservation.exception.ReservationNotFoundException
import com.booking.reservation.repository.ReservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ReservationService(
    private val creationFlow: ReservationCreationFlow,
    private val reservationRepository: ReservationRepository,
) {
    @Transactional(readOnly = true)
    fun findById(id: UUID): Reservation =
        reservationRepository.findById(id).orElseThrow { ReservationNotFoundException(id) }

    fun createReservation(
        eventId: Long,
        userId: String,
        lockStrategy: LockStrategy,
        idempotencyKey: String?,
    ): ReservationResult =
        creationFlow.create(eventId, userId, lockStrategy, idempotencyKey)
}
