package com.lab.reservation.config

import com.lab.reservation.domain.LockStrategy
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Phase B 현업 1스텝 기능 토글.
 *
 * application.yml 의 lab.phase-b.* 와 매핑됩니다.
 */
@ConfigurationProperties(prefix = "lab.phase-b")
data class PhaseBProperties(
    /** Phase B 전체 활성화 여부 */
    val enabled: Boolean = true,
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
        /** 미발행 outbox 폴링 주기(ms) */
        val pollIntervalMs: Long = 2_000,
    )
}
