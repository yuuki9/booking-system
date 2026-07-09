package com.booking.reservation.config

import com.booking.reservation.domain.Event
import com.booking.reservation.repository.EventRepository
import com.booking.reservation.service.standard.StandardRedisInventoryService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("!consumer")
class DataInitializer(
    private val eventRepository: EventRepository,
    private val standardRedisInventoryService: ObjectProvider<StandardRedisInventoryService>,
    private val appModeProperties: AppModeProperties,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        val event = if (eventRepository.count() > 0) {
            eventRepository.findAll().first()
        } else {
            eventRepository.save(
                Event(
                    name = "데모 공연",
                    capacity = 100,
                    reservedCount = 0,
                    price = 10_000,
                ),
            ).also {
                log.info("Seeded test event id={} capacity={}", it.id, it.capacity)
            }
        }

        if (appModeProperties.isStandardMode() && appModeProperties.standard.redisInventory.enabled) {
            standardRedisInventoryService.ifAvailable { it.syncFromDatabase(event.id!!) }
        }
    }
}
