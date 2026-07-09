package com.booking.payment.kafka

import com.booking.contracts.ReservationPendingEvent
import com.booking.payment.service.PaymentProcessingService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class ReservationPendingConsumer(
    private val paymentProcessingService: PaymentProcessingService,
) {
    @KafkaListener(
        topics = ["\${app.kafka.topic.reservation-pending}"],
        groupId = "payment",
        concurrency = "3",
    )
    fun consume(event: ReservationPendingEvent) {
        paymentProcessingService.process(event)
    }
}
