package com.booking.reservation.service

import com.booking.reservation.domain.Event
import com.booking.reservation.exception.EventNotFoundException
import com.booking.reservation.repository.EventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class EventService(
    private val eventRepository: EventRepository,
) {
    @Transactional(readOnly = true)
    fun findById(id: Long): Event =
        eventRepository.findById(id).orElseThrow { EventNotFoundException(id) }
}
