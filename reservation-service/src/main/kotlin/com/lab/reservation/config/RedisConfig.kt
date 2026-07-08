package com.lab.reservation.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.integration.redis.util.RedisLockRegistry

@Configuration
class RedisConfig {
    @Bean
    fun redisLockRegistry(connectionFactory: RedisConnectionFactory): RedisLockRegistry =
        RedisLockRegistry(connectionFactory, "booking-system-lock", 10_000)
}
