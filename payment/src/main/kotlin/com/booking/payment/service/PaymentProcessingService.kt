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
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * `reservation.pending` 소비 → Mock PG 호출 → `payment.result` Outbox 적재.
 *
 * ## 선제 개념
 * - **Database per service**: `payment_db`만 접근. 예약 좌석/재고는 건드리지 않는다.
 * - **Idempotent insert**: `payments.reservation_id UNIQUE`. 중복 pending 소비 시 INSERT 실패 → skip.
 * - **TX / PG 분리**: 외부 PG는 DB 트랜잭션 밖에서 호출한다.
 *   PENDING 커밋 → approve → 결과+Outbox 커밋. PG sleep/timeout 동안 커넥션을 붙잡지 않는다.
 * - **Outbox**: 결과 기록과 outbox 행은 **결과 TX** 안에서 원자적으로 넣는다.
 *
 * ## 작업 흐름
 * ```
 * ReservationPendingEvent
 *   → TX1: INSERT payments (PENDING) 커밋   // UNIQUE 충돌이면 return
 *   → MockPaymentGateway.approve            // TX 밖
 *   → TX2: UPDATE status + INSERT payment_outbox
 * ```
 *
 * ## 트레이드오프
 * - **두 TX 사이 크래시**: PENDING만 남고 outbox가 없을 수 있다.
 *   랩에서는 pending 재소비가 UNIQUE로 skip되므로, 운영이라면 PENDING 재처리 워커/재시도가 필요하다.
 * - **거절/타임아웃도 FAILED로 통일**: reservation은 status만 보고 보상한다.
 */
@Service
class PaymentProcessingService(
    private val paymentRepository: PaymentRepository,
    private val paymentOutboxRepository: PaymentOutboxRepository,
    private val paymentGateway: PaymentGateway,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(event: ReservationPendingEvent) {
        val paymentId = beginPending(event) ?: return

        val result = paymentGateway.approve(
            PaymentRequest(
                reservationId = event.reservationId,
                userId = event.userId,
                amount = event.amount,
            ),
        )

        completeWithResult(paymentId, result)
    }

    /** TX1: PENDING 행을 커밋하고 payment id를 반환. 중복이면 null. */
    private fun beginPending(event: ReservationPendingEvent): UUID? =
        try {
            transactionTemplate.execute {
                paymentRepository.saveAndFlush(
                    Payment(
                        reservationId = event.reservationId,
                        eventId = event.eventId,
                        userId = event.userId,
                        amount = event.amount,
                        lockStrategy = event.lockStrategy,
                        status = PaymentStatus.PENDING,
                    ),
                ).id
            }
        } catch (_: DataIntegrityViolationException) {
            log.info("Duplicate payment for reservationId={}, skipping", event.reservationId)
            null
        }

    /** TX2: PG 결과를 반영하고 outbox를 같은 트랜잭션에 적재. */
    private fun completeWithResult(paymentId: UUID, result: PaymentGatewayResult) {
        transactionTemplate.executeWithoutResult {
            val payment = paymentRepository.findById(paymentId).orElseThrow()
            if (payment.status != PaymentStatus.PENDING) {
                log.info("Payment already finalized paymentId={} status={}, skipping", paymentId, payment.status)
                return@executeWithoutResult
            }

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
}
