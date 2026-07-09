package com.booking.contracts

import java.time.Instant
import java.util.UUID

data class ReservationPendingEvent(
    val reservationId: UUID,
    val eventId: Long,
    val userId: String,
    val amount: Long,
    val lockStrategy: String,
    val occurredAt: Instant,
)
