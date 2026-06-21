package com.lab.reservation.benchmark

import com.lab.reservation.benchmark.support.BenchmarkIntegrationTestSupport
import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.exception.CapacityExceededException
import com.lab.reservation.exception.DistributedLockFailedException
import com.lab.reservation.exception.OptimisticLockConflictException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException

@DisplayName("benchmark 모드 락 전략 비교")
class BenchmarkLockStrategyTest : BenchmarkIntegrationTestSupport() {

    @Nested
    @DisplayName("NONE — 락 없음")
    inner class None {
        @Test
        fun `단일 예약은 정상 생성된다`() {
            val result = createOne(LockStrategy.NONE)

            assertEquals(LockStrategy.NONE, result.lockStrategy)
            assertEquals(99, result.remainingCapacity)
            assertEquals(1, reservationRepository.count())
        }

        @Test
        fun `동시 요청 시 동시성 파괴 재현`() {
            val outcomes = runConcurrentReservations(requestCount = 150, lockStrategy = LockStrategy.NONE)
            val successCount = outcomes.count { it.succeeded }
            val reservationCount = reservationRepository.count()
            val reservedCount = eventRepository.findAll().first().reservedCount

            assertTrue(
                reservationCount > 100 || reservedCount > 100,
                "NONE(락 없음)은 초과 예약이 발생해야 합니다. " +
                    "success=$successCount reservations=$reservationCount reservedCount=$reservedCount",
            )
            assertTrue(
                successCount > 100,
                "NONE은 150건 중 100건을 초과해 성공해야 합니다. success=$successCount",
            )
        }
    }

    @Nested
    @DisplayName("OPTIMISTIC — @Version")
    inner class Optimistic {
        @Test
        fun `단일 예약은 정상 생성된다`() {
            val result = createOne(LockStrategy.OPTIMISTIC)
            assertEquals(LockStrategy.OPTIMISTIC, result.lockStrategy)
            assertEquals(1, reservationRepository.count())
        }

        @Test
        fun `동시 200건 중 정확히 100건만 성공한다`() {
            val outcomes = runConcurrentReservations(requestCount = 200, lockStrategy = LockStrategy.OPTIMISTIC)
            val event = eventRepository.findAll().first()
            val reservationCount = reservationRepository.count()

            assertEquals(200, outcomes.size)
            assertTrue(reservationCount <= 100, "초과 예약이 발생하면 안 됩니다. count=$reservationCount")
            assertTrue(event.reservedCount <= 100, "reservedCount가 capacity를 넘으면 안 됩니다")
            assertEquals(reservationCount, event.reservedCount.toLong())
            assertTrue(outcomes.count { !it.succeeded } > 0, "일부 요청은 충돌/마감으로 실패해야 합니다")

            val failureTypes = outcomes.filter { !it.succeeded }.mapNotNull { it.error }.map { it.javaClass }.toSet()
            assertTrue(
                failureTypes.any {
                    it == OptimisticLockConflictException::class.java ||
                        it == CapacityExceededException::class.java ||
                        it == ObjectOptimisticLockingFailureException::class.java ||
                        it == DataIntegrityViolationException::class.java
                },
                "unexpected failure types: $failureTypes",
            )
        }
    }

    @Nested
    @DisplayName("PESSIMISTIC — SELECT FOR UPDATE")
    inner class Pessimistic {
        @Test
        fun `단일 예약은 정상 생성된다`() {
            val result = createOne(LockStrategy.PESSIMISTIC)
            assertEquals(LockStrategy.PESSIMISTIC, result.lockStrategy)
            assertEquals(1, reservationRepository.count())
        }

        @Test
        fun `동시 200건 중 정확히 100건만 성공한다`() {
            val outcomes = runConcurrentReservations(requestCount = 200, lockStrategy = LockStrategy.PESSIMISTIC)
            val event = eventRepository.findAll().first()

            assertEquals(100, outcomes.count { it.succeeded })
            assertEquals(100, outcomes.count { !it.succeeded })
            assertEquals(100, reservationRepository.count())
            assertEquals(100, event.reservedCount)

            val failureTypes = outcomes.filter { !it.succeeded }.mapNotNull { it.error }.map { it.javaClass }.toSet()
            assertTrue(failureTypes.all { it == CapacityExceededException::class.java })
        }
    }

    @Nested
    @DisplayName("REDIS — 분산 락")
    inner class Redis {
        @Test
        fun `단일 예약은 정상 생성된다`() {
            val result = createOne(LockStrategy.REDIS)
            assertEquals(LockStrategy.REDIS, result.lockStrategy)
            assertEquals(1, reservationRepository.count())
        }

        @Test
        fun `동시 200건 중 정확히 100건만 성공한다`() {
            val outcomes = runConcurrentReservations(requestCount = 200, lockStrategy = LockStrategy.REDIS)
            val event = eventRepository.findAll().first()

            assertEquals(200, outcomes.size)
            assertEquals(100, reservationRepository.count())
            assertEquals(100, event.reservedCount)
            assertNotEquals(0, outcomes.count { !it.succeeded })

            val failureTypes = outcomes.filter { !it.succeeded }.mapNotNull { it.error }.map { it.javaClass }.toSet()
            assertTrue(
                failureTypes.all {
                    it == CapacityExceededException::class.java ||
                        it == DistributedLockFailedException::class.java ||
                        it == ObjectOptimisticLockingFailureException::class.java ||
                        it == OptimisticLockConflictException::class.java
                },
                "unexpected failure types: $failureTypes",
            )
        }
    }
}
