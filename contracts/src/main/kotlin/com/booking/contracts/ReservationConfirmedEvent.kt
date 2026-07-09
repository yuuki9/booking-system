package com.booking.contracts

import java.time.Instant
import java.util.UUID

/**
 * 예약 확정 알림 (downstream 데모 consumer / 향후 알림·정산 확장 포인트).
 *
 * Saga on: payment APPROVED 후 reservation이 Outbox에 적재 → 이 이벤트 발행.
 * Saga off: 생성 직후 Outbox CONFIRMED로 바로 발행 (기존 단일 서비스 동작).
 *
 * contracts에 둔 이유: consumer 프로세스와 reservation이 동일 스키마로 역직렬화해야 한다
 * (`spring.json.trusted.packages: com.booking.contracts`).
 */
data class ReservationConfirmedEvent(
    val reservationId: UUID,
    val eventId: Long,
    val userId: String,
    val lockStrategy: String,
    val confirmedAt: Instant,
)
