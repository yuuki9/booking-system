package com.booking.reservation.service.standard

import com.booking.reservation.config.AppModeProperties
import com.booking.contracts.ReservationConfirmedEvent
import com.booking.reservation.kafka.ReservationEventPublisher
import com.booking.reservation.repository.ReservationOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
@Profile("!consumer")
@ConditionalOnProperty(name = ["app.mode"], havingValue = "standard", matchIfMissing = true)
class StandardOutboxPublisher(
    private val appModeProperties: AppModeProperties,
    private val outboxRepository: ReservationOutboxRepository,
    private val reservationEventPublisher: ReservationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.standard.outbox.poll-interval-ms:2000}")
    @Transactional
    fun publishPending() {
        if (!appModeProperties.standard.outbox.enabled) {
            return
        }

        val pending = outboxRepository.findUnpublished()
        if (pending.isEmpty()) {
            return
        }

        pending.forEach { row ->
            try {
                reservationEventPublisher.publish(
                    ReservationConfirmedEvent(
                        reservationId = row.reservationId,
                        eventId = row.eventId,
                        userId = row.userId,
                        lockStrategy = row.lockStrategy,
                        confirmedAt = row.confirmedAt,
                    ),
                )
                row.publishedAt = Instant.now()
                log.debug("Outbox published reservationId={}", row.reservationId)
            } catch (ex: Exception) {
                log.error("Outbox publish failed reservationId={}", row.reservationId, ex)
            }
        }
    }
}
