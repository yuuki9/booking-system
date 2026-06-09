package com.lab.reservation.service

import com.lab.reservation.domain.Event
import com.lab.reservation.exception.EventNotFoundException
import com.lab.reservation.repository.EventRepository
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
