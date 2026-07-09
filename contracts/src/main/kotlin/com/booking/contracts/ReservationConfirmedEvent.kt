package com.booking.contracts

import java.time.Instant
import java.util.UUID

data class ReservationConfirmedEvent(
    val reservationId: UUID,
    val eventId: Long,
    val userId: String,
    val lockStrategy: String,
    val confirmedAt: Instant,
)
