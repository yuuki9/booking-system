package com.booking.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "payments")
class Payment(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "reservation_id", nullable = false, unique = true)
    val reservationId: UUID,

    @Column(name = "event_id", nullable = false)
    val eventId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    @Column(nullable = false)
    val amount: Long,

    @Column(name = "lock_strategy", nullable = false)
    val lockStrategy: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentStatus,

    @Column(name = "failure_reason")
    var failureReason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
