package com.lab.reservation.exception

open class ReservationException(
    val code: String,
    override val message: String,
) : RuntimeException(message)

class EventNotFoundException(eventId: Long) :
    ReservationException("EVENT_NOT_FOUND", "Event not found: $eventId")

class ReservationNotFoundException(reservationId: java.util.UUID) :
    ReservationException("RESERVATION_NOT_FOUND", "Reservation not found: $reservationId")

class CapacityExceededException :
    ReservationException("CAPACITY_EXCEEDED", "Event capacity exceeded")

class OptimisticLockConflictException :
    ReservationException("OPTIMISTIC_LOCK_CONFLICT", "Optimistic lock conflict")

class DistributedLockFailedException :
    ReservationException("DISTRIBUTED_LOCK_FAILED", "Failed to acquire distributed lock")

class InvalidLockStrategyException(value: String?) :
    ReservationException("INVALID_LOCK_STRATEGY", "Invalid lock strategy: $value")

/** standard 모드: (eventId, userId) UNIQUE 또는 사전 검사에 걸린 중복 예약 */
class DuplicateReservationException(eventId: Long, userId: String) :
    ReservationException(
        "DUPLICATE_RESERVATION",
        "User already has a reservation for event $eventId: $userId",
    )

/** standard 모드: Redis 재고 키가 초기화되지 않음 — syncFromDatabase / reset-standard 필요 */
class RedisInventoryNotInitializedException(eventId: Long) :
    ReservationException(
        "REDIS_INVENTORY_NOT_INITIALIZED",
        "Redis inventory not initialized for event $eventId",
    )
