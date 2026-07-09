package com.booking.payment.kafka

import com.booking.contracts.PaymentResultEvent
import com.booking.contracts.PaymentResultStatus
import com.booking.payment.domain.PaymentStatus
import com.booking.payment.repository.PaymentOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class PaymentOutboxPublisher(
    private val outboxRepository: PaymentOutboxRepository,
    private val paymentResultPublisher: PaymentResultPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.outbox.poll-interval-ms:2000}")
    @Transactional
    fun publishPending() {
        val pending = outboxRepository.findUnpublished()
        if (pending.isEmpty()) {
            return
        }

        pending.forEach { row ->
            try {
                paymentResultPublisher.publish(
                    PaymentResultEvent(
                        paymentId = row.paymentId,
                        reservationId = row.reservationId,
                        eventId = row.eventId,
                        userId = row.userId,
                        amount = row.amount,
                        lockStrategy = row.lockStrategy,
                        status = when (row.status) {
                            PaymentStatus.APPROVED.name -> PaymentResultStatus.APPROVED
                            PaymentStatus.FAILED.name -> PaymentResultStatus.FAILED
                            else -> error("Unexpected outbox status=${row.status} paymentId=${row.paymentId}")
                        },
                        failureReason = row.failureReason,
                        occurredAt = row.occurredAt,
                    ),
                )
                row.publishedAt = Instant.now()
                log.debug("Payment outbox published reservationId={} status={}", row.reservationId, row.status)
            } catch (ex: Exception) {
                log.error(
                    "Payment outbox publish failed reservationId={} status={}",
                    row.reservationId,
                    row.status,
                    ex,
                )
            }
        }
    }
}
