package com.lab.reservation.service.lock

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.domain.Reservation

interface ReservationLockHandler {
    val strategy: LockStrategy

    fun reserve(eventId: Long, userId: String): Reservation
}
