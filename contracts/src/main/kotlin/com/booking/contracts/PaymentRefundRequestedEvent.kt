package com.booking.contracts

import java.time.Instant
import java.util.UUID

/**
 * 환불 요청. reservation → payment (Saga 금전 보상).
 *
 * ## 작업 흐름
 * - reaper/실패로 예약이 이미 `CANCELLED`인 뒤 늦은 `payment.result(APPROVED)` 도착
 *   → reservation Outbox `REFUND_REQUESTED` → 이 이벤트 → payment Mock PG refund → `REFUNDED`
 *
 * ## 트레이드오프
 * - **단방향 MVP**: payment는 `REFUNDED`만 갱신하고, reservation으로 환불 결과 토픽을 보내지 않는다.
 *   예약은 이미 CANCELLED라 추가 전이가 없고, 관측은 로그·payments 상태로 충분하다.
 * - **reason**: `LATE_APPROVAL_AFTER_CANCEL` 등 관측용. payment 분기 키는 아니다 (APPROVED→REFUNDED만).
 */
data class PaymentRefundRequestedEvent(
    val paymentId: UUID,
    val reservationId: UUID,
    val eventId: Long,
    val userId: String,
    val amount: Long,
    val reason: String,
    val occurredAt: Instant,
)
