package com.booking.payment.service

import com.booking.contracts.PaymentRefundRequestedEvent
import com.booking.payment.domain.Payment
import com.booking.payment.domain.PaymentStatus
import com.booking.payment.gateway.MockPaymentGateway
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

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(
    partitions = 1,
    topics = ["reservation.pending", "payment.result", "payment.refund"],
    brokerProperties = ["listeners=PLAINTEXT://localhost:0", "port=0"],
)
@Testcontainers(disabledWithoutDocker = true)
class PaymentRefundServiceTest {
    @Autowired
    private lateinit var paymentRefundService: PaymentRefundService

    @Autowired
    private lateinit var paymentRepository: PaymentRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setUp() {
        jdbcTemplate.execute("TRUNCATE payments, payment_outbox")
    }

    @Test
    fun `refund transitions APPROVED payment to REFUNDED`() {
        val reservationId = UUID.randomUUID()
        val paymentId = UUID.randomUUID()
        seedApprovedPayment(paymentId, reservationId, userId = "user-refund-ok")

        paymentRefundService.refund(refundEvent(paymentId, reservationId, userId = "user-refund-ok"))

        assertEquals(PaymentStatus.REFUNDED, paymentRepository.findByReservationId(reservationId)!!.status)
    }

    @Test
    fun `duplicate refund is no-op when already REFUNDED`() {
        val reservationId = UUID.randomUUID()
        val paymentId = UUID.randomUUID()
        seedApprovedPayment(paymentId, reservationId, userId = "user-refund-dup")
        val event = refundEvent(paymentId, reservationId, userId = "user-refund-dup")

        paymentRefundService.refund(event)
        paymentRefundService.refund(event)

        assertEquals(PaymentStatus.REFUNDED, paymentRepository.findByReservationId(reservationId)!!.status)
    }

    @Test
    fun `refund skips non APPROVED payment`() {
        val reservationId = UUID.randomUUID()
        val paymentId = UUID.randomUUID()
        paymentRepository.save(
            Payment(
                id = paymentId,
                reservationId = reservationId,
                eventId = 1L,
                userId = "user-pending",
                amount = 10_000,
                lockStrategy = "REDIS",
                status = PaymentStatus.PENDING,
            ),
        )

        paymentRefundService.refund(refundEvent(paymentId, reservationId, userId = "user-pending"))

        assertEquals(PaymentStatus.PENDING, paymentRepository.findByReservationId(reservationId)!!.status)
    }

    @Test
    fun `refund does not change status when Mock PG declines`() {
        val reservationId = UUID.randomUUID()
        val paymentId = UUID.randomUUID()
        seedApprovedPayment(paymentId, reservationId, userId = "${MockPaymentGateway.REFUND_FAIL_PREFIX}user")

        paymentRefundService.refund(
            refundEvent(paymentId, reservationId, userId = "${MockPaymentGateway.REFUND_FAIL_PREFIX}user"),
        )

        assertEquals(PaymentStatus.APPROVED, paymentRepository.findByReservationId(reservationId)!!.status)
    }

    private fun seedApprovedPayment(paymentId: UUID, reservationId: UUID, userId: String) {
        paymentRepository.save(
            Payment(
                id = paymentId,
                reservationId = reservationId,
                eventId = 1L,
                userId = userId,
                amount = 10_000,
                lockStrategy = "REDIS",
                status = PaymentStatus.APPROVED,
            ),
        )
    }

    private fun refundEvent(paymentId: UUID, reservationId: UUID, userId: String) =
        PaymentRefundRequestedEvent(
            paymentId = paymentId,
            reservationId = reservationId,
            eventId = 1L,
            userId = userId,
            amount = 10_000,
            reason = "LATE_APPROVAL_AFTER_CANCEL",
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
