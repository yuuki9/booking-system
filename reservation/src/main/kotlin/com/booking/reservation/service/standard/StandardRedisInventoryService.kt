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
 * Redis 기반 재고 선차감 (hot-path 필터).
 *
 * ## 선제 개념
 * - **선차감(pre-decrement)**: DB 락/트랜잭션 전에 원자적 DECR로 “이미 매진”을 빠르게 거절한다.
 * - **최종 정합은 DB**: Redis는 캐시·힌트. LockHandler가 `events.reserved_count`를 올린다.
 *   Saga 보상·로컬 실패 시 [rollback](INCR)으로 Redis를 DB에 다시 맞춘다.
 * - **Lua 스크립트**: GET+분기+DECR을 한 라운드트립·원자 연산으로 (레이스 없는 sold-out 판정).
 *
 * ## 작업 흐름
 * ```
 * syncFromDatabase: remaining = capacity - reserved_count → SET
 * tryDecrement:     Lua DECR if remaining > 0  else sold-out / not-initialized
 * rollback:         INCR (예약 실패·결제 실패·reaper 보상)
 * ```
 *
 * ## 트레이드오프
 * - **성능 vs 일관성**: Redis 장애·키 누락 시 요청 실패(미초기화). DB만 쓰면 느리지만 단순.
 * - **NONE 전략 skip**: 초과예약 실험(`shouldApply` false)에서는 선차감 안 함 — 의도적 오버부킹 재현.
 * - **드리프트**: 보상 누락 시 Redis remaining과 DB가 어긋날 수 있음 → reset/sync 스크립트로 복구.
 *
 * Redis 키: `event:{eventId}:remaining`
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
