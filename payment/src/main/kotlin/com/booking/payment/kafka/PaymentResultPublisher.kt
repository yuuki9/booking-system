package com.booking.payment.kafka

import com.booking.contracts.PaymentResultEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class PaymentResultPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    @Value("\${app.kafka.topic.payment-result}") private val paymentResultTopic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(event: PaymentResultEvent) {
        kafkaTemplate.send(paymentResultTopic, event.reservationId.toString(), event)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error(
                        "Failed to publish payment result reservationId={}",
                        event.reservationId,
                        ex,
                    )
                } else {
                    log.debug(
                        "Published payment result reservationId={} offset={}",
                        event.reservationId,
                        result?.recordMetadata?.offset(),
                    )
                }
            }
    }
}
