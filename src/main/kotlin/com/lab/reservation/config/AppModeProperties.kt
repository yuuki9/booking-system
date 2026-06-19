package com.lab.reservation.config

import com.lab.reservation.domain.LockStrategy
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 애플리케이션 실행 모드 및 standard 모드 전용 기능 설정.
 *
 * - benchmark: 4가지 락 전략 비교 실험 (Handler → DB → Kafka)
 * - standard: 멱등·중복검사·Redis 선차감·Outbox (기본값)
 */
@ConfigurationProperties(prefix = "app")
data class AppModeProperties(
    val mode: AppMode = AppMode.STANDARD,
    val standard: StandardFeatures = StandardFeatures(),
) {
    fun isBenchmarkMode(): Boolean = mode == AppMode.BENCHMARK

    fun isStandardMode(): Boolean = mode == AppMode.STANDARD

    data class StandardFeatures(
        val duplicateCheck: DuplicateCheck = DuplicateCheck(),
        val redisInventory: RedisInventory = RedisInventory(),
        val outbox: Outbox = Outbox(),
    ) {
        data class DuplicateCheck(
            val enabled: Boolean = true,
        )

        data class RedisInventory(
            val enabled: Boolean = true,
            /** NONE 전략 실험(초과 예약 재현) 시 Redis 선차감을 건너뜁니다 */
            val skipForStrategies: List<String> = listOf("NONE"),
        ) {
            fun shouldApply(strategy: LockStrategy): Boolean =
                enabled && strategy.name !in skipForStrategies.map { it.uppercase() }
        }

        data class Outbox(
            val enabled: Boolean = true,
            val pollIntervalMs: Long = 2_000,
        )
    }
}
