package com.booking.reservation.service

import com.booking.reservation.domain.LockStrategy
import com.booking.reservation.domain.Reservation
import com.booking.reservation.service.lock.ReservationLockHandler
import org.springframework.stereotype.Component

@Component
class ReservationLockExecutor(
    handlers: List<ReservationLockHandler>,
) {
    private val handlerMap = handlers.associateBy { it.strategy }

    fun reserve(eventId: Long, userId: String, lockStrategy: LockStrategy): Reservation {
        val handler = handlerMap[lockStrategy]
            ?: throw IllegalStateException("No handler for strategy: $lockStrategy")
        return handler.reserve(eventId, userId)
    }
}
