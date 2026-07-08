package com.booking.reservation.api

import com.booking.reservation.api.dto.ErrorResponse
import com.booking.reservation.exception.CapacityExceededException
import com.booking.reservation.exception.DistributedLockFailedException
import com.booking.reservation.exception.DuplicateReservationException
import com.booking.reservation.exception.EventNotFoundException
import com.booking.reservation.exception.InvalidLockStrategyException
import com.booking.reservation.exception.OptimisticLockConflictException
import com.booking.reservation.exception.RedisInventoryNotInitializedException
import com.booking.reservation.exception.ReservationException
import com.booking.reservation.exception.ReservationNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(InvalidLockStrategyException::class)
    fun handleInvalidLockStrategy(ex: InvalidLockStrategyException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(ex.code, ex.message))

    @ExceptionHandler(EventNotFoundException::class, ReservationNotFoundException::class)
    fun handleNotFound(ex: ReservationException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(ex.code, ex.message))

    @ExceptionHandler(
        CapacityExceededException::class,
        OptimisticLockConflictException::class,
        DistributedLockFailedException::class,
        DuplicateReservationException::class,
    )
    fun handleConflict(ex: ReservationException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.code, ex.message))

    @ExceptionHandler(RedisInventoryNotInitializedException::class)
    fun handleRedisInventoryNotReady(ex: RedisInventoryNotInitializedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse(ex.code, ex.message))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = ex.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse("VALIDATION_ERROR", message))
    }
}
