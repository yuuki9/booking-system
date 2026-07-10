# 결제 Saga 기반 MSA 전환 설계서

- 작성일: 2026-07-08
- 갱신일: 2026-07-09
- 상태: 설계 확정 (Phase 0~5 완료)
- 대상 독자: 이 문서만 보고 구현하는 AI 에이전트 / 개발자
- 관련 문서: `AGENT.md`, `docs/superpowers/specs/2026-06-10-reservation-concurrency-lab-design.md`

---

## 0. 문서 사용 규칙 (구현 에이전트 필독)

1. 이 문서는 **구현 순서(Phase 0~5)** 를 강제한다. 반드시 순서대로 진행하고, 각 Phase의 **완료 조건(DoD)** 을 검증한 뒤 다음 Phase로 넘어간다.
2. **basic 모드(`BasicReservationFlow`, `service/basic/`, `service/lock/`)는 절대 수정하지 않는다.** 단, Phase 2의 `ReservationLockExecutor.reserve()` 시그니처 변경은 예외이며, 이때도 basic 호출부는 기본 파라미터로 기존 동작을 유지해야 한다.
3. DB 스키마 변경은 **Flyway migration 파일 추가로만** 한다. 기존 migration 파일(V1, V2)은 수정 금지.
4. 이 문서에 명시되지 않은 기능(알림, 정산, 사용자 서비스 등)은 추가하지 않는다.
5. 패키지 이동 시 기존 패키지명 `com.booking.reservation`은 유지한다. 신규 코드만 `com.booking.contracts`, `com.booking.payment`를 사용한다.
6. 각 Phase 완료 시점에 반드시 컴파일과 테스트가 통과해야 한다. 깨진 상태로 다음 Phase 진행 금지.
7. **모듈·디렉터리 명명**: Gradle 모듈명은 `-service` 접미사 없이 `contracts`, `reservation`, `payment`만 사용한다. (`:payment-service` ❌ → `:payment` ✅) Docker Compose 서비스명·`MODULE` 빌드 ARG·경로도 동일하게 `payment`를 쓴다.

---

## 1. 배경과 목표

### 1.1 현재 상태

- 단일 Gradle 모듈, 단일 Spring Boot 앱(`booking-system`).
- `APP_MODE=basic`(락 4종 비교 실험) / `APP_MODE=standard`(멱등·중복검사·Redis 선차감·Outbox) 이원 구조.
- 예약은 생성 즉시 `CONFIRMED`가 되고, Outbox 폴러가 `reservation.confirmed` 토픽으로 발행. 별도 `consumer` 프로파일 프로세스가 로그로 소비.

### 1.2 목표

결제(payment) 도메인을 **별도 서비스**로 신설하여 서비스 경계를 넘는 트랜잭션을 **choreography Saga + 보상 트랜잭션**으로 처리한다.

- 예약 생성 → `PENDING_PAYMENT` → 결제 승인 시 `CONFIRMED` / 실패 시 `CANCELLED` + 좌석 반환(보상).
- 결제는 **mock PG**로 구현하되, 실패·타임아웃·지연을 **설정으로 주입 가능**하게 만든다.
- 레포는 유지하고 **Gradle 멀티 모듈**로 전환한다. 서비스 간 공유는 `contracts` 모듈(이벤트 DTO)만 허용한다.

### 1.3 비목표 (하지 않는 것)

- 실제 PG 연동, 결제 화면, 금액 계산 로직.
- basic 모드에 결제 붙이기 (basic은 락 실험실로 유지).
- 서비스 3개 이상으로 쪼개기, API Gateway 신설(기존 Nginx 유지), 서비스 디스커버리, orchestrator 방식 Saga.
- reservation ↔ payment 간 동기 HTTP 호출 (통신은 Kafka 이벤트만).

### 1.4 핵심 설계 원칙

| 원칙 | 적용 |
|------|------|
| Database per service | `booking_system` DB는 reservation 모듈만, `payment_db`는 payment 모듈만 접근 |
| 계약만 공유 | `contracts` 모듈에는 Kafka 이벤트 DTO만. 도메인/유틸/설정 공유 금지 |
| 비동기 우선 | 서비스 간 통신은 Kafka. 각 서비스는 자체 Outbox로 발행 |
| 하위 호환 | `app.standard.payment.enabled=false`(기본값)면 기존 standard 동작 100% 유지 |

---

## 2. 목표 아키텍처

```
Client / k6
    │ POST /api/v1/reservations
    ▼
 Nginx (scale3 프로필, 기존 유지)
    │
    ▼
┌─────────────────────────────┐          ┌─────────────────────────────┐
│ reservation (8080)          │          │ payment (8081)              │
│                             │          │                             │
│  - 멱등/중복검사/락/Redis 선차감 │          │  - MockPaymentGateway       │
│  - 예약 PENDING_PAYMENT 생성  │          │    (실패/타임아웃/지연 주입)    │
│  - payment.result 소비       │          │  - reservation.pending 소비  │
│  - 승인→CONFIRMED            │          │  - payments 기록             │
│  - 실패→CANCELLED+좌석 반환    │          │  - payment_outbox 발행       │
│  - 타임아웃 reaper            │          │                             │
│                             │          │                             │
│  DB: booking_system         │          │  DB: payment_db             │
│  Redis: event:{id}:remaining│          │  (Redis 미사용)               │
└──────────┬──────────────────┘          └──────────┬──────────────────┘
           │ produce                                │ produce
           │ reservation.pending ──────────────────▶│ (consume)
           │ reservation.confirmed                  │ payment.result
           │◀────────────────────────────────────── │
           ▼                                        ▼
        ┌──────────────────── Kafka ────────────────────┐
        │ topics: reservation.pending / payment.result   │
        │         reservation.confirmed (기존)            │
        └──────────────────────┬────────────────────────┘
                               │ reservation.confirmed
                               ▼
                    reservation-consumer (기존 로그 데모, 유지)
```

### 2.1 서비스 분리 근거 (README/면접용 서사)

- 예약: 자기 DB 안에서 정합성을 지키는 문제 (락, 재고).
- 결제: 통제 불가능한 외부(PG)와 통신하는 문제 (타임아웃, 불확실한 응답, 재시도).
- 트랜잭션 경계와 실패 모델이 달라 하나의 로컬 트랜잭션으로 묶을 수 없다 → Saga + 보상 트랜잭션.

---

## 3. 최종 디렉토리 구조

```
booking-system/
├── settings.gradle.kts               # 3개 모듈 include
├── build.gradle.kts                  # 루트: 공통 플러그인/버전 관리
├── gradle.properties                 # 기존 유지
├── Dockerfile                        # ARG MODULE 방식으로 변경
├── docker-compose.yml                # payment, payment_db init 추가
├── docker-compose.infra.yml          # payment_db init 추가
├── contracts/
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/booking/contracts/
│       ├── ReservationPendingEvent.kt
│       ├── ReservationConfirmedEvent.kt   # 기존 kafka 패키지에서 이동
│       └── PaymentResultEvent.kt
├── reservation/
│   ├── build.gradle.kts
│   └── src/                          # 기존 src/ 전체 이동 (main + test)
│       └── main/kotlin/com/booking/reservation/
│           ├── ... (기존 구조 유지)
│           ├── kafka/PaymentResultConsumer.kt        # 신규
│           └── service/standard/
│               ├── StandardPaymentSagaService.kt     # 신규 (승인/보상)
│               └── StandardPendingReservationReaper.kt  # 신규 (Phase 5)
├── payment/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/com/booking/payment/
│       │   ├── PaymentServiceApplication.kt
│       │   ├── config/KafkaConfig.kt
│       │   ├── domain/Payment.kt
│       │   ├── domain/PaymentOutbox.kt
│       │   ├── repository/PaymentRepository.kt
│       │   ├── repository/PaymentOutboxRepository.kt
│       │   ├── gateway/PaymentGateway.kt
│       │   ├── gateway/MockPaymentGateway.kt
│       │   ├── kafka/ReservationPendingConsumer.kt
│       │   ├── kafka/PaymentOutboxPublisher.kt
│       │   └── service/PaymentProcessingService.kt
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/V1__init.sql
│       └── test/kotlin/com/booking/payment/
│           └── gateway/MockPaymentGatewayTest.kt
├── scripts/
│   ├── db/init-payment-db.sh         # 신규: postgres 컨테이너 초기화 스크립트
│   ├── reset-standard.sh / .ps1      # payment_db 초기화 추가
│   └── k6/standard/
│       ├── payment-saga.js           # 신규 (Phase 5)
│       └── payment-failure.js        # 신규 (Phase 5)
└── docs/ ...
```

---

## 4. 이벤트 계약 (contracts 모듈)

### 4.1 토픽 목록

| 토픽 | 방향 | 파티션 키 | 설명 |
|------|------|-----------|------|
| `reservation.pending` | reservation → payment | reservationId | 결제 요청. saga 시작 |
| `payment.result` | payment → reservation | reservationId | 승인/실패 결과. saga 종료 트리거 |
| `reservation.confirmed` | reservation → (consumer 데모) | reservationId | 기존 유지. 결제 승인 후 발행 |

토픽 생성: 로컬(`!aws` 프로파일)에서는 각 모듈의 `KafkaConfig`가 `NewTopic` bean으로 생성(파티션 3, replica 1 — 기존 `reservation.confirmed`와 동일). `reservation.pending`·`reservation.confirmed`는 reservation 모듈이, `payment.result`는 payment 모듈이 생성한다.

### 4.2 이벤트 DTO (아래 코드 그대로 구현)

```kotlin
// contracts/src/main/kotlin/com/booking/contracts/ReservationPendingEvent.kt
package com.booking.contracts

import java.time.Instant
import java.util.UUID

data class ReservationPendingEvent(
    val reservationId: UUID,
    val eventId: Long,
    val userId: String,
    val amount: Long,          // 이벤트 price 스냅샷 (KRW)
    val lockStrategy: String,  // 보상 시 Redis 복원 여부 판단에 사용
    val occurredAt: Instant,
)
```

```kotlin
// contracts/src/main/kotlin/com/booking/contracts/PaymentResultEvent.kt
package com.booking.contracts

import java.time.Instant
import java.util.UUID

enum class PaymentResultStatus { APPROVED, FAILED }

data class PaymentResultEvent(
    val paymentId: UUID,
    val reservationId: UUID,
    val eventId: Long,
    val userId: String,
    val amount: Long,
    val lockStrategy: String,      // ReservationPendingEvent에서 그대로 전달(pass-through)
    val status: PaymentResultStatus,
    val failureReason: String?,    // FAILED일 때만: DECLINED | TIMEOUT | ERROR
    val occurredAt: Instant,
)
```

```kotlin
// contracts/src/main/kotlin/com/booking/contracts/ReservationConfirmedEvent.kt
package com.booking.contracts

import java.time.Instant
import java.util.UUID

// 기존 com.booking.reservation.kafka.ReservationConfirmedEvent를 필드 변경 없이 이동
data class ReservationConfirmedEvent(
    val reservationId: UUID,
    val eventId: Long,
    val userId: String,
    val lockStrategy: String,
    val confirmedAt: Instant,
)
```

### 4.3 직렬화 규칙 (중요)

- Producer: 기존과 동일하게 `JsonSerializer` 사용. 타입 헤더(`__TypeId__`)가 자동으로 붙는다.
- Consumer: 두 서비스가 **같은 FQCN의 contracts 클래스**를 공유하므로 타입 헤더로 역직렬화가 해결된다.
- 각 서비스 `application.yml`에서 `spring.json.trusted.packages: com.booking.contracts`로 변경하고, 기존의 `spring.json.value.default.type` 설정은 **제거**한다 (토픽별 이벤트 타입이 여러 개가 되므로 default type은 오동작 원인).
- consumer 프로파일(로그 데모)의 설정도 동일하게 갱신한다.

---

## 5. Saga 흐름 상세

### 5.1 상태 모델

`ReservationStatus` enum 확장 (기존 `CONFIRMED`만 존재):

```kotlin
enum class ReservationStatus {
    PENDING_PAYMENT,  // 신규: 좌석 선점, 결제 대기
    CONFIRMED,        // 기존: 결제 승인 완료 (flag off일 땐 생성 즉시)
    CANCELLED,        // 신규: 결제 실패/타임아웃으로 보상 완료
}
```

Payment 상태: `PENDING → APPROVED | FAILED` (payment 모듈 내부).

### 5.2 정상 흐름 (happy path)

```
Client          reservation                Kafka           payment
  │                    │                     │                    │
  │ POST /reservations │                     │                    │
  ├───────────────────▶│                     │                    │
  │                    │ 멱등키 확인            │                    │
  │                    │ 중복검사              │                    │
  │                    │ Redis 선차감          │                    │
  │                    │ 락 → 예약 생성         │                    │
  │                    │   status=PENDING_PAYMENT                 │
  │                    │ outbox(PENDING) 적재  │                    │
  │ 201 PENDING_PAYMENT│                     │                    │
  │◀───────────────────┤                     │                    │
  │                    │ [폴러] reservation.pending               │
  │                    ├────────────────────▶├───────────────────▶│
  │                    │                     │                    │ payments INSERT (PENDING)
  │                    │                     │                    │ MockPG.approve() → 승인
  │                    │                     │                    │ payments → APPROVED
  │                    │                     │                    │ payment_outbox 적재
  │                    │                     │  payment.result    │ [폴러] 발행
  │                    │◀────────────────────┤◀───────────────────┤
  │                    │ status: PENDING_PAYMENT → CONFIRMED (가드된 UPDATE)
  │                    │ outbox(CONFIRMED) 적재 → reservation.confirmed 발행
  │ GET /reservations/{id} → status=CONFIRMED │                    │
  │◀───────────────────┤                     │                    │
```

클라이언트는 201 응답의 `status=PENDING_PAYMENT`를 받고 `GET /api/v1/reservations/{id}`를 폴링해 확정을 확인한다. (API 신설 없음 — 기존 GET 재사용.)

### 5.3 결제 실패 → 보상 트랜잭션

```
payment: MockPG 거절 → payments FAILED(DECLINED) → payment.result(FAILED)
        │
        ▼
reservation (PaymentResultConsumer):
  1. UPDATE reservations SET status='CANCELLED'
       WHERE id=:id AND status='PENDING_PAYMENT'     ← 가드: 0행이면 이미 처리됨 → 로그 후 skip
  2. UPDATE events SET reserved_count = reserved_count - 1, version = version + 1
       WHERE id=:eventId AND reserved_count > 0       ← 원자적 감소
  3. lockStrategy가 Redis 선차감 대상이었으면 (shouldApply 판정 동일 로직)
       redisInventoryService.rollback(eventId)        ← INCR로 재고 복원
```

1~3은 하나의 `@Transactional` 안에서 실행하되, 3(Redis)은 DB 커밋 후 실행 순서가 보장되도록 트랜잭션 커밋 이후에 수행해도 무방하다. **단순 구현 우선: 같은 메서드 안에서 DB UPDATE 2개 → Redis INCR 순서로 실행한다.**

### 5.4 타임아웃 / 이벤트 유실 방어 (Phase 5)

두 겹의 방어:

1. **payment 내부 타임아웃**: MockPG가 지연/타임아웃을 시뮬레이션하면 payment 모듈이 `FAILED(TIMEOUT)`으로 기록하고 `payment.result`를 발행한다. (정상 경로로 보상이 돈다.)
2. **reservation reaper**: `payment.result` 자체가 유실된 경우의 안전망. `@Scheduled`(10초 주기)로 `PENDING_PAYMENT`이면서 `created_at`이 `app.standard.payment.timeout-seconds`(기본 60초)보다 오래된 예약을 5.3과 동일한 보상 경로로 `CANCELLED` 처리한다.
   - reaper의 타임아웃(60s)은 MockPG 최대 지연보다 충분히 커야 한다.
   - reaper와 늦게 도착한 `payment.result`가 경합해도 **status 가드 UPDATE** 덕분에 한쪽만 이긴다.
   - **알려진 한계 (문서화만, 구현 안 함)**: reaper가 취소한 뒤 늦은 APPROVED가 도착하면 "결제는 됐는데 좌석은 없는" 상태가 된다. 이때는 경고 로그만 남긴다. 실무라면 payment 취소 보상 이벤트가 필요하다 — README에 트레이드오프로 명시할 것.

### 5.5 멱등성 규칙 (이벤트 중복 소비 대응)

| 위치 | 메커니즘 |
|------|----------|
| payment의 `reservation.pending` 소비 | `payments.reservation_id UNIQUE` 제약. 중복 INSERT 시 `DataIntegrityViolationException` catch → 로그 후 skip |
| reservation의 `payment.result` 소비 | status 가드 UPDATE (`WHERE status='PENDING_PAYMENT'`). 0행이면 skip |
| Outbox 발행 | 기존과 동일 at-least-once. 위 두 가드가 중복을 흡수 |

### 5.6 feature flag 동작

`app.standard.payment.enabled` (env: `PAYMENT_ENABLED`, 기본 `false`):

| | `false` (기본) | `true` |
|---|---|---|
| 예약 생성 시 status | `CONFIRMED` (기존 동작) | `PENDING_PAYMENT` |
| outbox 적재 | `CONFIRMED` 타입 → `reservation.confirmed` | `PENDING` 타입 → `reservation.pending` |
| PaymentResultConsumer / reaper | bean은 떠 있어도 무해 (이벤트가 없음) | 활성 동작 |

기존 테스트·k6 벤치마크는 flag off로 그대로 통과해야 한다. docker-compose에서는 `PAYMENT_ENABLED: "true"`로 켠다.

---

## 6. reservation 모듈 변경 상세

### 6.1 Flyway `V3__payment_saga.sql`

```sql
-- 1) 이벤트 가격 (결제 금액 스냅샷용)
ALTER TABLE events
    ADD COLUMN price BIGINT NOT NULL DEFAULT 10000;

-- 2) outbox 다중 이벤트 타입 지원
ALTER TABLE reservation_outbox
    ADD COLUMN event_type VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED',
    ADD COLUMN amount BIGINT;

-- 3) reaper 조회용 부분 인덱스
CREATE INDEX idx_reservations_pending_created
    ON reservations (created_at)
    WHERE status = 'PENDING_PAYMENT';
```

주의: `status` 컬럼은 `VARCHAR(32)`이고 CHECK 제약이 없으므로 enum 값 추가에 DDL이 필요 없다.

### 6.2 엔티티 변경

- `Event`: `price: Long` 필드 추가 (`@Column(nullable = false)`, 기본값 10000). `DataInitializer` 시드에도 `price = 10000` 명시.
- `ReservationStatus`: 5.1의 3개 값.
- `ReservationOutbox`: `eventType: String`(기본 `"CONFIRMED"`), `amount: Long?` 필드 추가.

### 6.3 락 경로 시그니처 변경 (유일한 공용 코드 변경)

`ReservationLockExecutor.reserve()`와 4종 `ReservationLockHandler` 구현이 `Reservation`을 생성하므로 초기 status를 주입할 수 있어야 한다.

```kotlin
// 기본 파라미터로 기존 호출부(basic 포함)는 무변경 동작
fun reserve(
    eventId: Long,
    userId: String,
    lockStrategy: LockStrategy,
    initialStatus: ReservationStatus = ReservationStatus.CONFIRMED,
): Reservation
```

각 핸들러(`NoneLockHandler`, `OptimisticLockHandler`, `PessimisticLockHandler`, `RedisLockHandler`)는 받은 `initialStatus`로 `Reservation`을 생성하도록 파라미터를 전달만 한다. **핸들러의 락 로직 자체는 수정 금지.**

### 6.4 `StandardReservationFlow.create()` 변경

flag on일 때의 분기 (기존 흐름 순서는 유지):

```
멱등 replay 확인 → 중복검사 → Redis 선차감
→ lockExecutor.reserve(..., initialStatus = if (paymentEnabled) PENDING_PAYMENT else CONFIRMED)
→ outbox 적재:
     paymentEnabled == true  → enqueuePending(reservation, lockStrategy, amount = event.price)
     paymentEnabled == false → 기존 enqueue (CONFIRMED)
→ 멱등키 저장 (기존과 동일 — PENDING 상태여도 저장한다. 재전송 시 현재 상태 그대로 반환)
```

`StandardOutboxService`에 `enqueuePending(reservation, lockStrategy, amount)` 추가: `eventType = "PENDING"`, `amount` 채워서 저장.

### 6.5 `StandardOutboxPublisher` 변경

`event_type`으로 라우팅:

- `"PENDING"` → `ReservationPendingEvent(reservationId, eventId, userId, amount!!, lockStrategy, occurredAt = confirmedAt)` → 토픽 `reservation.pending`
- `"CONFIRMED"` → 기존 `ReservationConfirmedEvent` → 토픽 `reservation.confirmed`

발행 시 Kafka 메시지 key는 `reservationId.toString()`을 사용한다 (기존 publisher가 key 없이 보내고 있다면 key 추가 — 두 토픽 모두).

### 6.6 신규: `PaymentResultConsumer` + `StandardPaymentSagaService`

```kotlin
// kafka/PaymentResultConsumer.kt — @Profile("!consumer"), standard 모드 조건부
@KafkaListener(topics = ["\${app.kafka.topic.payment-result}"], groupId = "reservation")
fun consume(event: PaymentResultEvent) → sagaService로 위임
```

`StandardPaymentSagaService`:

- `onApproved(event)`: 가드 UPDATE(`PENDING_PAYMENT → CONFIRMED`). 성공(1행)이면 `StandardOutboxService.enqueue()`로 CONFIRMED outbox 적재 (기존 `reservation.confirmed` 흐름 재사용). 0행이면 WARN 로그(늦은 승인 등) 후 skip.
- `onFailed(event)`: 5.3의 보상 3단계. Redis 복원 여부는 `appModeProperties.standard.redisInventory.shouldApply(LockStrategy.valueOf(event.lockStrategy))`로 판정.

가드 UPDATE는 JPA 더티체킹이 아니라 `@Modifying @Query`로 구현한다 (원자성 + 영향 행 수 확인 필요):

```kotlin
// ReservationRepository에 추가
@Modifying
@Query("UPDATE Reservation r SET r.status = :to WHERE r.id = :id AND r.status = :from")
fun transitionStatus(id: UUID, from: ReservationStatus, to: ReservationStatus): Int

// EventRepository에 추가 (native)
@Modifying
@Query(
    value = "UPDATE events SET reserved_count = reserved_count - 1, version = version + 1 " +
            "WHERE id = :eventId AND reserved_count > 0",
    nativeQuery = true,
)
fun releaseSeat(eventId: Long): Int
```

주의: `Reservation` 엔티티의 `status`가 현재 `val`이면 `var`로 변경하거나, 위처럼 UPDATE 쿼리만 쓰고 엔티티는 불변으로 둔다. **UPDATE 쿼리 방식을 채택한다 (엔티티 변경 최소화).**

### 6.7 신규: `StandardPendingReservationReaper` (Phase 5)

```kotlin
@Scheduled(fixedDelayString = "\${app.standard.payment.reaper-interval-ms:10000}")
// PENDING_PAYMENT && created_at < now - timeout-seconds 인 예약 목록 조회 후
// 각 건에 대해 sagaService.onFailed(...)와 동일한 보상 실행 (reason = SAGA_TIMEOUT)
```

- `@Profile("!consumer")` + standard 조건부 + `app.standard.payment.enabled=true`일 때만 동작.
- 보상 시 lockStrategy를 알아야 Redis 복원 판정이 가능한데 예약에 저장되어 있지 않다. **단순화 결정: reaper는 `app.standard.redis-inventory.enabled=true`이면 무조건 rollback(INCR)한다.** standard 모드 k6·데모는 REDIS/OPTIMISTIC 전략만 사용하며 skip 대상은 NONE뿐이므로 실용상 문제없고, 이 단순화는 코드 주석으로 명시한다.

### 6.8 설정 추가 (`reservation/src/main/resources/application.yml`)

```yaml
app:
  kafka:
    topic:
      reservation-confirmed: reservation.confirmed
      reservation-pending: reservation.pending      # 신규
      payment-result: payment.result                # 신규
  standard:
    payment:
      enabled: ${PAYMENT_ENABLED:false}             # 신규
      timeout-seconds: 60                           # 신규 (Phase 5)
      reaper-interval-ms: 10000                     # 신규 (Phase 5)
```

`AppModeProperties`에 `standard.payment` 프로퍼티 바인딩 추가. consumer 역직렬화 설정은 4.3 참조 (`trusted.packages: com.booking.contracts`, `default.type` 제거).

`KafkaConfig`에 `reservationPendingTopic` NewTopic bean 추가 (`!aws`, 파티션 3).

### 6.9 API 영향

- 엔드포인트 추가/변경 없음. `ReservationResponse.status`가 `PENDING_PAYMENT`/`CANCELLED`를 가질 수 있게 되는 것뿐 (enum 직렬화라 코드 변경 불필요).
- `EventResponse`에 `price` 필드 추가 (선택이지만 권장).

---

## 7. payment 모듈 신규 구현 상세

### 7.1 Flyway `V1__init.sql` (payment_db)

```sql
CREATE TABLE payments (
    id              UUID PRIMARY KEY,
    reservation_id  UUID NOT NULL,
    event_id        BIGINT NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    amount          BIGINT NOT NULL,
    lock_strategy   VARCHAR(32) NOT NULL,
    status          VARCHAR(32) NOT NULL,          -- PENDING | APPROVED | FAILED
    failure_reason  VARCHAR(32),                   -- DECLINED | TIMEOUT | ERROR
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_payments_reservation UNIQUE (reservation_id)
);

CREATE TABLE payment_outbox (
    id              UUID PRIMARY KEY,
    payment_id      UUID NOT NULL,
    reservation_id  UUID NOT NULL,
    event_id        BIGINT NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    amount          BIGINT NOT NULL,
    lock_strategy   VARCHAR(32) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    failure_reason  VARCHAR(32),
    occurred_at     TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_outbox_unpublished ON payment_outbox (created_at)
    WHERE published_at IS NULL;
```

### 7.2 MockPaymentGateway (실패 주입 장치 — 이 프로젝트의 핵심 셀링 포인트)

```kotlin
// gateway/PaymentGateway.kt
interface PaymentGateway {
    fun approve(request: PaymentRequest): PaymentGatewayResult
}

data class PaymentRequest(val reservationId: UUID, val userId: String, val amount: Long)

sealed interface PaymentGatewayResult {
    data object Approved : PaymentGatewayResult
    data class Declined(val reason: String) : PaymentGatewayResult   // reason = "DECLINED"
    data object TimedOut : PaymentGatewayResult                       // reason = "TIMEOUT"
}
```

`MockPaymentGateway` 판정 순서 (위에서부터 먼저 매칭되면 종료):

1. **결정적 트리거 (테스트/데모용)** — `userId` prefix:
   - `fail-`로 시작 → 즉시 `Declined("DECLINED")`
   - `timeout-`로 시작 → `timeout-ms`만큼 sleep 후 `TimedOut`
2. **확률 주입**: `Random.nextDouble() < timeout-rate` → sleep 후 `TimedOut`; 아니면 `< failure-rate` → `Declined`
3. **지연 시뮬레이션**: `delay-min-ms ~ delay-max-ms` 균등 랜덤 sleep 후 `Approved`

설정 (`payment.gateway.*`):

```yaml
payment:
  gateway:
    failure-rate: ${PG_FAILURE_RATE:0.0}    # 0.0 ~ 1.0
    timeout-rate: ${PG_TIMEOUT_RATE:0.0}
    delay-min-ms: ${PG_DELAY_MIN_MS:50}
    delay-max-ms: ${PG_DELAY_MAX_MS:200}
    timeout-ms: ${PG_TIMEOUT_MS:3000}       # TimedOut 시뮬레이션 시 sleep 시간
```

### 7.3 `PaymentProcessingService`

```
@Transactional
fun process(event: ReservationPendingEvent) {
    1. Payment 엔티티 INSERT (status=PENDING)
       → DataIntegrityViolationException(uk_payments_reservation) catch 시 로그 후 return  ← 멱등
    2. gateway.approve(...) 호출
    3. 결과에 따라 payment.status = APPROVED | FAILED(+failure_reason), updated_at 갱신
    4. payment_outbox INSERT (결과 스냅샷)
}
```

주의: gateway 호출은 DB 트랜잭션 **밖**에서 수행한다 (TX1 PENDING 커밋 → PG → TX2 결과+Outbox).
PG sleep/timeout 동안 커넥션을 붙잡지 않는다. TX1~TX2 사이 크래시 시 PENDING orphan이 남을 수 있어
운영이라면 PENDING 재처리가 필요하다. `@KafkaListener(concurrency = "3")`으로 처리량을 확보한다.

### 7.4 `ReservationPendingConsumer` / `PaymentOutboxPublisher`

- Consumer: `@KafkaListener(topics = ["reservation.pending"], groupId = "payment", concurrency = "3")` → `PaymentProcessingService.process()`.
- OutboxPublisher: reservation 모듈의 `StandardOutboxPublisher`와 동일 패턴. 2초 폴링, 미발행분을 `PaymentResultEvent`로 매핑해 `payment.result` 토픽 발행 (key = reservationId), `published_at` 갱신.

### 7.5 `application.yml` (payment)

```yaml
spring:
  application:
    name: payment
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:payment_db}
    username: ${DB_USER:lab}
    password: ${DB_PASSWORD:lab}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
  flyway:
    enabled: true
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      group-id: payment
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.booking.contracts

app:
  kafka:
    topic:
      reservation-pending: reservation.pending
      payment-result: payment.result

payment:
  gateway:
    failure-rate: ${PG_FAILURE_RATE:0.0}
    timeout-rate: ${PG_TIMEOUT_RATE:0.0}
    delay-min-ms: ${PG_DELAY_MIN_MS:50}
    delay-max-ms: ${PG_DELAY_MAX_MS:200}
    timeout-ms: ${PG_TIMEOUT_MS:3000}

management:
  endpoints:
    web:
      exposure:
        include: health,info

server:
  port: ${SERVER_PORT:8081}
```

- Redis 의존성 없음 (`spring-boot-starter-data-redis` 제외).
- `@EnableScheduling` 필요 (outbox 폴러).

---

## 8. 빌드 / 멀티 모듈 구성

### 8.1 `settings.gradle.kts`

```kotlin
rootProject.name = "booking-system"

include("contracts")
include("reservation")
include("payment")
```

### 8.2 루트 `build.gradle.kts`

```kotlin
plugins {
    id("org.springframework.boot") version "3.4.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.spring") version "2.1.0" apply false
    kotlin("plugin.jpa") version "2.1.0" apply false
}

subprojects {
    group = "com.booking"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}
```

### 8.3 `contracts/build.gradle.kts`

```kotlin
plugins {
    kotlin("jvm")
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
```

Spring/Jackson 의존성 없음. 순수 data class만. (JsonSerializer는 리플렉션 기반이라 어노테이션 불필요.)

### 8.4 `reservation/build.gradle.kts`

기존 루트 `build.gradle.kts` 내용을 옮기되:

- `plugins` 블록에서 버전 명시 제거 (루트에서 관리): `id("org.springframework.boot")`, `kotlin("jvm")` 등 이름만.
- `dependencies`에 `implementation(project(":contracts"))` 추가.
- `tasks.bootJar { archiveFileName = "app.jar" }` 추가 (Dockerfile 단순화용).

### 8.5 `payment/build.gradle.kts`

reservation과 동일 골격에서 의존성만 축소:

```kotlin
dependencies {
    implementation(project(":contracts"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar { archiveFileName.set("app.jar") }
```

### 8.6 Dockerfile (루트, ARG 방식으로 교체)

```dockerfile
FROM eclipse-temurin:17-jdk-alpine AS build
ARG MODULE=reservation
WORKDIR /app
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY contracts contracts
COPY reservation reservation
COPY payment payment
RUN chmod +x gradlew && ./gradlew :${MODULE}:bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre-alpine
ARG MODULE=reservation
WORKDIR /app
COPY --from=build /app/${MODULE}/build/libs/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 9. Docker Compose 변경

### 9.1 payment_db 초기화 스크립트

`scripts/db/init-payment-db.sh` (postgres 컨테이너의 `/docker-entrypoint-initdb.d/`에 마운트):

```bash
#!/bin/bash
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE DATABASE payment_db OWNER $POSTGRES_USER;
EOSQL
```

주의: init 스크립트는 **볼륨이 새로 생성될 때만** 실행된다. 기존 볼륨이 있으면 `docker compose down -v` 후 재기동 필요 — README 트러블슈팅에 명시.

### 9.2 `docker-compose.yml` 변경 사항

1. `postgres` 서비스에 볼륨 마운트 추가:

```yaml
    volumes:
      - ./scripts/db/init-payment-db.sh:/docker-entrypoint-initdb.d/init-payment-db.sh:ro
```

2. `x-app-env`에 `PAYMENT_ENABLED: "true"` 추가 (모든 reservation 앱에 saga 활성).

3. 기존 `app`, `app-1..3`의 `build:`를 명시적 ARG로 교체:

```yaml
    build:
      context: .
      args:
        MODULE: reservation
```

4. `payment` 서비스 신규 (single·scale3 양쪽에서 필요하므로 두 프로필 모두 지정):

```yaml
  payment:
    build:
      context: .
      args:
        MODULE: payment
    environment:
      DB_HOST: postgres
      DB_PORT: 5432
      DB_NAME: payment_db
      DB_USER: lab
      DB_PASSWORD: lab
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      SERVER_PORT: 8081
      PG_FAILURE_RATE: "0.0"
      PG_TIMEOUT_RATE: "0.0"
    ports:
      - "8081:8081"
    depends_on:
      postgres:
        condition: service_healthy
      kafka:
        condition: service_started
    profiles:
      - single
      - scale3
```

5. `reservation-consumer`는 무변경 (역직렬화 설정만 4.3에 따라 갱신).

### 9.3 `docker-compose.infra.yml`

postgres에 동일한 init 스크립트 마운트 추가.

### 9.4 reset 스크립트 갱신 (`reset-standard.sh` / `.ps1`)

기존 내용에 추가:

```bash
docker compose exec postgres psql -U lab -d payment_db -c \
  "TRUNCATE payments, payment_outbox;"
```

(payment 미기동 시 테이블이 없을 수 있으므로 `|| true`로 무시 처리.)

---

## 10. 테스트 계획

### 10.1 기존 테스트 보전

- `LockStrategyIntegrationTest`, `LockStrategyResolverTest`는 reservation 모듈로 이동 후 **무수정 통과**해야 한다 (flag 기본 off이므로).
- 실행: `./gradlew :reservation:test`

### 10.2 신규 단위 테스트

| 테스트 | 검증 내용 |
|--------|-----------|
| `MockPaymentGatewayTest` (payment) | `fail-` prefix → Declined, `timeout-` prefix → TimedOut, rate=0이면 항상 Approved, failure-rate=1.0이면 항상 Declined |
| `StandardPaymentSagaServiceTest` (reservation) | APPROVED: PENDING→CONFIRMED 전이 + outbox 적재. FAILED: CANCELLED 전이 + reserved_count 감소 + Redis rollback 호출. 이미 CONFIRMED/CANCELLED인 예약에 대한 이벤트 → 아무 것도 안 함 |
| `PaymentProcessingServiceTest` (payment) | 정상 처리 → APPROVED + outbox. 중복 reservation_id → skip |

Saga 서비스 테스트는 기존 통합 테스트 인프라(Testcontainers Postgres·Redis)를 재사용하고, Kafka 발행은 outbox 테이블 검증으로 대체한다 (실제 Kafka 불필요).

### 10.3 E2E 검증 (docker compose, Phase 4 DoD)

```bash
docker compose --profile single up -d --build
./scripts/reset-standard.sh

# 1. happy path
curl -X POST http://localhost:8080/api/v1/reservations \
  -H "Content-Type: application/json" -H "X-Idempotency-Key: saga-1" \
  -d '{"eventId": 1, "userId": "user-saga-1"}'
# → 201, status=PENDING_PAYMENT
# 수 초 후 (outbox 2s 폴링 × 2회 + PG 지연):
curl http://localhost:8080/api/v1/reservations/{id}   # → status=CONFIRMED

# 2. 결제 거절 → 보상
curl http://localhost:8080/api/v1/events/1             # reservedCount 기록
curl -X POST http://localhost:8080/api/v1/reservations \
  -H "Content-Type: application/json" \
  -d '{"eventId": 1, "userId": "fail-user-1"}'
# → 201 PENDING_PAYMENT → 수 초 후 status=CANCELLED
curl http://localhost:8080/api/v1/events/1             # reservedCount 원복 확인
docker compose exec redis redis-cli GET event:1:remaining   # 재고 원복 확인

# 3. 타임아웃 (Phase 5 이후)
# userId=timeout-user-1 → FAILED(TIMEOUT) → CANCELLED + 좌석 반환
```

### 10.4 k6 시나리오 (Phase 5)

- `scripts/k6/standard/payment-saga.js`: 동시 200 예약 → 201은 모두 PENDING_PAYMENT → 폴링으로 전원 CONFIRMED 도달 + `reservedCount == min(200, capacity)` 검증.
- `scripts/k6/standard/payment-failure.js`: `PG_FAILURE_RATE=0.3`으로 기동한 뒤 동시 200 예약 → 최종적으로 `reservedCount == CONFIRMED 수` && Redis remaining과 DB 정합 → **"결제 실패 30%에서도 재고 정합성 유지"** 를 수치로 증명. README 벤치마크 표에 추가.

---

## 11. 구현 Phase (이 순서를 강제한다)

### Phase 0 — 멀티 모듈 재구성 (동작 불변)

1. `reservation/` 디렉토리 생성, 기존 `src/` 전체를 `reservation/src/`로 이동 (git mv).
2. 8.1~8.4의 빌드 파일 작성 (contracts, payment는 아직 빈 껍데기 또는 미포함 가능 — 미포함 권장: settings에는 reservation만 먼저 include해도 됨).
3. Dockerfile을 8.6으로 교체, docker-compose의 build를 9.2-3 형식으로 변경.

**DoD**: `./gradlew :reservation:test` 전체 통과. `docker compose --profile single up -d --build` 후 기존 README의 curl 예제(예약 생성 201) 동작. `./scripts/reset-standard.sh` 동작.

### Phase 1 — contracts 모듈 + 이벤트 이동

1. `contracts` 모듈 생성, 4.2의 3개 이벤트 클래스 작성.
2. `com.booking.reservation.kafka.ReservationConfirmedEvent` 삭제, 참조를 `com.booking.contracts.ReservationConfirmedEvent`로 교체.
3. 4.3의 직렬화 설정 변경 (trusted.packages, default.type 제거) — `application.yml` + `application-consumer.yml` 영향 확인.

**DoD**: 테스트 통과 + docker compose 기동 후 예약 생성 시 `reservation-consumer` 로그에 ReservationConfirmed 출력 (역직렬화 회귀 검증 — 이 확인을 생략하지 말 것).

### Phase 2 — reservation Saga 지원 (flag off 기본)

1. V3 migration (6.1), 엔티티 변경 (6.2), `AppModeProperties`에 payment 설정 추가.
2. 락 경로 시그니처 변경 (6.3) — 기본 파라미터 필수.
3. Flow/Outbox 변경 (6.4, 6.5), `PaymentResultConsumer` + `StandardPaymentSagaService` (6.6), repository 쿼리 추가.
4. `StandardPaymentSagaServiceTest` 작성 (10.2).

**DoD**: flag off로 기존 테스트 전부 통과. flag on(`PAYMENT_ENABLED=true`) + infra compose로 로컬 기동 시 예약 생성 응답이 `PENDING_PAYMENT`이고 `reservation_outbox`에 `event_type='PENDING'` 행이 쌓이며 폴러가 `reservation.pending` 토픽으로 발행 (kafka-console-consumer로 확인).

### Phase 3 — payment 모듈 신규

1. `payment/` 모듈 생성 (8.5), 스키마 (7.1), 엔티티/리포지토리, MockPaymentGateway (7.2), ProcessingService (7.3), Consumer/OutboxPublisher (7.4), application.yml (7.5), KafkaConfig (payment.result NewTopic).
2. `settings.gradle.kts`에 `include("payment")` 추가.
3. 단위 테스트 (10.2).

**DoD**: `./gradlew :payment:test` 통과. infra compose + reservation·payment 양쪽 로컬 기동으로 happy path 수동 확인 (PENDING_PAYMENT → CONFIRMED).

### Phase 4 — Docker Compose 통합

1. 9장 전체 반영 (init 스크립트, `payment` Compose 서비스, PAYMENT_ENABLED, reset 스크립트).
2. **기존 볼륨 제거 후 재기동** (`docker compose down -v`).

**DoD**: 10.3의 E2E 시나리오 1·2 전부 통과. scale3 프로필에서도 happy path 확인.

### Phase 5 — 타임아웃 reaper + k6 + 문서

1. Reaper (6.7) + 타임아웃 E2E (10.3의 3).
2. k6 스크립트 2종 (10.4) 작성·실행, 결과 수치 기록.
3. `README.md`·`AGENT.md` 갱신: 아키텍처 다이어그램(2장), Saga 흐름(5장), 서비스 분리 근거(2.1), 알려진 한계(5.4), k6 결과 표 추가.

**DoD**: k6 payment-failure 시나리오에서 재고 정합성 검증 통과. 문서 갱신 완료.

---

## 12. 저위험 구현을 위한 함정 목록 (구현 중 반드시 참고)

1. **Kafka 역직렬화**: `spring.json.value.default.type`을 남겨두면 모든 토픽이 한 타입으로 강제되어 `payment.result` 소비가 깨진다. 반드시 제거하고 타입 헤더 + trusted packages 방식으로.
2. **`@Modifying` 쿼리와 영속성 컨텍스트**: 가드 UPDATE 후 같은 트랜잭션에서 해당 엔티티를 다시 읽으면 stale일 수 있다. saga 서비스에서는 UPDATE 결과 행 수만 사용하고 엔티티 재조회를 하지 않는다.
3. **outbox 폴러 지연**: E2E에서 상태 전이는 outbox 2초 폴링 × 2단계(pending 발행 + result 발행)로 최대 4~5초+PG 지연이 걸린다. 검증 시 sleep을 충분히 (8초 권장).
4. **postgres init 스크립트는 기존 볼륨에서 실행 안 됨**: Phase 4에서 `payment_db does not exist` 에러가 나면 `docker compose down -v`.
5. **Windows 줄바꿈**: `init-payment-db.sh`는 LF로 저장해야 한다 (CRLF면 컨테이너에서 실행 실패). `.gitattributes` 확인 또는 파일 생성 시 주의.
6. **basic 모드 회귀**: `ReservationLockExecutor` 시그니처 변경 후 `APP_MODE=basic docker compose up`으로 basic 예약 생성이 여전히 `CONFIRMED`로 동작하는지 Phase 2에서 확인.
7. **멱등 replay와 PENDING**: 멱등키 재전송 시 저장 시점 상태가 아니라 **현재 예약 상태**를 반환해야 한다. 기존 코드가 `reservationRepository.findById`로 현재 상태를 읽으므로 그대로 두면 맞다 — 수정하지 말 것.
8. **`java-library`/버전 정합**: 루트에서 `apply false`로 버전을 고정하므로 하위 모듈 plugins 블록에 버전을 다시 쓰면 빌드 에러. 이름만 쓴다.
9. **Flyway 분리**: payment 모듈의 migration은 `payment/src/main/resources/db/migration`에 V1부터 새로 시작한다. reservation의 V1·V2와 무관하다.
10. **aws 프로파일**: `application-aws.yml`은 reservation 모듈로 함께 이동만 하고 내용 수정은 이번 범위에서 제외 (MSK 토픽 pre-provision 등 AWS 반영은 별도 작업).
11. **모듈명 `-service` 금지**: Gradle task(`:payment`), 디렉터리(`payment/`), Docker `MODULE` ARG, Compose 서비스명 모두 `payment`를 쓴다. Kafka consumer `groupId`도 `payment` / `reservation`으로 통일한다.

---

## 13. 완료 후 포트폴리오 서사 체크리스트

README에 다음이 수치·다이어그램과 함께 드러나야 한다.

- [ ] 왜 예약/결제로 잘랐는가 (트랜잭션 경계·실패 모델 차이)
- [ ] 왜 모노레포 멀티 모듈인가 (독립성은 배포 단위에서 확보, 공유는 contracts뿐)
- [ ] Saga choreography 흐름도 + 보상 트랜잭션 경로
- [ ] 실패 주입 결과: "결제 실패 N%에서도 재고 정합성 유지" k6 수치
- [x] 알려진 한계와 실무 확장 방향 (늦은 승인 처리, PG 트랜잭션 분리 — TX 분리는 반영, 늦은 승인 환불은 미구현)
