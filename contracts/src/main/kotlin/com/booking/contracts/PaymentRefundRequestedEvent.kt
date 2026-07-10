package com.booking.contracts

import java.time.Instant
import java.util.UUID

/**
 * reservation → payment 환불 요청 (늦은 APPROVED after CANCELLED 등).
 */
data class PaymentRefundRequestedEvent(
    val paymentId: UUID,
    val reservationId: UUID,
    val eventId: Long,
    val userId: String,
    val amount: Long,
    val reason: String,
    val occurredAt: Instant,
)
