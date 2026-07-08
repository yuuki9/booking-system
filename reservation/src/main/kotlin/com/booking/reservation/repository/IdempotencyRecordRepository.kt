package com.booking.reservation.repository

import com.booking.reservation.domain.IdempotencyRecord
import org.springframework.data.jpa.repository.JpaRepository

interface IdempotencyRecordRepository : JpaRepository<IdempotencyRecord, String>
