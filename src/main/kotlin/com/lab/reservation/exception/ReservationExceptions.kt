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
