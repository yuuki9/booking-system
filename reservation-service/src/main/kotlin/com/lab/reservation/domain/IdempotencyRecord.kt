package com.lab.reservation.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * HTTP 헤더 [X-Idempotency-Key] 재전송 시
 * 동일 예약 결과를 다시 돌려주기 위한 매핑 테이블.
 */
@Entity
@Table(name = "idempotency_records")
class IdempotencyRecord(
    @Id
    @Column(name = "idempotency_key")
    val idempotencyKey: String,

    @Column(name = "reservation_id", nullable = false)
    val reservationId: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
