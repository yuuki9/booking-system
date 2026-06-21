package com.lab.reservation.service.lock

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.domain.Reservation
import com.lab.reservation.exception.CapacityExceededException
import com.lab.reservation.exception.EventNotFoundException
import com.lab.reservation.repository.ReservationRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * NONE — 락·@Version 없이 read → check → increment (lab 시나리오 1: 초과 예약 재현).
 *
 * JPA save()를 쓰면 [Event.version] 낙관적 락이 적용되어 비관적/낙관적 락처럼 동작하므로,
 * raw SQL로 reserved_count만 증가시킵니다.
 */
@Component
class NoneLockHandler(
    private val jdbcTemplate: JdbcTemplate,
    private val reservationRepository: ReservationRepository,
) : ReservationLockHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val strategy: LockStrategy = LockStrategy.NONE

    @Transactional
    override fun reserve(eventId: Long, userId: String): Reservation {
        val counts = jdbcTemplate.query(
            "SELECT reserved_count, capacity FROM events WHERE id = ?",
            { rs, _ ->
                rs.getInt("reserved_count") to rs.getInt("capacity")
            },
            eventId,
        ).firstOrNull() ?: throw EventNotFoundException(eventId)

        val (reservedCount, capacity) = counts
        if (reservedCount >= capacity) {
            log.warn("NONE capacity exceeded eventId={} reserved={}/{}", eventId, reservedCount, capacity)
            throw CapacityExceededException()
        }

        jdbcTemplate.update(
            "UPDATE events SET reserved_count = reserved_count + 1 WHERE id = ?",
            eventId,
        )
        return reservationRepository.save(Reservation(eventId = eventId, userId = userId))
    }
}
