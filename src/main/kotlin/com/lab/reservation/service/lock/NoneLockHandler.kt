package com.lab.reservation.service.lock

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.domain.Reservation
import com.lab.reservation.exception.CapacityExceededException
import com.lab.reservation.exception.EventNotFoundException
import com.lab.reservation.repository.EventRepository
import com.lab.reservation.repository.ReservationRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * NONE — 락 없음 (lab 시나리오 1: 초과 예약 재현용).
 * Phase B Redis 선차감은 NONE 전략에서 skip 되므로 DB 경합 그대로 관찰 가능.
 */
@Component
class NoneLockHandler(
    private val eventRepository: EventRepository,
    private val reservationRepository: ReservationRepository,
) : ReservationLockHandler {
    override val strategy: LockStrategy = LockStrategy.NONE

    @Transactional
    override fun reserve(eventId: Long, userId: String): Reservation {
        val event = eventRepository.findById(eventId).orElseThrow { EventNotFoundException(eventId) }
        if (event.reservedCount >= event.capacity) {
            throw CapacityExceededException()
        }
        event.reservedCount++
        eventRepository.save(event)
        return reservationRepository.save(Reservation(eventId = eventId, userId = userId))
    }
}
