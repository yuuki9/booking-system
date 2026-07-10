package com.booking.payment.gateway

import java.util.UUID

interface PaymentGateway {
    fun approve(request: PaymentRequest): PaymentGatewayResult

    fun refund(request: PaymentRefundRequest): PaymentGatewayRefundResult
}

data class PaymentRequest(
    val reservationId: UUID,
    val userId: String,
    val amount: Long,
)

data class PaymentRefundRequest(
    val paymentId: UUID,
    val reservationId: UUID,
    val userId: String,
    val amount: Long,
)

sealed interface PaymentGatewayResult {
    data object Approved : PaymentGatewayResult

    data class Declined(val reason: String) : PaymentGatewayResult

    data object TimedOut : PaymentGatewayResult
}

sealed interface PaymentGatewayRefundResult {
    data object Refunded : PaymentGatewayRefundResult

    data class Failed(val reason: String) : PaymentGatewayRefundResult
}
