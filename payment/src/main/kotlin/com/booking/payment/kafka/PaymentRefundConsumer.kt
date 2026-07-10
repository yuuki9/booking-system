package com.booking.payment.kafka

import com.booking.contracts.PaymentRefundRequestedEvent
import com.booking.payment.service.PaymentRefundService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class PaymentRefundConsumer(
    private val paymentRefundService: PaymentRefundService,
) {
    @KafkaListener(
        topics = ["\${app.kafka.topic.payment-refund}"],
        groupId = "payment-refund",
    )
    fun consume(event: PaymentRefundRequestedEvent) {
        paymentRefundService.refund(event)
    }
}
