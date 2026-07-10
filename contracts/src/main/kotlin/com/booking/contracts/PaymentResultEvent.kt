package com.booking.contracts

import java.time.Instant
import java.util.UUID

/**
 * 결제 결과. payment → reservation Saga 입력.
 *
 * ## 작업 흐름
 * - [APPROVED] → PENDING_PAYMENT → CONFIRMED + confirmed Outbox
 * - [FAILED] → CANCELLED + 좌석 반환 (`failureReason`: DECLINED / TIMEOUT 등)
 *
 * ## 트레이드오프
 * - APPROVED/FAILED 이진 분류: reservation 보상 로직을 단순화.
 *   세부 reason은 관측·디버그용이며 분기 키는 아니다.
 * - 늦은 APPROVED vs reaper CANCELLED 경합: 가드 UPDATE로 좌석 보상은 한쪽만 성공.
 *   CANCELLED 뒤 도착한 APPROVED는 reservation이 `payment.refund`로 금전 보상을 이어간다 (Phase 6).
 */
enum class PaymentResultStatus {
    APPROVED,
    FAILED,
}

data class PaymentResultEvent(
    val paymentId: UUID,
    val reservationId: UUID,
    val eventId: Long,
    val userId: String,
    val amount: Long,
    val lockStrategy: String,
    val status: PaymentResultStatus,
    val failureReason: String?,
    val occurredAt: Instant,
)
