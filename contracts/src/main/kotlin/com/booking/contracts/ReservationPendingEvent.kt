package com.booking.contracts

import java.time.Instant
import java.util.UUID

/**
 * 서비스 간 공유 **계약(Contract)** 모듈의 이벤트 DTO.
 *
 * ## 선제 개념
 * - **계약만 공유**: 도메인 엔티티·유틸·설정을 모듈 간에 끌어오지 않는다.
 *   reservation / payment는 이 페이로드로만 대화한다 (Database per service + 느슨한 결합).
 * - **Choreography 트리거**: reservation Outbox → 이 이벤트 → payment 소비.
 *
 * ## 트레이드오프
 * - `lockStrategy`를 문자열로 실어 보냄: payment는 enum을 몰라도 되고,
 *   reservation 보상 시 Redis 선차감 여부를 복원할 수 있다 (스키마에 컬럼 추가 대신 이벤트 전파).
 */
data class ReservationPendingEvent(
    val reservationId: UUID,
    val eventId: Long,
    val userId: String,
    val amount: Long,
    val lockStrategy: String,
    val occurredAt: Instant,
)
