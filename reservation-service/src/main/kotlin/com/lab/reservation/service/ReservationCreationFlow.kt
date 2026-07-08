package com.lab.reservation.service

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.domain.Reservation

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
