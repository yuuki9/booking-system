package com.booking.payment.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "payment.gateway")
data class PaymentGatewayProperties(
    val failureRate: Double = 0.0,
    val timeoutRate: Double = 0.0,
    val delayMinMs: Long = 50,
    val delayMaxMs: Long = 200,
    val timeoutMs: Long = 3000,
)
