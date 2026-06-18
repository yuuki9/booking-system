package com.lab.reservation.service

import com.lab.reservation.config.PhaseBProperties
import com.lab.reservation.domain.IdempotencyRecord
import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.domain.Reservation
import com.lab.reservation.exception.DuplicateReservationException
import com.lab.reservation.exception.ReservationNotFoundException
import com.lab.reservation.kafka.ReservationConfirmedEvent
import com.lab.reservation.kafka.ReservationEventPublisher
import com.lab.reservation.repository.EventRepository
import com.lab.reservation.repository.IdempotencyRecordRepository
import com.lab.reservation.repository.ReservationRepository
import com.lab.reservation.service.inventory.RedisInventoryService
import com.lab.reservation.service.lock.ReservationLockHandler
import com.lab.reservation.service.outbox.ReservationOutboxService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ReservationResult(
    val reservation: Reservation,
    val lockStrategy: LockStrategy,
    val remainingCapacity: Int,
    /** Phase B: X-Idempotency-Key 재전송으로 기존 예약을 반환한 경우 true */
    val idempotentReplay: Boolean = false,
)

/**
 * 예약 생성 오케스트레이션.
 *
 * Phase B(현업 1스텝) 흐름:
 *   멱등성 → 중복 검사 → Redis 선차감 → Lock Handler(DB) → Outbox → (폴러→Kafka)
 *
 * lab.original 흐름(phase-b.enabled=false):
 *   Lock Handler → Kafka 직접 publish
 */
@Service
class ReservationService(
    handlers: List<ReservationLockHandler>,
    private val phaseBProperties: PhaseBProperties,
    private val reservationRepository: ReservationRepository,
    private val eventRepository: EventRepository,
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
    private val redisInventoryService: RedisInventoryService,
    private val reservationOutboxService: ReservationOutboxService,
    private val reservationEventPublisher: ReservationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handlerMap = handlers.associateBy { it.strategy }

    @Transactional(readOnly = true)
    fun findById(id: java.util.UUID): Reservation =
        reservationRepository.findById(id).orElseThrow { ReservationNotFoundException(id) }

    @Transactional
    fun createReservation(
        eventId: Long,
        userId: String,
        lockStrategy: LockStrategy,
        idempotencyKey: String?,
    ): ReservationResult {
        // ── Phase B 비활성 시: lab 원본 경로 (4전략 비교용) ──
        if (!phaseBProperties.enabled) {
            return createReservationLab(eventId, userId, lockStrategy)
        }

        // [체크포인트 1] 멱등성 키 — 네트워크 재시도 시 동일 예약 재사용 (HTTP 200)
        resolveIdempotentReplay(eventId, userId, lockStrategy, idempotencyKey)?.let { return it }

        // [체크포인트 2] 중복 예약 — DB UNIQUE(event_id, user_id) 이전 1차 방어
        assertNotDuplicate(eventId, userId)

        // [체크포인트 3] Redis 재고 선차감 — DB 락 전에 마감 빠르게 거절 (NONE 전략은 skip)
        var redisDecremented = false
        if (phaseBProperties.redisInventory.shouldApply(lockStrategy)) {
            redisInventoryService.tryDecrement(eventId)
            redisDecremented = true
        }

        try {
            // [체크포인트 4] Lock Strategy Handler — DB에 reserved_count++ & reservation INSERT
            val reservation = reserveWithHandler(eventId, userId, lockStrategy)

            // [체크포인트 5] Outbox INSERT — Kafka는 트랜잭션 커밋 후 ReservationOutboxPublisher가 발행
            publishConfirmation(reservation, lockStrategy)

            // [체크포인트 6] 멱등성 키 저장 — 이후 동일 키 재전송 시 [체크포인트 1]에서 조회
            saveIdempotencyKey(idempotencyKey, reservation)

            val event = eventRepository.findById(eventId).orElseThrow()
            return ReservationResult(
                reservation = reservation,
                lockStrategy = lockStrategy,
                remainingCapacity = event.remainingCapacity,
            )
        } catch (ex: Exception) {
            // [체크포인트 7] 보상 — Redis 선차감만 rollback (DB는 @Transactional rollback)
            if (redisDecremented) {
                redisInventoryService.rollback(eventId)
                log.debug("Redis inventory compensated eventId={} reason={}", eventId, ex.javaClass.simpleName)
            }
            throw mapPersistenceException(eventId, userId, ex)
        }
    }

    /** lab 모드: Handler → Kafka 직접 publish (Phase B 레이어 없음) */
    private fun createReservationLab(
        eventId: Long,
        userId: String,
        lockStrategy: LockStrategy,
    ): ReservationResult {
        val reservation = reserveWithHandler(eventId, userId, lockStrategy)
        reservationEventPublisher.publish(
            ReservationConfirmedEvent(
                reservationId = reservation.id,
                eventId = reservation.eventId,
                userId = reservation.userId,
                lockStrategy = lockStrategy.name,
                confirmedAt = reservation.createdAt,
            ),
        )
        val event = eventRepository.findById(eventId).orElseThrow()
        return ReservationResult(
            reservation = reservation,
            lockStrategy = lockStrategy,
            remainingCapacity = event.remainingCapacity,
        )
    }

    private fun reserveWithHandler(eventId: Long, userId: String, lockStrategy: LockStrategy): Reservation {
        val handler = handlerMap[lockStrategy]
            ?: throw IllegalStateException("No handler for strategy: $lockStrategy")
        return handler.reserve(eventId, userId)
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
        if (!phaseBProperties.duplicateCheck.enabled) {
            return
        }
        if (reservationRepository.existsByEventIdAndUserId(eventId, userId)) {
            throw DuplicateReservationException(eventId, userId)
        }
    }

    private fun publishConfirmation(reservation: Reservation, lockStrategy: LockStrategy) {
        if (phaseBProperties.outbox.enabled) {
            reservationOutboxService.enqueue(reservation, lockStrategy)
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

    /** UNIQUE 제약 등 DB 레벨 중복을 API 예외로 변환 */
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
