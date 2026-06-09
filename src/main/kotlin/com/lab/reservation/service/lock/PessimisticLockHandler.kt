package com.lab.reservation.service.lock

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.domain.Reservation
import com.lab.reservation.exception.CapacityExceededException
import com.lab.reservation.exception.EventNotFoundException
import com.lab.reservation.repository.EventRepository
import com.lab.reservation.repository.ReservationRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PessimisticLockHandler(
    private val eventRepository: EventRepository,
    private val reservationRepository: ReservationRepository,
) : ReservationLockHandler {
    override val strategy: LockStrategy = LockStrategy.PESSIMISTIC

    @Transactional
    override fun reserve(eventId: Long, userId: String): Reservation {
        val event = eventRepository.findByIdForUpdate(eventId).orElseThrow { EventNotFoundException(eventId) }
        if (event.reservedCount >= event.capacity) {
            throw CapacityExceededException()
        }
        event.reservedCount++
        eventRepository.save(event)
        return reservationRepository.save(Reservation(eventId = eventId, userId = userId))
    }
}
