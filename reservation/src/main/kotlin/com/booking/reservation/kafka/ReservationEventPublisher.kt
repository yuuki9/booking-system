package com.booking.reservation.kafka

import com.booking.contracts.ReservationConfirmedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class ReservationEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, ReservationConfirmedEvent>,
    @Value("\${app.kafka.topic.reservation-confirmed}") private val topic: String,
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
