package com.booking.reservation.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 예약 생명주기 (결제 Saga on 기준).
 *
 * ```
 * PENDING_PAYMENT ──APPROVED──▶ CONFIRMED
 *        │
 *        └──FAILED / TIMEOUT / reaper──▶ CANCELLED (+ 좌석 보상)
 * ```
 *
 * payment off면 생성 직후 [CONFIRMED] (기존 동작).
 * status는 VARCHAR — CHECK 없이 enum 확장 가능 (V3에서 PENDING 추가 시 DDL 불필요했던 이유).
 */
enum class ReservationStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CANCELLED,
}

@Entity
@Table(name = "reservations")
class Reservation(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "event_id", nullable = false)
    val eventId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    /**
     * 불변 필드: 상태 전이는 [com.booking.reservation.repository.ReservationRepository.transitionStatus]
     * 로만 수행한다 (가드 UPDATE + 영향 행 수).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: ReservationStatus = ReservationStatus.CONFIRMED,

    /** Reaper cutoff 기준. 생성 시각 이후 timeout-seconds가 지나면 고아로 간주. */
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
