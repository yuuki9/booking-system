package com.booking.reservation.service.standard

import com.booking.reservation.config.AppModeProperties
import com.booking.reservation.exception.CapacityExceededException
import com.booking.reservation.exception.RedisInventoryNotInitializedException
import com.booking.reservation.repository.EventRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

/**
 * standard 모드: Redis 재고 선차감.
 *
 * Redis 키: event:{eventId}:remaining
 */
@Service
@ConditionalOnProperty(name = ["app.mode"], havingValue = "standard", matchIfMissing = true)
class StandardRedisInventoryService(
    private val redisTemplate: StringRedisTemplate,
    private val eventRepository: EventRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun key(eventId: Long): String = "event:$eventId:remaining"

    fun syncFromDatabase(eventId: Long) {
        val event = eventRepository.findById(eventId).orElse(null) ?: return
        val remaining = (event.capacity - event.reservedCount).coerceAtLeast(0)
        redisTemplate.opsForValue().set(key(eventId), remaining.toString())
        log.info("Redis inventory synced eventId={} remaining={}", eventId, remaining)
    }

    fun tryDecrement(eventId: Long) {
        val redisKey = key(eventId)
        val result = redisTemplate.execute(DECREMENT_SCRIPT, listOf(redisKey))
        when (result) {
            DECREMENT_OK -> return
            DECREMENT_SOLD_OUT -> throw CapacityExceededException()
            DECREMENT_NOT_INITIALIZED -> throw RedisInventoryNotInitializedException(eventId)
            else -> error("Unexpected Redis inventory result: $result")
        }
    }

    fun rollback(eventId: Long) {
        redisTemplate.opsForValue().increment(key(eventId), 1)
        log.debug("Redis inventory rollback eventId={}", eventId)
    }

    companion object {
        private const val DECREMENT_OK = 1L
        private const val DECREMENT_SOLD_OUT = -1L
        private const val DECREMENT_NOT_INITIALIZED = -2L

        private val DECREMENT_SCRIPT = org.springframework.data.redis.core.script.DefaultRedisScript<Long>().apply {
            resultType = Long::class.java
            setScriptText(
                """
                local current = redis.call('GET', KEYS[1])
                if current == false then
                  return -2
                end
                local remaining = tonumber(current)
                if remaining <= 0 then
                  return -1
                end
                redis.call('DECR', KEYS[1])
                return 1
                """.trimIndent(),
            )
        }
    }
}
