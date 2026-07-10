package com.booking.payment.service

import com.booking.contracts.PaymentRefundRequestedEvent
import com.booking.payment.domain.PaymentStatus
import com.booking.payment.gateway.PaymentGateway
import com.booking.payment.gateway.PaymentGatewayRefundResult
import com.booking.payment.gateway.PaymentRefundRequest
import com.booking.payment.repository.PaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * `payment.refund` 소비 → Mock PG 환불 → `payments.status = REFUNDED`.
 *
 * ## 작업 흐름
 * ```
 * PaymentRefundRequestedEvent
 *   → APPROVED 결제 조회 (없거나 이미 REFUNDED면 skip)
 *   → MockPaymentGateway.refund (TX 밖)
 *   → TX: APPROVED → REFUNDED
 * ```
 */
@Service
class PaymentRefundService(
    private val paymentRepository: PaymentRepository,
    private val paymentGateway: PaymentGateway,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun refund(event: PaymentRefundRequestedEvent) {
        val payment = paymentRepository.findByReservationId(event.reservationId)
            ?: paymentRepository.findById(event.paymentId).orElse(null)
        if (payment == null) {
            log.warn("Refund skipped payment not found reservationId={}", event.reservationId)
            return
        }
        if (payment.status == PaymentStatus.REFUNDED) {
            log.info("Refund skipped already REFUNDED paymentId={}", payment.id)
            return
        }
        if (payment.status != PaymentStatus.APPROVED) {
            log.info("Refund skipped paymentId={} status={}", payment.id, payment.status)
            return
        }

        when (
            val result = paymentGateway.refund(
                PaymentRefundRequest(
                    paymentId = payment.id,
                    reservationId = event.reservationId,
                    userId = event.userId,
                    amount = event.amount,
                ),
            )
        ) {
            PaymentGatewayRefundResult.Refunded -> markRefunded(payment.id)
            is PaymentGatewayRefundResult.Failed -> {
                log.error("Refund failed paymentId={} reason={}", payment.id, result.reason)
            }
        }
    }

    private fun markRefunded(paymentId: UUID) {
        transactionTemplate.executeWithoutResult {
            val current = paymentRepository.findById(paymentId).orElseThrow()
            if (current.status == PaymentStatus.REFUNDED) {
                return@executeWithoutResult
            }
            if (current.status != PaymentStatus.APPROVED) {
                log.info("Refund finalize skipped paymentId={} status={}", paymentId, current.status)
                return@executeWithoutResult
            }
            current.status = PaymentStatus.REFUNDED
            current.updatedAt = Instant.now()
            paymentRepository.save(current)
        }
    }
}
