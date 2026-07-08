package com.lab.reservation.service

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.exception.InvalidLockStrategyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LockStrategyResolverTest {
    private val resolver = LockStrategyResolver("OPTIMISTIC")

    @Test
    fun `query parameter overrides default`() {
        assertEquals(LockStrategy.REDIS, resolver.resolve("REDIS", null))
    }

    @Test
    fun `header is used when query is absent`() {
        assertEquals(LockStrategy.PESSIMISTIC, resolver.resolve(null, "PESSIMISTIC"))
    }

    @Test
    fun `query takes precedence over header`() {
        assertEquals(LockStrategy.NONE, resolver.resolve("NONE", "REDIS"))
    }

    @Test
    fun `invalid query throws`() {
        assertThrows(InvalidLockStrategyException::class.java) {
            resolver.resolve("INVALID", null)
        }
    }
}
