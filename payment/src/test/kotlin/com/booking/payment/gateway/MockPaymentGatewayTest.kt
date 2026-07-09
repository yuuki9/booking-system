package com.booking.payment.gateway

import com.booking.payment.config.PaymentGatewayProperties
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MockPaymentGatewayTest {
    @Test
    fun `fail prefix returns Declined`() {
        val gateway = gateway(failureRate = 0.0, timeoutRate = 0.0)

        val result = gateway.approve(request(userId = "fail-user-1"))

        assertIs<PaymentGatewayResult.Declined>(result)
        assertEquals("DECLINED", result.reason)
    }

    @Test
    fun `timeout prefix returns TimedOut`() {
        val gateway = gateway(failureRate = 0.0, timeoutRate = 0.0, timeoutMs = 10)

        val result = gateway.approve(request(userId = "timeout-user-1"))

        assertIs<PaymentGatewayResult.TimedOut>(result)
    }

    @Test
    fun `zero rates always approve`() {
        val gateway = gateway(
            failureRate = 0.0,
            timeoutRate = 0.0,
            delayMinMs = 0,
            delayMaxMs = 0,
        )

        val result = gateway.approve(request(userId = "user-ok"))

        assertEquals(PaymentGatewayResult.Approved, result)
    }

    @Test
    fun `failure rate one always declines when timeout rate is zero`() {
        val gateway = gateway(
            failureRate = 1.0,
            timeoutRate = 0.0,
            delayMinMs = 0,
            delayMaxMs = 0,
            random = Random(0),
        )

        repeat(5) {
            val result = gateway.approve(request(userId = "user-$it"))
            assertIs<PaymentGatewayResult.Declined>(result)
        }
    }

    private fun gateway(
        failureRate: Double,
        timeoutRate: Double,
        timeoutMs: Long = 50,
        delayMinMs: Long = 0,
        delayMaxMs: Long = 0,
        random: Random = Random.Default,
    ): MockPaymentGateway =
        MockPaymentGateway(
            properties = PaymentGatewayProperties(
                failureRate = failureRate,
                timeoutRate = timeoutRate,
                delayMinMs = delayMinMs,
                delayMaxMs = delayMaxMs,
                timeoutMs = timeoutMs,
            ),
            random = random,
        )

    private fun request(userId: String) =
        PaymentRequest(
            reservationId = UUID.randomUUID(),
            userId = userId,
            amount = 10_000,
        )
}
