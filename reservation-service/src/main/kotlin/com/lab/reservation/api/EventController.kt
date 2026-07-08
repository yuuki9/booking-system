package com.lab.reservation.api

import com.lab.reservation.api.dto.EventResponse
import com.lab.reservation.service.EventService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/events")
class EventController(
    private val eventService: EventService,
) {
    @GetMapping("/{id}")
    fun getEvent(@PathVariable id: Long): EventResponse =
        EventResponse.from(eventService.findById(id))
}
