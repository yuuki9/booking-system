package com.lab.reservation.service.inventory

import com.lab.reservation.exception.CapacityExceededException
import com.lab.reservation.exception.RedisInventoryNotInitializedException
import com.lab.reservation.repository.EventRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

/**
 * Phase B: Redis 재고 선차감 서비스.
 *
 * DB 트랜잭션에 들어가기 **전에** Redis에서 남은 좌석을 DECR하여
 * 마감된 이벤트 요청을 빠르게 거절합니다.
 *
 * Redis 키: event:{eventId}:remaining
 */
@Service
class RedisInventoryService(
    private val redisTemplate: StringRedisTemplate,
    private val eventRepository: EventRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 이벤트별 Redis 재고 키 */
    private fun key(eventId: Long): String = "event:$eventId:remaining"

    /**
     * [체크포인트] DB events.reserved_count / capacity 기준으로 Redis 재고를 동기화합니다.
     * 앱 기동·reset-data 후 호출합니다.
     */
    fun syncFromDatabase(eventId: Long) {
        val event = eventRepository.findById(eventId).orElse(null) ?: return
        val remaining = (event.capacity - event.reservedCount).coerceAtLeast(0)
        redisTemplate.opsForValue().set(key(eventId), remaining.toString())
        log.info("Redis inventory synced eventId={} remaining={}", eventId, remaining)
    }

    /**
     * [체크포인트] Lua 스크립트로 원자적 DECR.
     * - 0 이하이면 INCR 롤백 후 [CapacityExceededException]
     * - 키 없으면 [RedisInventoryNotInitializedException]
     */
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

    /**
     * [체크포인트] DB 저장 실패 등으로 선차감을 되돌릴 때 INCR (보상 트랜잭션).
     */
    fun rollback(eventId: Long) {
        redisTemplate.opsForValue().increment(key(eventId), 1)
        log.debug("Redis inventory rollback eventId={}", eventId)
    }

    companion object {
        private const val DECREMENT_OK = 1L
        private const val DECREMENT_SOLD_OUT = -1L
        private const val DECREMENT_NOT_INITIALIZED = -2L

        /**
         * KEYS[1] = 재고 키
         * 반환: 1=성공, -1=매진, -2=키 없음
         */
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
