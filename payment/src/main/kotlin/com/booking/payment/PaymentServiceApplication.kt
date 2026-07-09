package com.booking.payment

import com.booking.payment.config.PaymentGatewayProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PaymentGatewayProperties::class)
class PaymentServiceApplication

fun main(args: Array<String>) {
    runApplication<PaymentServiceApplication>(*args)
}
