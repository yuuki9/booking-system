package com.lab.reservation

import com.lab.reservation.config.AppModeProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppModeProperties::class)
class BookingSystemApplication

fun main(args: Array<String>) {
    runApplication<BookingSystemApplication>(*args)
}
