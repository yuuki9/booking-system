package com.lab.reservation.service.lock

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.domain.Reservation
import com.lab.reservation.exception.CapacityExceededException
import com.lab.reservation.exception.EventNotFoundException
import com.lab.reservation.exception.OptimisticLockConflictException
import com.lab.reservation.repository.EventRepository
import com.lab.reservation.repository.ReservationRepository
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

    override fun reserve(eventId: Long, userId: String): Reservation {
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return transactionTemplate.execute { tryReserveOnce(eventId, userId) }!!
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

    private fun tryReserveOnce(eventId: Long, userId: String): Reservation {
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
        return reservationRepository.save(Reservation(eventId = eventId, userId = userId))
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
    }
}
