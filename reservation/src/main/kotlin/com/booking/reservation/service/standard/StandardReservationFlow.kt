package com.booking.reservation.service.standard

import com.booking.reservation.config.AppModeProperties
import com.booking.reservation.domain.IdempotencyRecord
import com.booking.reservation.domain.LockStrategy
import com.booking.reservation.domain.Reservation
import com.booking.reservation.exception.DuplicateReservationException
import com.booking.reservation.exception.ReservationNotFoundException
import com.booking.reservation.kafka.ReservationConfirmedEvent
import com.booking.reservation.kafka.ReservationEventPublisher
import com.booking.reservation.repository.EventRepository
import com.booking.reservation.repository.IdempotencyRecordRepository
import com.booking.reservation.repository.ReservationRepository
import com.booking.reservation.service.ReservationCreationFlow
import com.booking.reservation.service.ReservationLockExecutor
import com.booking.reservation.service.ReservationResult
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * standard 모드: 멱등 → 중복검사 → Redis 선차감 → Lock Handler → Outbox → (폴러→Kafka)
 */
@Component
@ConditionalOnProperty(name = ["app.mode"], havingValue = "standard", matchIfMissing = true)
class StandardReservationFlow(
    private val appModeProperties: AppModeProperties,
    private val lockExecutor: ReservationLockExecutor,
    private val reservationRepository: ReservationRepository,
    private val eventRepository: EventRepository,
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
    private val redisInventoryService: StandardRedisInventoryService,
    private val outboxService: StandardOutboxService,
    private val reservationEventPublisher: ReservationEventPublisher,
) : ReservationCreationFlow {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun create(
        eventId: Long,
        userId: String,
        lockStrategy: LockStrategy,
        idempotencyKey: String?,
    ): ReservationResult {
        resolveIdempotentReplay(eventId, userId, lockStrategy, idempotencyKey)?.let { return it }

        assertNotDuplicate(eventId, userId)

        var redisDecremented = false
        if (appModeProperties.standard.redisInventory.shouldApply(lockStrategy)) {
            redisInventoryService.tryDecrement(eventId)
            redisDecremented = true
        }

        try {
            val reservation = lockExecutor.reserve(eventId, userId, lockStrategy)
            publishConfirmation(reservation, lockStrategy)
            saveIdempotencyKey(idempotencyKey, reservation)

            val event = eventRepository.findById(eventId).orElseThrow()
            return ReservationResult(
                reservation = reservation,
                lockStrategy = lockStrategy,
                remainingCapacity = event.remainingCapacity,
            )
        } catch (ex: Exception) {
            if (redisDecremented) {
                redisInventoryService.rollback(eventId)
                log.debug("Redis inventory compensated eventId={} reason={}", eventId, ex.javaClass.simpleName)
            }
            throw mapPersistenceException(eventId, userId, ex)
        }
    }

    private fun resolveIdempotentReplay(
        eventId: Long,
        userId: String,
        lockStrategy: LockStrategy,
        idempotencyKey: String?,
    ): ReservationResult? {
        if (idempotencyKey.isNullOrBlank()) {
            return null
        }
        val record = idempotencyRecordRepository.findById(idempotencyKey).orElse(null) ?: return null
        val reservation = reservationRepository.findById(record.reservationId).orElseThrow {
            ReservationNotFoundException(record.reservationId)
        }
        val event = eventRepository.findById(eventId).orElseThrow()
        log.info("Idempotent replay key={} reservationId={}", idempotencyKey, reservation.id)
        return ReservationResult(
            reservation = reservation,
            lockStrategy = lockStrategy,
            remainingCapacity = event.remainingCapacity,
            idempotentReplay = true,
        )
    }

    private fun assertNotDuplicate(eventId: Long, userId: String) {
        if (!appModeProperties.standard.duplicateCheck.enabled) {
            return
        }
        if (reservationRepository.existsByEventIdAndUserId(eventId, userId)) {
            throw DuplicateReservationException(eventId, userId)
        }
    }

    private fun publishConfirmation(reservation: Reservation, lockStrategy: LockStrategy) {
        if (appModeProperties.standard.outbox.enabled) {
            outboxService.enqueue(reservation, lockStrategy)
        } else {
            reservationEventPublisher.publish(
                ReservationConfirmedEvent(
                    reservationId = reservation.id,
                    eventId = reservation.eventId,
                    userId = reservation.userId,
                    lockStrategy = lockStrategy.name,
                    confirmedAt = reservation.createdAt,
                ),
            )
        }
    }

    private fun saveIdempotencyKey(idempotencyKey: String?, reservation: Reservation) {
        if (idempotencyKey.isNullOrBlank()) {
            return
        }
        idempotencyRecordRepository.save(
            IdempotencyRecord(
                idempotencyKey = idempotencyKey,
                reservationId = reservation.id,
            ),
        )
    }

    private fun mapPersistenceException(eventId: Long, userId: String, ex: Exception): Exception {
        if (ex is DuplicateReservationException) {
            return ex
        }
        val duplicateKeyViolation = generateSequence(ex as Throwable) { it.cause }.any { node ->
            node.message?.contains("uk_reservations_event_user", ignoreCase = true) == true
        }
        if (ex is DataIntegrityViolationException && duplicateKeyViolation) {
            return DuplicateReservationException(eventId, userId)
        }
        return ex
    }
}
