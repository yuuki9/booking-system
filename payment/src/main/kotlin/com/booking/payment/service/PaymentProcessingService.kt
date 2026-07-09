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

/**
 * `reservation.pending` 소비 → Mock PG 호출 → `payment.result` Outbox 적재.
 *
 * ## 선제 개념
 * - **Database per service**: `payment_db`만 접근. 예약 좌석/재고는 건드리지 않는다.
 *   결과는 Kafka로만 reservation에 알린다 (경계 넘은 공유 DB 금지).
 * - **Idempotent insert**: `payments.reservation_id UNIQUE`. 중복 pending 소비 시 INSERT 실패 → skip.
 *   (Kafka at-least-once + Outbox 재발행에 대한 1차 방어)
 * - **Outbox**: PG 결과와 outbox 행을 같은 TX에 넣어 “결제 기록만 있고 이벤트 없음”을 막는다.
 *
 * ## 작업 흐름
 * ```
 * ReservationPendingEvent
 *   → INSERT payments (PENDING)  // UNIQUE 충돌이면 return
 *   → MockPaymentGateway.approve
 *   → UPDATE status APPROVED | FAILED
 *   → INSERT payment_outbox → 폴러가 payment.result 발행
 * ```
 *
 * ## 트레이드오프
 * - **PG sleep을 같은 @Transactional 안에서 수행**: 랩 단순화. 커넥션을 수 초 점유한다.
 *   실무에서는 TX를 짧게 끊고 (PENDING 저장 커밋 → PG 호출 → 결과 TX) 또는 비동기 워커로 분리한다.
 * - **거절/타임아웃도 FAILED로 통일**: reservation은 status만 보고 보상한다. reason은 관측/디버그용.
 */
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
