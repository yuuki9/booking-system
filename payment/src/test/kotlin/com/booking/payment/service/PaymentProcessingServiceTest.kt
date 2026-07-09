package com.booking.payment.service

import com.booking.contracts.ReservationPendingEvent
import com.booking.payment.domain.PaymentStatus
import com.booking.payment.gateway.MockPaymentGateway
import com.booking.payment.repository.PaymentOutboxRepository
import com.booking.payment.repository.PaymentRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(
    partitions = 1,
    topics = ["reservation.pending", "payment.result"],
    brokerProperties = ["listeners=PLAINTEXT://localhost:0", "port=0"],
)
@Testcontainers(disabledWithoutDocker = true)
class PaymentProcessingServiceTest {
    @Autowired
    private lateinit var paymentProcessingService: PaymentProcessingService

    @Autowired
    private lateinit var paymentRepository: PaymentRepository

    @Autowired
    private lateinit var paymentOutboxRepository: PaymentOutboxRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setUp() {
        jdbcTemplate.execute("TRUNCATE payments, payment_outbox")
    }

    @Test
    fun `process approves payment and enqueues outbox`() {
        val reservationId = UUID.randomUUID()
        val event = pendingEvent(
            reservationId = reservationId,
            userId = "user-ok",
        )

        paymentProcessingService.process(event)

        val payment = paymentRepository.findByReservationId(reservationId)!!
        assertEquals(PaymentStatus.APPROVED, payment.status)
        assertNull(payment.failureReason)

        val outbox = paymentOutboxRepository.findAll().single()
        assertEquals(payment.id, outbox.paymentId)
        assertEquals(PaymentStatus.APPROVED.name, outbox.status)
        assertEquals(reservationId, outbox.reservationId)
    }

    @Test
    fun `process skips duplicate reservation id`() {
        val reservationId = UUID.randomUUID()
        val event = pendingEvent(
            reservationId = reservationId,
            userId = "user-ok",
        )

        paymentProcessingService.process(event)
        paymentProcessingService.process(event)

        assertEquals(1, paymentRepository.count())
        assertEquals(1, paymentOutboxRepository.count())
    }

    @Test
    fun `process records declined payment in outbox`() {
        val reservationId = UUID.randomUUID()

        paymentProcessingService.process(
            pendingEvent(
                reservationId = reservationId,
                userId = "${MockPaymentGateway.FAIL_PREFIX}user-1",
            ),
        )

        val payment = paymentRepository.findByReservationId(reservationId)!!
        assertEquals(PaymentStatus.FAILED, payment.status)
        assertEquals("DECLINED", payment.failureReason)

        val outbox = paymentOutboxRepository.findAll().single()
        assertEquals(PaymentStatus.FAILED.name, outbox.status)
        assertEquals("DECLINED", outbox.failureReason)
    }

    private fun pendingEvent(
        reservationId: UUID,
        userId: String,
    ) = ReservationPendingEvent(
        reservationId = reservationId,
        eventId = 1L,
        userId = userId,
        amount = 10_000,
        lockStrategy = "OPTIMISTIC",
        occurredAt = Instant.now(),
    )

    companion object {
        @Container
        @JvmStatic
        private val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("payment_db")
            .withUsername("lab")
            .withPassword("lab")

        @DynamicPropertySource
        @JvmStatic
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
