package com.lab.reservation.kafka

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class ReservationEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, ReservationConfirmedEvent>,
    @Value("\${lab.kafka.topic.reservation-confirmed}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(event: ReservationConfirmedEvent) {
        kafkaTemplate.send(topic, event.reservationId.toString(), event)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error("Failed to publish ReservationConfirmed: {}", event.reservationId, ex)
                } else {
                    log.debug(
                        "Published ReservationConfirmed: {} offset={}",
                        event.reservationId,
                        result?.recordMetadata?.offset(),
                    )
                }
            }
    }
}
