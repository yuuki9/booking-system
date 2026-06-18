package com.lab.reservation.service.outbox

import com.lab.reservation.domain.Reservation
import com.lab.reservation.domain.ReservationOutbox
import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.repository.ReservationOutboxRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Phase B: Outbox 적재.
 *
 * 예약 INSERT와 **같은 DB 트랜잭션** 안에서 outbox 행을 INSERT합니다.
 * Kafka publish는 트랜잭션 밖(ReservationOutboxPublisher)에서 수행합니다.
 */
@Service
class ReservationOutboxService(
    private val outboxRepository: ReservationOutboxRepository,
) {
    /**
     * [체크포인트] handler.reserve() 성공 직후, 커밋 전에 호출됩니다.
     */
    @Transactional
    fun enqueue(reservation: Reservation, lockStrategy: LockStrategy) {
        outboxRepository.save(
            ReservationOutbox(
                reservationId = reservation.id,
                eventId = reservation.eventId,
                userId = reservation.userId,
                lockStrategy = lockStrategy.name,
                confirmedAt = reservation.createdAt,
            ),
        )
    }
}
