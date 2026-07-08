package com.booking.reservation.service.lock

import com.booking.reservation.domain.LockStrategy
import com.booking.reservation.domain.Reservation

interface ReservationLockHandler {
    val strategy: LockStrategy

    fun reserve(eventId: Long, userId: String): Reservation
}
