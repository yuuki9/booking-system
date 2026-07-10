package com.booking.payment.service

import com.booking.contracts.ReservationPendingEvent
import com.booking.payment.config.PaymentGatewayProperties
import com.booking.payment.domain.PaymentStatus
import com.booking.payment.gateway.MockPaymentGateway
import com.booking.payment.gateway.PaymentGateway
import com.booking.payment.gateway.PaymentGatewayResult
import com.booking.payment.gateway.PaymentRequest
import com.booking.payment.repository.PaymentOutboxRepository
import com.booking.payment.repository.PaymentRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest
@ActiveProfiles("test")
@Import(PaymentProcessingServiceTest.TxSplitGatewayConfig::class)
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

    @Autowired
    private lateinit var recordingGateway: RecordingPaymentGateway

    @BeforeEach
    fun setUp() {
        jdbcTemplate.execute("TRUNCATE payments, payment_outbox")
        recordingGateway.reset()
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

    @Test
    fun `process commits PENDING before Mock PG so gateway runs outside the write transaction`() {
        val reservationId = UUID.randomUUID()

        paymentProcessingService.process(
            pendingEvent(
                reservationId = reservationId,
                userId = "user-tx-split",
            ),
        )

        assertEquals(
            false,
            recordingGateway.wasInTransaction,
            "Mock PG must not hold an open Spring transaction (DB connection) during approve/sleep",
        )
        assertEquals(
            true,
            recordingGateway.pendingCommittedBeforeApprove,
            "PENDING row must already be committed and visible on another connection before PG approve",
        )

        val payment = paymentRepository.findByReservationId(reservationId)!!
        assertEquals(PaymentStatus.APPROVED, payment.status)
        assertEquals(1, paymentOutboxRepository.count())
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

    @TestConfiguration
    class TxSplitGatewayConfig {
        @Bean
        @Primary
        fun recordingPaymentGateway(
            properties: PaymentGatewayProperties,
            jdbcTemplate: JdbcTemplate,
        ): RecordingPaymentGateway =
            RecordingPaymentGateway(MockPaymentGateway(properties), jdbcTemplate)
    }

    /**
     * PG 호출 순간에 (1) Spring TX 활성 여부, (2) 다른 JDBC 커넥션에서 PENDING 가시성을 기록한다.
     * TX 미분리면 PENDING이 아직 커밋되지 않아 (2)가 false가 된다 (READ COMMITTED).
     */
    class RecordingPaymentGateway(
        private val delegate: MockPaymentGateway,
        private val jdbcTemplate: JdbcTemplate,
    ) : PaymentGateway {
        @Volatile
        var wasInTransaction: Boolean? = null
            private set

        @Volatile
        var pendingCommittedBeforeApprove: Boolean? = null
            private set

        fun reset() {
            wasInTransaction = null
            pendingCommittedBeforeApprove = null
        }

        override fun approve(request: PaymentRequest): PaymentGatewayResult {
            wasInTransaction = TransactionSynchronizationManager.isActualTransactionActive()
            pendingCommittedBeforeApprove = try {
                jdbcTemplate.queryForObject(
                    "SELECT status FROM payments WHERE reservation_id = ?",
                    String::class.java,
                    request.reservationId,
                ) == PaymentStatus.PENDING.name
            } catch (_: Exception) {
                false
            }
            return delegate.approve(request)
        }
    }

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
