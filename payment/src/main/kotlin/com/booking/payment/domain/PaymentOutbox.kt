package com.booking.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "payment_outbox")
class PaymentOutbox(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "payment_id", nullable = false)
    val paymentId: UUID,

    @Column(name = "reservation_id", nullable = false)
    val reservationId: UUID,

    @Column(name = "event_id", nullable = false)
    val eventId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    @Column(nullable = false)
    val amount: Long,

    @Column(name = "lock_strategy", nullable = false)
    val lockStrategy: String,

    @Column(nullable = false)
    val status: String,

    @Column(name = "failure_reason")
    val failureReason: String?,

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,

    @Column(name = "published_at")
    var publishedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
