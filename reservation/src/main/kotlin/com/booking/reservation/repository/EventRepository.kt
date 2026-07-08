package com.booking.reservation.repository

import com.booking.reservation.domain.Event
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface EventRepository : JpaRepository<Event, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Optional<Event>
}
