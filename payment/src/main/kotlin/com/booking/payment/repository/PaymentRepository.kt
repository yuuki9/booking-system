package com.booking.payment.repository

import com.booking.payment.domain.Payment
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PaymentRepository : JpaRepository<Payment, UUID> {
    fun findByReservationId(reservationId: UUID): Payment?
}
