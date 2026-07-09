package com.booking.reservation.service.lock

import com.booking.reservation.domain.LockStrategy
import com.booking.reservation.domain.Reservation
import com.booking.reservation.domain.ReservationStatus
import com.booking.reservation.exception.CapacityExceededException
import com.booking.reservation.exception.DistributedLockFailedException
import com.booking.reservation.exception.EventNotFoundException
import com.booking.reservation.repository.EventRepository
import com.booking.reservation.repository.ReservationRepository
import org.slf4j.LoggerFactory
import org.springframework.integration.redis.util.RedisLockRegistry
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.TimeUnit

/**
 * REDIS — RedisLockRegistry 분산 락 + DB 트랜잭션.
 * standard 모드에서는 ReservationService의 Redis 재고 선차감 + 이 Handler의 event-lock 이중 방어.
 */
@Component
class RedisLockHandler(
    private val eventRepository: EventRepository,
    private val reservationRepository: ReservationRepository,
    private val redisLockRegistry: RedisLockRegistry,
    private val transactionTemplate: TransactionTemplate,
) : ReservationLockHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val strategy: LockStrategy = LockStrategy.REDIS

    override fun reserve(eventId: Long, userId: String, initialStatus: ReservationStatus): Reservation {
        val lock = redisLockRegistry.obtain("event-lock:$eventId")
        val lockWaitStart = System.nanoTime()
        val acquired = lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val lockWaitMs = (System.nanoTime() - lockWaitStart) / 1_000_000

        if (!acquired) {
            log.warn(
                "REDIS distributed lock timeout after {}ms eventId={} userId={} timeout={}s",
                lockWaitMs,
                eventId,
                userId,
                LOCK_TIMEOUT_SECONDS,
            )
            throw DistributedLockFailedException()
        }
        if (lockWaitMs > 0) {
            log.warn("REDIS distributed lock waited {}ms eventId={} userId={}", lockWaitMs, eventId, userId)
        }

        try {
            return transactionTemplate.execute {
                val event = eventRepository.findById(eventId).orElseThrow { EventNotFoundException(eventId) }
                if (event.reservedCount >= event.capacity) {
                    log.warn("REDIS capacity exceeded eventId={} reserved={}/{}", eventId, event.reservedCount, event.capacity)
                    throw CapacityExceededException()
                }
                event.reservedCount++
                eventRepository.save(event)
                reservationRepository.save(Reservation(eventId = eventId, userId = userId, status = initialStatus))
            }!!
        } finally {
            lock.unlock()
        }
    }

    companion object {
        private const val LOCK_TIMEOUT_SECONDS = 5L
    }
}
