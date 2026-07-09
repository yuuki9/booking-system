package com.booking.reservation.service.lock

import com.booking.reservation.domain.LockStrategy
import com.booking.reservation.domain.Reservation
import com.booking.reservation.domain.ReservationStatus
import com.booking.reservation.exception.CapacityExceededException
import com.booking.reservation.exception.EventNotFoundException
import com.booking.reservation.exception.OptimisticLockConflictException
import com.booking.reservation.repository.EventRepository
import com.booking.reservation.repository.ReservationRepository
import jakarta.persistence.OptimisticLockException
import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * OPTIMISTIC — @Version 낙관적 락.
 * standard 모드에서도 Handler 내부 DB 로직은 동일, 앞단 Redis 선차감은 ReservationService가 담당.
 */
@Component
class OptimisticLockHandler(
    private val eventRepository: EventRepository,
    private val reservationRepository: ReservationRepository,
    private val transactionTemplate: TransactionTemplate,
) : ReservationLockHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val strategy: LockStrategy = LockStrategy.OPTIMISTIC

    override fun reserve(eventId: Long, userId: String, initialStatus: ReservationStatus): Reservation {
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return transactionTemplate.execute { tryReserveOnce(eventId, userId, initialStatus) }!!
            } catch (ex: CapacityExceededException) {
                throw ex
            } catch (ex: OptimisticLockConflictException) {
                if (attempt == MAX_ATTEMPTS - 1) {
                    log.warn(
                        "OPTIMISTIC version conflict, retries exhausted eventId={} userId={} attempts={}",
                        eventId,
                        userId,
                        MAX_ATTEMPTS,
                    )
                    throw ex
                }
                log.warn(
                    "OPTIMISTIC version conflict, retry {}/{} eventId={} userId={}",
                    attempt + 2,
                    MAX_ATTEMPTS,
                    eventId,
                    userId,
                )
            }
        }
        throw OptimisticLockConflictException()
    }

    private fun tryReserveOnce(eventId: Long, userId: String, initialStatus: ReservationStatus): Reservation {
        val event = eventRepository.findById(eventId).orElseThrow { EventNotFoundException(eventId) }
        if (event.reservedCount >= event.capacity) {
            log.warn("OPTIMISTIC capacity exceeded eventId={} reserved={}/{}", eventId, event.reservedCount, event.capacity)
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
        return reservationRepository.save(Reservation(eventId = eventId, userId = userId, status = initialStatus))
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
    }
}
