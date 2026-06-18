package com.lab.reservation.service.lock

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.domain.Reservation
import com.lab.reservation.exception.CapacityExceededException
import com.lab.reservation.exception.EventNotFoundException
import com.lab.reservation.exception.OptimisticLockConflictException
import com.lab.reservation.repository.EventRepository
import com.lab.reservation.repository.ReservationRepository
import jakarta.persistence.OptimisticLockException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * OPTIMISTIC — @Version 낙관적 락.
 * [체크포인트 4] Phase B에서도 Handler 내부 DB 로직은 동일, 앞단 Redis 선차감은 ReservationService가 담당.
 */
@Component
class OptimisticLockHandler(
    private val eventRepository: EventRepository,
    private val reservationRepository: ReservationRepository,
) : ReservationLockHandler {
    override val strategy: LockStrategy = LockStrategy.OPTIMISTIC

    @Transactional
    override fun reserve(eventId: Long, userId: String): Reservation {
        val event = eventRepository.findById(eventId).orElseThrow { EventNotFoundException(eventId) }
        if (event.reservedCount >= event.capacity) {
            throw CapacityExceededException()
        }
        event.reservedCount++
        try {
            eventRepository.saveAndFlush(event)
        } catch (_: OptimisticLockException) {
            throw OptimisticLockConflictException()
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw OptimisticLockConflictException()
        }
        return reservationRepository.save(Reservation(eventId = eventId, userId = userId))
    }
}
