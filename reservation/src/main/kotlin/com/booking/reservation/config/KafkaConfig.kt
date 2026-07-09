package com.booking.reservation.config

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.TopicBuilder

@Configuration
@EnableKafka
class KafkaConfig {
    @Bean
    @Profile("!aws")
    fun reservationConfirmedTopic(
        @Value("\${app.kafka.topic.reservation-confirmed}") topic: String,
    ): NewTopic =
        TopicBuilder.name(topic)
            .partitions(3)
            .replicas(1)
            .build()
}
