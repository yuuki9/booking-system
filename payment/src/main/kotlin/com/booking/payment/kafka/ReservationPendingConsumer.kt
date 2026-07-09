package com.booking.payment.kafka

import com.booking.contracts.ReservationPendingEvent
import com.booking.payment.service.PaymentProcessingService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `reservation.pending` → [PaymentProcessingService].
 *
 * ## 선제 개념
 * - **서비스 경계 진입점**: payment 모듈의 유일한 외부 트리거 (동기 API 없음).
 * - **concurrency=3**: 토픽 파티션(설계상 3)과 맞춰 병렬 처리. 파티션 키는 reservationId.
 *
 * ## 트레이드오프
 * - Listener 스레드에서 Mock PG sleep → 동시성 상한이 concurrency에 묶임.
 *   부하가 크면 워커 큐로 넘기는 편이 낫다 (랩에서는 단순함 우선).
 */
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
