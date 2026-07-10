package com.booking.reservation.repository

import com.booking.reservation.domain.ReservationOutbox
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ReservationOutboxRepository : JpaRepository<ReservationOutbox, UUID> {
    @Query(
        """
        SELECT o FROM ReservationOutbox o
        WHERE o.publishedAt IS NULL
        ORDER BY o.createdAt ASC
        """,
    )
    fun findUnpublished(): List<ReservationOutbox>

    fun existsByReservationIdAndEventType(reservationId: UUID, eventType: String): Boolean
}
