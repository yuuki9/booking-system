package com.booking.contracts

import java.time.Instant
import java.util.UUID

enum class PaymentResultStatus {
    APPROVED,
    FAILED,
}

data class PaymentResultEvent(
    val paymentId: UUID,
    val reservationId: UUID,
    val eventId: Long,
    val userId: String,
    val amount: Long,
    val lockStrategy: String,
    val status: PaymentResultStatus,
    val failureReason: String?,
    val occurredAt: Instant,
)
