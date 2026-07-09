package com.booking.reservation.repository

import com.booking.reservation.domain.Reservation
import com.booking.reservation.domain.ReservationStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface ReservationRepository : JpaRepository<Reservation, UUID> {
    /** standard 모드: (eventId, userId) 중복 예약 사전 검사 (최종 방어는 UK). */
    fun existsByEventIdAndUserId(eventId: Long, userId: String): Boolean

    fun findByEventIdAndUserId(eventId: Long, userId: String): Reservation?

    /**
     * Saga 상태 전이 가드.
     *
     * ## 선제 개념
     * CAS에 가까운 **조건부 UPDATE**: `from`이 일치할 때만 `to`로 바꾼다.
     * 영향 행 수 0 = 다른 경로(중복 이벤트·reaper)가 이미 이김 → 멱등 no-op.
     *
     * ## 트레이드오프
     * JPA 더티체킹 대신 `@Modifying`을 쓰는 이유: 행 수 확인이 필요하고,
     * 엔티티 `status`가 `val`(불변)이라 인메모리 변경이 불가하다.
     * `clearAutomatically`로 이후 재조회 시 stale 엔티티를 피한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Reservation r SET r.status = :to WHERE r.id = :id AND r.status = :from")
    fun transitionStatus(
        @Param("id") id: UUID,
        @Param("from") from: ReservationStatus,
        @Param("to") to: ReservationStatus,
    ): Int

    /**
     * Reaper용: 타임아웃된 PENDING_PAYMENT 일괄 조회.
     * 부분 인덱스 `idx_reservations_pending_created` (V3)가 이 패턴을 지원한다.
     */
    fun findByStatusAndCreatedAtBefore(status: ReservationStatus, createdAt: Instant): List<Reservation>
}
