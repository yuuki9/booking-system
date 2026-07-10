package com.booking.reservation.domain

object OutboxEventType {
    const val CONFIRMED = "CONFIRMED"
    const val PENDING = "PENDING"
    const val REFUND_REQUESTED = "REFUND_REQUESTED"
}
