package com.lab.reservation.repository

import com.lab.reservation.domain.Reservation
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReservationRepository : JpaRepository<Reservation, UUID>
