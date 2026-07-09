package com.booking.payment.repository

import com.booking.payment.domain.PaymentOutbox
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface PaymentOutboxRepository : JpaRepository<PaymentOutbox, UUID> {
    @Query(
        """
        SELECT o FROM PaymentOutbox o
        WHERE o.publishedAt IS NULL
        ORDER BY o.createdAt ASC
        """,
    )
    fun findUnpublished(): List<PaymentOutbox>
}
