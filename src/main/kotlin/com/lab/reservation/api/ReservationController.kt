package com.lab.reservation.api

import com.lab.reservation.api.dto.CreateReservationRequest
import com.lab.reservation.api.dto.ReservationResponse
import com.lab.reservation.service.LockStrategyResolver
import com.lab.reservation.service.ReservationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/reservations")
class ReservationController(
    private val reservationService: ReservationService,
    private val lockStrategyResolver: LockStrategyResolver,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createReservation(
        @Valid @RequestBody request: CreateReservationRequest,
        @RequestParam(required = false) lockStrategy: String?,
        @RequestHeader(name = "X-Lock-Strategy", required = false) headerLockStrategy: String?,
    ): ReservationResponse {
        val strategy = lockStrategyResolver.resolve(lockStrategy, headerLockStrategy)
        val result = reservationService.createReservation(request.eventId, request.userId, strategy)
        return ReservationResponse.from(result)
    }

    @GetMapping("/{id}")
    fun getReservation(@PathVariable id: UUID): ReservationResponse =
        ReservationResponse.from(reservationService.findById(id))
}
