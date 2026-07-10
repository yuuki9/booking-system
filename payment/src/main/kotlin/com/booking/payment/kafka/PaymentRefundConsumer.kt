package com.booking.payment.kafka

import com.booking.contracts.PaymentRefundRequestedEvent
import com.booking.payment.service.PaymentRefundService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `payment.refund` → [PaymentRefundService].
 *
 * ## 선제 개념
 * - **보상 진입점**: reservation Outbox가 발행한 환불 요청의 payment 측 유일 트리거.
 * - **별도 consumer group** (`payment-refund`): approve 경로(`groupId=payment`)와 처리 속도·실패 모델을 분리.
 *
 * ## 트레이드오프
 * - concurrency 기본(1): 환불은 드문 경로라 병렬보다 단순 멱등·관측을 우선한다.
 *   approve 경로처럼 부하가 커지면 파티션·concurrency를 올릴 수 있다.
 */
@Component
class PaymentRefundConsumer(
    private val paymentRefundService: PaymentRefundService,
) {
    @KafkaListener(
        topics = ["\${app.kafka.topic.payment-refund}"],
        groupId = "payment-refund",
    )
    fun consume(event: PaymentRefundRequestedEvent) {
        paymentRefundService.refund(event)
    }
}
