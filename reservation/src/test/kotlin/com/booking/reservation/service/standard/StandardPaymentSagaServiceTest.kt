package com.booking.reservation.service.standard

import com.booking.contracts.PaymentResultEvent
import com.booking.contracts.PaymentResultStatus
import com.booking.reservation.domain.LockStrategy
import com.booking.reservation.domain.OutboxEventType
import com.booking.reservation.domain.Reservation
import com.booking.reservation.domain.ReservationStatus
import com.booking.reservation.repository.EventRepository
import com.booking.reservation.repository.ReservationOutboxRepository
import com.booking.reservation.repository.ReservationRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class StandardPaymentSagaServiceTest {
    @Autowired
    private lateinit var sagaService: StandardPaymentSagaService

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var outboxRepository: ReservationOutboxRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var redisInventoryService: StandardRedisInventoryService

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @BeforeEach
    fun setUp() {
        jdbcTemplate.execute("TRUNCATE idempotency_records, reservation_outbox, reservations RESTART IDENTITY CASCADE")
        jdbcTemplate.update("UPDATE events SET reserved_count = 0, version = 0, price = 10000 WHERE id = (SELECT MIN(id) FROM events)")
        redisInventoryService.syncFromDatabase(eventId())
    }

    @Test
    fun `onApproved transitions PENDING_PAYMENT to CONFIRMED and enqueues confirmed outbox`() {
        val reservation = seedPendingReservation()

        sagaService.onApproved(paymentResult(reservation, PaymentResultStatus.APPROVED))

        val updated = reservationRepository.findById(reservation.id).orElseThrow()
        assertEquals(ReservationStatus.CONFIRMED, updated.status)

        val outbox = outboxRepository.findAll().single()
        assertEquals(OutboxEventType.CONFIRMED, outbox.eventType)
        assertEquals(reservation.id, outbox.reservationId)
    }

    @Test
    fun `onFailed transitions to CANCELLED releases seat and rolls back redis inventory`() {
        val reservation = seedPendingReservation()
        jdbcTemplate.update("UPDATE events SET reserved_count = 1 WHERE id = ?", reservation.eventId)

        sagaService.onFailed(paymentResult(reservation, PaymentResultStatus.FAILED, failureReason = "DECLINED"))

        val updated = reservationRepository.findById(reservation.id).orElseThrow()
        assertEquals(ReservationStatus.CANCELLED, updated.status)
        assertEquals(0, eventRepository.findById(reservation.eventId).orElseThrow().reservedCount)
        assertEquals("1", redisTemplate.opsForValue().get("event:${reservation.eventId}:remaining"))
    }

    @Test
    fun `onApproved is no-op when reservation is already CONFIRMED`() {
        val reservation = seedPendingReservation()
        sagaService.onApproved(paymentResult(reservation, PaymentResultStatus.APPROVED))
        sagaService.onApproved(paymentResult(reservation, PaymentResultStatus.APPROVED))

        assertEquals(1, outboxRepository.count())
        assertEquals(ReservationStatus.CONFIRMED, reservationRepository.findById(reservation.id).orElseThrow().status)
    }

    @Test
    fun `onFailed is no-op when reservation is already CANCELLED`() {
        val reservation = seedPendingReservation()
        jdbcTemplate.update("UPDATE events SET reserved_count = 1 WHERE id = ?", reservation.eventId)
        sagaService.onFailed(paymentResult(reservation, PaymentResultStatus.FAILED))
        sagaService.onFailed(paymentResult(reservation, PaymentResultStatus.FAILED))

        assertEquals(ReservationStatus.CANCELLED, reservationRepository.findById(reservation.id).orElseThrow().status)
        assertEquals(0, eventRepository.findById(reservation.eventId).orElseThrow().reservedCount)
    }

    @Test
    fun `compensateTimeout cancels pending reservation and rolls back redis when inventory enabled`() {
        val reservation = seedPendingReservation()
        jdbcTemplate.update("UPDATE events SET reserved_count = 1 WHERE id = ?", reservation.eventId)

        sagaService.compensateTimeout(reservation.id, reservation.eventId)

        assertEquals(ReservationStatus.CANCELLED, reservationRepository.findById(reservation.id).orElseThrow().status)
        assertEquals(0, eventRepository.findById(reservation.eventId).orElseThrow().reservedCount)
        assertEquals("1", redisTemplate.opsForValue().get("event:${reservation.eventId}:remaining"))
    }

    @Test
    fun `compensateTimeout is no-op when reservation is already processed`() {
        val reservation = seedPendingReservation()
        jdbcTemplate.update("UPDATE events SET reserved_count = 1 WHERE id = ?", reservation.eventId)
        sagaService.onFailed(paymentResult(reservation, PaymentResultStatus.FAILED))
        sagaService.compensateTimeout(reservation.id, reservation.eventId)

        assertEquals(ReservationStatus.CANCELLED, reservationRepository.findById(reservation.id).orElseThrow().status)
        assertEquals(0, eventRepository.findById(reservation.eventId).orElseThrow().reservedCount)
    }

    private fun seedPendingReservation(): Reservation {
        val event = eventRepository.findById(eventId()).orElseThrow()
        return reservationRepository.save(
            Reservation(
                eventId = event.id!!,
                userId = "saga-test-user",
                status = ReservationStatus.PENDING_PAYMENT,
            ),
        )
    }

    private fun paymentResult(
        reservation: Reservation,
        status: PaymentResultStatus,
        failureReason: String? = null,
    ): PaymentResultEvent =
        PaymentResultEvent(
            paymentId = UUID.randomUUID(),
            reservationId = reservation.id,
            eventId = reservation.eventId,
            userId = reservation.userId,
            amount = 10_000,
            lockStrategy = LockStrategy.REDIS.name,
            status = status,
            failureReason = failureReason,
            occurredAt = Instant.now(),
        )

    private fun eventId(): Long = eventRepository.findAll().first().id!!

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("booking_system")
            .withUsername("lab")
            .withPassword("lab")

        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            registry.add("spring.kafka.bootstrap-servers") { "localhost:9092" }
            registry.add("spring.autoconfigure.exclude") {
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
            }
            registry.add("app.mode") { "standard" }
            registry.add("app.standard.payment.enabled") { "true" }
        }
    }
}
