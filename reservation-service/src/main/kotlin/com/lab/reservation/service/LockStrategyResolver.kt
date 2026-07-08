package com.lab.reservation.service

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.exception.InvalidLockStrategyException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class LockStrategyResolver(
    @Value("\${app.lock-strategy}") private val defaultStrategy: String,
) {
    fun resolve(queryStrategy: String?, headerStrategy: String?): LockStrategy {
        val query = LockStrategy.from(queryStrategy)
        if (queryStrategy != null && query == null) {
            throw InvalidLockStrategyException(queryStrategy)
        }
        if (query != null) {
            return query
        }

        val header = LockStrategy.from(headerStrategy)
        if (headerStrategy != null && header == null) {
            throw InvalidLockStrategyException(headerStrategy)
        }
        if (header != null) {
            return header
        }

        return LockStrategy.from(defaultStrategy)
            ?: throw InvalidLockStrategyException(defaultStrategy)
    }
}
