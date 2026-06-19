package com.lab.reservation.service.lock

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.domain.Reservation
import com.lab.reservation.exception.CapacityExceededException
import com.lab.reservation.exception.DistributedLockFailedException
import com.lab.reservation.exception.EventNotFoundException
import com.lab.reservation.repository.EventRepository
import com.lab.reservation.repository.ReservationRepository
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
    override val strategy: LockStrategy = LockStrategy.REDIS

    override fun reserve(eventId: Long, userId: String): Reservation {
        val lock = redisLockRegistry.obtain("event-lock:$eventId")
        val acquired = lock.tryLock(5, TimeUnit.SECONDS)
        if (!acquired) {
            throw DistributedLockFailedException()
        }
        try {
            return transactionTemplate.execute {
                val event = eventRepository.findById(eventId).orElseThrow { EventNotFoundException(eventId) }
                if (event.reservedCount >= event.capacity) {
                    throw CapacityExceededException()
                }
                event.reservedCount++
                eventRepository.save(event)
                reservationRepository.save(Reservation(eventId = eventId, userId = userId))
            }!!
        } finally {
            lock.unlock()
        }
    }
}
