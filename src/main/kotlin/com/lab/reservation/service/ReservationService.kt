package com.lab.reservation.service

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.domain.Reservation
import com.lab.reservation.exception.ReservationNotFoundException
import com.lab.reservation.kafka.ReservationConfirmedEvent
import com.lab.reservation.kafka.ReservationEventPublisher
import com.lab.reservation.repository.EventRepository
import com.lab.reservation.repository.ReservationRepository
import com.lab.reservation.service.lock.ReservationLockHandler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ReservationResult(
    val reservation: Reservation,
    val lockStrategy: LockStrategy,
    val remainingCapacity: Int,
)

@Service
class ReservationService(
    handlers: List<ReservationLockHandler>,
    private val reservationRepository: ReservationRepository,
    private val eventRepository: EventRepository,
    private val reservationEventPublisher: ReservationEventPublisher,
) {
    private val handlerMap = handlers.associateBy { it.strategy }

    @Transactional(readOnly = true)
    fun findById(id: java.util.UUID): Reservation =
        reservationRepository.findById(id).orElseThrow { ReservationNotFoundException(id) }

    fun createReservation(eventId: Long, userId: String, lockStrategy: LockStrategy): ReservationResult {
        val handler = handlerMap[lockStrategy]
            ?: throw IllegalStateException("No handler for strategy: $lockStrategy")
        val reservation = handler.reserve(eventId, userId)
        val event = eventRepository.findById(eventId).orElseThrow()
        reservationEventPublisher.publish(
            ReservationConfirmedEvent(
                reservationId = reservation.id,
                eventId = reservation.eventId,
                userId = reservation.userId,
                lockStrategy = lockStrategy.name,
                confirmedAt = reservation.createdAt,
            ),
        )
        return ReservationResult(
            reservation = reservation,
            lockStrategy = lockStrategy,
            remainingCapacity = event.remainingCapacity,
        )
    }
}
