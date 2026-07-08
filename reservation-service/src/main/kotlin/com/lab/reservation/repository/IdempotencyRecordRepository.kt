package com.lab.reservation.repository

import com.lab.reservation.domain.IdempotencyRecord
import org.springframework.data.jpa.repository.JpaRepository

interface IdempotencyRecordRepository : JpaRepository<IdempotencyRecord, String>
