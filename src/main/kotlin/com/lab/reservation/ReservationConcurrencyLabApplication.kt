package com.lab.reservation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ReservationConcurrencyLabApplication

fun main(args: Array<String>) {
    runApplication<ReservationConcurrencyLabApplication>(*args)
}
