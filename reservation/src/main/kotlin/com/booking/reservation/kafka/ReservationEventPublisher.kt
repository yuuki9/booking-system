package com.booking.reservation.kafka

import com.booking.contracts.ReservationConfirmedEvent
import com.booking.contracts.ReservationPendingEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class ReservationEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    @Value("\${app.kafka.topic.reservation-confirmed}") private val confirmedTopic: String,
    @Value("\${app.kafka.topic.reservation-pending}") private val pendingTopic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(event: ReservationConfirmedEvent) {
        send(confirmedTopic, event.reservationId.toString(), event)
    }

    fun publishPending(event: ReservationPendingEvent) {
        send(pendingTopic, event.reservationId.toString(), event)
    }

    private fun send(topic: String, key: String, payload: Any) {
        kafkaTemplate.send(topic, key, payload)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error("Failed to publish to {}: key={}", topic, key, ex)
                } else {
                    log.debug(
                        "Published to {}: key={} offset={}",
                        topic,
                        key,
                        result?.recordMetadata?.offset(),
                    )
                }
            }
    }
}
