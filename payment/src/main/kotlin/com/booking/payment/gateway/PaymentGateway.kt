package com.booking.payment.gateway

import java.util.UUID

interface PaymentGateway {
    fun approve(request: PaymentRequest): PaymentGatewayResult
}

data class PaymentRequest(
    val reservationId: UUID,
    val userId: String,
    val amount: Long,
)

sealed interface PaymentGatewayResult {
    data object Approved : PaymentGatewayResult

    data class Declined(val reason: String) : PaymentGatewayResult

    data object TimedOut : PaymentGatewayResult
}
