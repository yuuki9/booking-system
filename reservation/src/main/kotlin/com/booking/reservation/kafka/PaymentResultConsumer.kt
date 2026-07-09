package com.booking.reservation.kafka

import com.booking.contracts.PaymentResultEvent
import com.booking.contracts.PaymentResultStatus
import com.booking.reservation.service.standard.StandardPaymentSagaService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `payment.result` → [StandardPaymentSagaService] 위임.
 *
 * ## 선제 개념
 * - **Choreography 경계**: reservation은 payment DB를 모른다. 이벤트 payload만으로 상태 전이.
 * - **Thin consumer**: 역직렬화·라우팅만 하고 도메인 규칙은 Saga 서비스에 둔다 (테스트·재사용 용이).
 *
 * ## 트레이드오프
 * - **groupId = reservation**: 모듈명과 통일. scale-out 시 파티션만큼 병렬 소비.
 * - **basic 모드 / consumer 프로파일**: 빈이 안 뜨거나 이벤트가 없어 무해 (PAYMENT_ENABLED와 독립적으로
 *   빈은 뜰 수 있으나, pending이 없으면 실질 no-op).
 */
@Component
@Profile("!consumer")
@ConditionalOnProperty(name = ["app.mode"], havingValue = "standard", matchIfMissing = true)
class PaymentResultConsumer(
    private val sagaService: StandardPaymentSagaService,
) {
    @KafkaListener(
        topics = ["\${app.kafka.topic.payment-result}"],
        groupId = "reservation",
    )
    fun consume(event: PaymentResultEvent) {
        when (event.status) {
            PaymentResultStatus.APPROVED -> sagaService.onApproved(event)
            PaymentResultStatus.FAILED -> sagaService.onFailed(event)
        }
    }
}
