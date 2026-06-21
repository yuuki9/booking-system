package com.lab.reservation.service.lock

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.domain.Reservation
import com.lab.reservation.exception.CapacityExceededException
import com.lab.reservation.exception.EventNotFoundException
import com.lab.reservation.repository.EventRepository
import com.lab.reservation.repository.ReservationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * PESSIMISTIC — SELECT FOR UPDATE 행 잠금.
 * App scale-out 시에도 DB 한 행 직렬화 → 처리량 병목 관찰용.
 */
@Component
class PessimisticLockHandler(
    private val eventRepository: EventRepository,
    private val reservationRepository: ReservationRepository,
) : ReservationLockHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val strategy: LockStrategy = LockStrategy.PESSIMISTIC

    @Transactional
    override fun reserve(eventId: Long, userId: String): Reservation {
        val lockWaitStart = System.nanoTime()
        val event = eventRepository.findByIdForUpdate(eventId).orElseThrow { EventNotFoundException(eventId) }
        val lockWaitMs = (System.nanoTime() - lockWaitStart) / 1_000_000
        if (lockWaitMs > 0) {
            log.warn("PESSIMISTIC row lock waited {}ms eventId={} userId={}", lockWaitMs, eventId, userId)
        }

        if (event.reservedCount >= event.capacity) {
            log.warn("PESSIMISTIC capacity exceeded eventId={} reserved={}/{}", eventId, event.reservedCount, event.capacity)
            throw CapacityExceededException()
        }
        event.reservedCount++
        eventRepository.save(event)
        return reservationRepository.save(Reservation(eventId = eventId, userId = userId))
    }
}
