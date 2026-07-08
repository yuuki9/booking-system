package com.booking.reservation.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Kafka Outbox 패턴용 테이블.
 *
 * 예약 DB 트랜잭션이 커밋될 때 outbox 행도 함께 INSERT하고,
 * 별도 폴러(ReservationOutboxPublisher)가 Kafka로 발행한 뒤 published_at을 갱신합니다.
 */
@Entity
@Table(name = "reservation_outbox")
class ReservationOutbox(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "reservation_id", nullable = false)
    val reservationId: UUID,

    @Column(name = "event_id", nullable = false)
    val eventId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    @Column(name = "lock_strategy", nullable = false)
    val lockStrategy: String,

    @Column(name = "confirmed_at", nullable = false)
    val confirmedAt: Instant,

    @Column(name = "published_at")
    var publishedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
