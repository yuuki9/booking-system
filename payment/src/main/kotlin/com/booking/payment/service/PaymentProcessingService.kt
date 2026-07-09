package com.booking.payment.service

import com.booking.contracts.ReservationPendingEvent
import com.booking.payment.domain.Payment
import com.booking.payment.domain.PaymentOutbox
import com.booking.payment.domain.PaymentStatus
import com.booking.payment.gateway.PaymentGateway
import com.booking.payment.gateway.PaymentGatewayResult
import com.booking.payment.gateway.PaymentRequest
import com.booking.payment.repository.PaymentOutboxRepository
import com.booking.payment.repository.PaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class PaymentProcessingService(
    private val paymentRepository: PaymentRepository,
    private val paymentOutboxRepository: PaymentOutboxRepository,
    private val paymentGateway: PaymentGateway,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun process(event: ReservationPendingEvent) {
        val payment = try {
            paymentRepository.saveAndFlush(
                Payment(
                    reservationId = event.reservationId,
                    eventId = event.eventId,
                    userId = event.userId,
                    amount = event.amount,
                    lockStrategy = event.lockStrategy,
                    status = PaymentStatus.PENDING,
                ),
            )
        } catch (_: DataIntegrityViolationException) {
            log.info("Duplicate payment for reservationId={}, skipping", event.reservationId)
            return
        }

        // Mock PG sleep runs inside the same transaction for lab simplicity; production would split the TX.
        val result = paymentGateway.approve(
            PaymentRequest(
                reservationId = event.reservationId,
                userId = event.userId,
                amount = event.amount,
            ),
        )

        val now = Instant.now()
        when (result) {
            PaymentGatewayResult.Approved -> {
                payment.status = PaymentStatus.APPROVED
                payment.failureReason = null
            }

            is PaymentGatewayResult.Declined -> {
                payment.status = PaymentStatus.FAILED
                payment.failureReason = result.reason
            }

            PaymentGatewayResult.TimedOut -> {
                payment.status = PaymentStatus.FAILED
                payment.failureReason = "TIMEOUT"
            }
        }
        payment.updatedAt = now
        paymentRepository.save(payment)

        paymentOutboxRepository.save(
            PaymentOutbox(
                paymentId = payment.id,
                reservationId = payment.reservationId,
                eventId = payment.eventId,
                userId = payment.userId,
                amount = payment.amount,
                lockStrategy = payment.lockStrategy,
                status = payment.status.name,
                failureReason = payment.failureReason,
                occurredAt = now,
            ),
        )
    }
}
