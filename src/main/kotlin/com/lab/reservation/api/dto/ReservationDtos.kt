package com.lab.reservation.api.dto

import com.lab.reservation.domain.Event
import com.lab.reservation.domain.Reservation
import com.lab.reservation.domain.ReservationStatus
import com.lab.reservation.service.ReservationResult
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

data class CreateReservationRequest(
    @field:NotNull val eventId: Long,
    @field:NotBlank val userId: String,
)

data class ReservationResponse(
    val reservationId: UUID,
    val eventId: Long,
    val userId: String,
    val status: ReservationStatus,
    val lockStrategy: String? = null,
    val remainingCapacity: Int? = null,
    val createdAt: Instant,
) {
    companion object {
        fun from(result: ReservationResult): ReservationResponse =
            ReservationResponse(
                reservationId = result.reservation.id,
                eventId = result.reservation.eventId,
                userId = result.reservation.userId,
                status = result.reservation.status,
                lockStrategy = result.lockStrategy.name,
                remainingCapacity = result.remainingCapacity,
                createdAt = result.reservation.createdAt,
            )

        fun from(reservation: Reservation): ReservationResponse =
            ReservationResponse(
                reservationId = reservation.id,
                eventId = reservation.eventId,
                userId = reservation.userId,
                status = reservation.status,
                createdAt = reservation.createdAt,
            )
    }
}

data class EventResponse(
    val id: Long,
    val name: String,
    val capacity: Int,
    val reservedCount: Int,
    val remainingCapacity: Int,
) {
    companion object {
        fun from(event: Event): EventResponse =
            EventResponse(
                id = event.id!!,
                name = event.name,
                capacity = event.capacity,
                reservedCount = event.reservedCount,
                remainingCapacity = event.remainingCapacity,
            )
    }
}

data class ErrorResponse(
    val code: String,
    val message: String,
)
