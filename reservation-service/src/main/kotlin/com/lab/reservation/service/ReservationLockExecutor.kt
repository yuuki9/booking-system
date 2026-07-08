package com.lab.reservation.service

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.domain.Reservation
import com.lab.reservation.service.lock.ReservationLockHandler
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
