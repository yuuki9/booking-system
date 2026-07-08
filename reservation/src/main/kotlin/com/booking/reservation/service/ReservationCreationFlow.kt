package com.booking.reservation.service

import com.booking.reservation.domain.LockStrategy
import com.booking.reservation.domain.Reservation

data class ReservationResult(
    val reservation: Reservation,
    val lockStrategy: LockStrategy,
    val remainingCapacity: Int,
    val idempotentReplay: Boolean = false,
)

interface ReservationCreationFlow {
    fun create(
        eventId: Long,
        userId: String,
        lockStrategy: LockStrategy,
        idempotencyKey: String?,
    ): ReservationResult
}
