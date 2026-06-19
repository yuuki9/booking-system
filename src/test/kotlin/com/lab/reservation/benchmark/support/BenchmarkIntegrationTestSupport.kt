package com.lab.reservation.benchmark.support

import com.lab.reservation.domain.LockStrategy
import com.lab.reservation.kafka.ReservationEventPublisher
import com.lab.reservation.repository.EventRepository
import com.lab.reservation.repository.ReservationRepository
import com.lab.reservation.service.ReservationResult
import com.lab.reservation.service.ReservationService
import com.lab.reservation.service.benchmark.BenchmarkReservationFlow
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
abstract class BenchmarkIntegrationTestSupport {
    @Autowired
    protected lateinit var reservationService: ReservationService

    @Autowired
    protected lateinit var eventRepository: EventRepository

    @Autowired
    protected lateinit var reservationRepository: ReservationRepository

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    @Autowired(required = false)
    protected var benchmarkReservationFlow: BenchmarkReservationFlow? = null

    @MockBean
    protected lateinit var reservationEventPublisher: ReservationEventPublisher

    @BeforeEach
    fun setUpBenchmarkCase() {
        assertNotNull(benchmarkReservationFlow, "APP_MODE=benchmark 여야 BenchmarkReservationFlow가 로드됩니다")
        jdbcTemplate.execute("TRUNCATE idempotency_records, reservation_outbox, reservations RESTART IDENTITY CASCADE")
        jdbcTemplate.update("UPDATE events SET reserved_count = 0, version = 0 WHERE id = (SELECT MIN(id) FROM events)")
    }

    protected fun eventId(): Long = eventRepository.findAll().first().id!!

    protected data class AttemptOutcome(
        val succeeded: Boolean,
        val error: Throwable? = null,
    )

    protected fun runConcurrentReservations(
        requestCount: Int,
        lockStrategy: LockStrategy,
        timeoutSeconds: Long = 120,
    ): List<AttemptOutcome> {
        val eventId = eventId()
        val pool = Executors.newFixedThreadPool(32)
        val latch = CountDownLatch(requestCount)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<AttemptOutcome>())

        repeat(requestCount) { index ->
            pool.submit {
                try {
                    reservationService.createReservation(
                        eventId = eventId,
                        userId = "bench-user-$lockStrategy-$index",
                        lockStrategy = lockStrategy,
                        idempotencyKey = null,
                    )
                    outcomes.add(AttemptOutcome(succeeded = true))
                } catch (ex: Throwable) {
                    outcomes.add(AttemptOutcome(succeeded = false, error = ex))
                } finally {
                    latch.countDown()
                }
            }
        }

        try {
            assertTrue(
                latch.await(timeoutSeconds, TimeUnit.SECONDS),
                "Concurrent reservations did not finish within ${timeoutSeconds}s",
            )
        } finally {
            pool.shutdown()
        }

        return outcomes
    }

    protected fun createOne(lockStrategy: LockStrategy): ReservationResult =
        reservationService.createReservation(
            eventId = eventId(),
            userId = "single-user-${lockStrategy.name.lowercase()}",
            lockStrategy = lockStrategy,
            idempotencyKey = null,
        )

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
        }
    }
}
