package com.booking.reservation.service.lock

import com.booking.reservation.domain.LockStrategy
import com.booking.reservation.domain.Reservation
import com.booking.reservation.domain.ReservationStatus

interface ReservationLockHandler {
    val strategy: LockStrategy

    fun reserve(
        eventId: Long,
        userId: String,
        initialStatus: ReservationStatus = ReservationStatus.CONFIRMED,
    ): Reservation
}
