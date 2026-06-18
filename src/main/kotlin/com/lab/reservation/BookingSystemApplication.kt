package com.lab.reservation

import com.lab.reservation.config.PhaseBProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PhaseBProperties::class)
class BookingSystemApplication

fun main(args: Array<String>) {
    runApplication<BookingSystemApplication>(*args)
}
