package com.booking.reservation.repository

import com.booking.reservation.domain.Event
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface EventRepository : JpaRepository<Event, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Optional<Event>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = "UPDATE events SET reserved_count = reserved_count - 1, version = version + 1 " +
            "WHERE id = :eventId AND reserved_count > 0",
        nativeQuery = true,
    )
    fun releaseSeat(@Param("eventId") eventId: Long): Int
}
