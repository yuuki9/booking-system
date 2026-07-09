package com.booking.reservation.config

import com.booking.reservation.domain.LockStrategy
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 실행 모드 및 standard 하드닝 플래그.
 *
 * ## 선제 개념
 * - **Feature flag로 하위 호환**: `payment.enabled=false`(기본)면 Phase 이전과 동일한
 *   즉시 CONFIRMED 경로. 기존 k6·통합 테스트가 flag off로 통과해야 한다.
 * - **basic vs standard**: 락 실험실과 운영 경로를 코드로 분리 (`@ConditionalOnProperty`).
 *
 * ## 트레이드오프 (payment)
 * - [Payment.timeoutSeconds] (기본 60): Mock PG 최대 지연보다 커야 reaper가 정상 결제를 삼키지 않음.
 * - [Payment.reaperIntervalMs] (기본 10s): 짧을수록 고아 회수 빠름, DB 폴링 부하↑.
 */
@ConfigurationProperties(prefix = "app")
data class AppModeProperties(
    val mode: AppMode = AppMode.STANDARD,
    val standard: StandardFeatures = StandardFeatures(),
) {
    fun isBasicMode(): Boolean = mode == AppMode.BASIC

    fun isStandardMode(): Boolean = mode == AppMode.STANDARD

    data class StandardFeatures(
        val duplicateCheck: DuplicateCheck = DuplicateCheck(),
        val redisInventory: RedisInventory = RedisInventory(),
        val outbox: Outbox = Outbox(),
        val payment: Payment = Payment(),
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

        /**
         * @property enabled env `PAYMENT_ENABLED`. Compose에서는 true.
         * @property timeoutSeconds PENDING_PAYMENT 최대 대기 (reaper cutoff).
         * @property reaperIntervalMs 고아 스캔 주기.
         */
        data class Payment(
            val enabled: Boolean = false,
            val timeoutSeconds: Long = 60,
            val reaperIntervalMs: Long = 10_000,
        )
    }
}
