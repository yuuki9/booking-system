package com.booking.reservation.service.standard

import com.booking.contracts.PaymentResultEvent
import com.booking.contracts.PaymentResultStatus
import com.booking.reservation.config.AppModeProperties
import com.booking.reservation.domain.LockStrategy
import com.booking.reservation.domain.OutboxEventType
import com.booking.reservation.domain.Reservation
import com.booking.reservation.domain.ReservationStatus
import com.booking.reservation.repository.EventRepository
import com.booking.reservation.repository.ReservationOutboxRepository
import com.booking.reservation.repository.ReservationRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
@ConditionalOnProperty(name = ["app.mode"], havingValue = "standard", matchIfMissing = true)
class StandardPaymentSagaService(
    private val appModeProperties: AppModeProperties,
    private val reservationRepository: ReservationRepository,
    private val eventRepository: EventRepository,
    private val outboxService: StandardOutboxService,
    private val redisInventoryService: StandardRedisInventoryService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun onApproved(event: PaymentResultEvent) {
        val updated = reservationRepository.transitionStatus(
            event.reservationId,
            ReservationStatus.PENDING_PAYMENT,
            ReservationStatus.CONFIRMED,
        )
        if (updated == 0) {
            log.warn("Late or duplicate approval ignored reservationId={}", event.reservationId)
            return
        }
        val reservation = reservationRepository.findById(event.reservationId).orElseThrow()
        val lockStrategy = LockStrategy.valueOf(event.lockStrategy)
        outboxService.enqueue(reservation, lockStrategy)
    }

    @Transactional
    fun onFailed(event: PaymentResultEvent) {
        compensate(event.reservationId, event.eventId, event.lockStrategy)
    }

    private fun compensate(reservationId: UUID, eventId: Long, lockStrategyValue: String) {
        val updated = reservationRepository.transitionStatus(
            reservationId,
            ReservationStatus.PENDING_PAYMENT,
            ReservationStatus.CANCELLED,
        )
        if (updated == 0) {
            log.warn("Compensation skipped reservationId={} (already processed)", reservationId)
            return
        }
        eventRepository.releaseSeat(eventId)
        val lockStrategy = LockStrategy.valueOf(lockStrategyValue)
        if (appModeProperties.standard.redisInventory.shouldApply(lockStrategy)) {
            redisInventoryService.rollback(eventId)
        }
    }
}
