package com.booking.reservation.repository

import com.booking.reservation.domain.Reservation
import com.booking.reservation.domain.ReservationStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ReservationRepository : JpaRepository<Reservation, UUID> {
    /** standard 모드: (eventId, userId) 중복 예약 사전 검사 */
    fun existsByEventIdAndUserId(eventId: Long, userId: String): Boolean

    fun findByEventIdAndUserId(eventId: Long, userId: String): Reservation?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Reservation r SET r.status = :to WHERE r.id = :id AND r.status = :from")
    fun transitionStatus(
        @Param("id") id: UUID,
        @Param("from") from: ReservationStatus,
        @Param("to") to: ReservationStatus,
    ): Int
}
