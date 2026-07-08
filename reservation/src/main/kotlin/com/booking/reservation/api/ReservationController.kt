package com.booking.reservation.api

import com.booking.reservation.api.dto.CreateReservationRequest
import com.booking.reservation.api.dto.ReservationResponse
import com.booking.reservation.service.LockStrategyResolver
import com.booking.reservation.service.ReservationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/reservations")
class ReservationController(
    private val reservationService: ReservationService,
    private val lockStrategyResolver: LockStrategyResolver,
) {
    /**
     * 예약 생성 API.
     *
     * standard 모드 헤더:
     * - [X-Idempotency-Key]: 동일 키 재전송 시 기존 예약 반환 (200 OK)
     * - [X-Lock-Strategy]: 락 전략 (query param보다 우선순위 낮음)
     */
    @PostMapping
    fun createReservation(
        @Valid @RequestBody request: CreateReservationRequest,
        @RequestParam(required = false) lockStrategy: String?,
        @RequestHeader(name = "X-Lock-Strategy", required = false) headerLockStrategy: String?,
        @RequestHeader(name = "X-Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<ReservationResponse> {
        val strategy = lockStrategyResolver.resolve(lockStrategy, headerLockStrategy)
        val result = reservationService.createReservation(
            eventId = request.eventId,
            userId = request.userId,
            lockStrategy = strategy,
            idempotencyKey = idempotencyKey,
        )
        // [체크포인트] 멱등 재전송은 200, 최초 생성은 201
        val status = if (result.idempotentReplay) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(ReservationResponse.from(result))
    }

    @GetMapping("/{id}")
    fun getReservation(@PathVariable id: UUID): ReservationResponse =
        ReservationResponse.from(reservationService.findById(id))
}
