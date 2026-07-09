package com.booking.reservation.kafka

import com.booking.contracts.PaymentResultEvent
import com.booking.contracts.PaymentResultStatus
import com.booking.reservation.service.standard.StandardPaymentSagaService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
@Profile("!consumer")
@ConditionalOnProperty(name = ["app.mode"], havingValue = "standard", matchIfMissing = true)
class PaymentResultConsumer(
    private val sagaService: StandardPaymentSagaService,
) {
    @KafkaListener(
        topics = ["\${app.kafka.topic.payment-result}"],
        groupId = "reservation",
    )
    fun consume(event: PaymentResultEvent) {
        when (event.status) {
            PaymentResultStatus.APPROVED -> sagaService.onApproved(event)
            PaymentResultStatus.FAILED -> sagaService.onFailed(event)
        }
    }
}
