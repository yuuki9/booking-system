package com.lab.reservation.api

import com.lab.reservation.api.dto.ErrorResponse
import com.lab.reservation.exception.CapacityExceededException
import com.lab.reservation.exception.DistributedLockFailedException
import com.lab.reservation.exception.EventNotFoundException
import com.lab.reservation.exception.InvalidLockStrategyException
import com.lab.reservation.exception.OptimisticLockConflictException
import com.lab.reservation.exception.ReservationException
import com.lab.reservation.exception.ReservationNotFoundException
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
    )
    fun handleConflict(ex: ReservationException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.code, ex.message))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = ex.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse("VALIDATION_ERROR", message))
    }
}
