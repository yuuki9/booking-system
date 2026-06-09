package com.lab.reservation.config

import com.lab.reservation.domain.Event
import com.lab.reservation.repository.EventRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("!consumer")
class DataInitializer(
    private val eventRepository: EventRepository,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        if (eventRepository.count() > 0) {
            return
        }
        val event = eventRepository.save(
            Event(
                name = "Concurrency Test Event",
                capacity = 100,
                reservedCount = 0,
            ),
        )
        log.info("Seeded test event id={} capacity={}", event.id, event.capacity)
    }
}
