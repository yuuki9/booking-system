package com.lab.reservation.kafka

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
@Profile("consumer")
class ReservationConfirmedConsumer {
    private val log = LoggerFactory.getLogger(javaClass)
    private var processedCount = 0L

    @KafkaListener(
        topics = ["\${app.kafka.topic.reservation-confirmed}"],
        groupId = "booking-system-consumer",
    )
    fun consume(event: ReservationConfirmedEvent) {
        processedCount++
        log.info(
            "ReservationConfirmed [#{}] reservationId={} eventId={} userId={} strategy={} at={}",
            processedCount,
            event.reservationId,
            event.eventId,
            event.userId,
            event.lockStrategy,
            event.confirmedAt,
        )
    }
}
