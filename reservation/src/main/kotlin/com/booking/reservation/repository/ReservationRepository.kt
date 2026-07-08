package com.booking.reservation.repository

import com.booking.reservation.domain.Reservation
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReservationRepository : JpaRepository<Reservation, UUID> {
    /** standard 모드: (eventId, userId) 중복 예약 사전 검사 */
    fun existsByEventIdAndUserId(eventId: Long, userId: String): Boolean

    fun findByEventIdAndUserId(eventId: Long, userId: String): Reservation?
}
