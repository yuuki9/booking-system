package com.lab.reservation.config

import com.lab.reservation.domain.Event
import com.lab.reservation.repository.EventRepository
import com.lab.reservation.service.inventory.RedisInventoryService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("!consumer")
class DataInitializer(
    private val eventRepository: EventRepository,
    private val redisInventoryService: RedisInventoryService,
    private val phaseBProperties: PhaseBProperties,
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
                ),
            ).also {
                log.info("Seeded test event id={} capacity={}", it.id, it.capacity)
            }
        }

        // [체크포인트] Phase B: DB 잔여 좌석 → Redis event:{id}:remaining 동기화
        if (phaseBProperties.enabled && phaseBProperties.redisInventory.enabled) {
            redisInventoryService.syncFromDatabase(event.id!!)
        }
    }
}
