package com.lab.reservation.service.outbox

import com.lab.reservation.config.PhaseBProperties
import com.lab.reservation.kafka.ReservationConfirmedEvent
import com.lab.reservation.kafka.ReservationEventPublisher
import com.lab.reservation.repository.ReservationOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Phase B: Outbox 폴러.
 *
 * [체크포인트] DB 커밋이 끝난 뒤 주기적으로 unpublished outbox를 Kafka로 발행합니다.
 * 발행 실패 시 published_at을 갱신하지 않아 다음 폴링에서 재시도합니다.
 */
@Component
@Profile("!consumer")
class ReservationOutboxPublisher(
    private val phaseBProperties: PhaseBProperties,
    private val outboxRepository: ReservationOutboxRepository,
    private val reservationEventPublisher: ReservationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${lab.phase-b.outbox.poll-interval-ms:2000}")
    @Transactional
    fun publishPending() {
        if (!phaseBProperties.enabled || !phaseBProperties.outbox.enabled) {
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
                // [체크포인트] 실패 시 published_at 미갱신 → 다음 폴링에서 재시도
                log.error("Outbox publish failed reservationId={}", row.reservationId, ex)
            }
        }
    }
}
