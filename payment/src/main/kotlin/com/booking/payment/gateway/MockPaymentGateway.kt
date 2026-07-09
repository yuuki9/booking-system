package com.booking.payment.gateway

import com.booking.payment.config.PaymentGatewayProperties
import org.springframework.stereotype.Component
import kotlin.random.Random

@Component
class MockPaymentGateway(
    private val properties: PaymentGatewayProperties,
    private val random: Random = Random.Default,
) : PaymentGateway {
    override fun approve(request: PaymentRequest): PaymentGatewayResult {
        when {
            request.userId.startsWith(FAIL_PREFIX) ->
                return PaymentGatewayResult.Declined("DECLINED")

            request.userId.startsWith(TIMEOUT_PREFIX) -> {
                Thread.sleep(properties.timeoutMs)
                return PaymentGatewayResult.TimedOut
            }
        }

        if (random.nextDouble() < properties.timeoutRate) {
            Thread.sleep(properties.timeoutMs)
            return PaymentGatewayResult.TimedOut
        }

        if (random.nextDouble() < properties.failureRate) {
            return PaymentGatewayResult.Declined("DECLINED")
        }

        val delayRange = properties.delayMaxMs - properties.delayMinMs
        val delayMs = properties.delayMinMs + if (delayRange > 0) {
            random.nextLong(delayRange + 1)
        } else {
            0L
        }
        if (delayMs > 0) {
            Thread.sleep(delayMs)
        }

        return PaymentGatewayResult.Approved
    }

    companion object {
        const val FAIL_PREFIX = "fail-"
        const val TIMEOUT_PREFIX = "timeout-"
    }
}
