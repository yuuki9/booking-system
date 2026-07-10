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
 * ## 선제 개념
 * - **보상 트랜잭션(Compensation)**: reservation이 이미 CANCELLED인 채 늦은 APPROVED가 오면,
 *   좌석은 되돌렸고 결제만 남으므로 payment 측에서 PG 환불로 금전 상태를 맞춘다.
 * - **TX / PG 분리**: [PaymentProcessingService]의 approve와 같이 `refund()`는 DB 트랜잭션 밖에서 호출한다.
 * - **Idempotent consumer**: 이미 `REFUNDED`이거나 `APPROVED`가 아니면 skip. Kafka at-least-once 중복에 안전.
 *
 * ## 작업 흐름
 * ```
 * PaymentRefundRequestedEvent
 *   → APPROVED 결제 조회 (없거나 이미 REFUNDED면 skip)
 *   → MockPaymentGateway.refund (TX 밖)
 *   → TX: APPROVED → REFUNDED
 * ```
 *
 * ## 트레이드오프
 * - **환불 실패 시**: Mock 실패·실 PG 거절이면 로그만 남기고 재시도·DLQ는 하지 않는다 (랩 MVP).
 *   운영이면 outbox/상태 머신으로 환불 재시도를 두는 편이 낫다.
 * - **reservation 재전이 없음**: 이미 CANCELLED이므로 payment만 `REFUNDED`로 닫는다 (결과 토픽 미발행).
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
