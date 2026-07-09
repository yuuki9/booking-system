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
    /** PESSIMISTIC 핸들러용 SELECT FOR UPDATE. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Optional<Event>

    /**
     * Saga 보상: 좌석 1석 반환.
     *
     * ## 선제 개념
     * 네이티브 원자 UPDATE — 읽기-수정-쓰기 레이스 없이 `reserved_count`를 줄인다.
     * `reserved_count > 0` 가드로 음수 방지.
     *
     * ## 트레이드오프
     * `version`도 +1: OPTIMISTIC 핸들러와 충돌 시 재시도 유도.
     * 보상 경로와 신규 예약 경로가 같은 행을 건드리는 것이 정상이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = "UPDATE events SET reserved_count = reserved_count - 1, version = version + 1 " +
            "WHERE id = :eventId AND reserved_count > 0",
        nativeQuery = true,
    )
    fun releaseSeat(@Param("eventId") eventId: Long): Int
}
