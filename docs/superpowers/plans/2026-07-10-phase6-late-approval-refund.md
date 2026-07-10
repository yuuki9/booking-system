# Phase 6: 늦은 APPROVED 환불 보상 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** reaper가 먼저 `CANCELLED`로 만든 뒤 늦은 `payment.result(APPROVED)`가 도착해도, reservation이 payment에 환불 요청을 보내 “결제만 성공·좌석 없음” 불일치를 닫는다.

**Architecture:** choreography 유지. reservation Outbox에 `REFUND_REQUESTED` 타입을 추가해 `payment.refund` 토픽으로 발행하고, payment는 Mock PG 환불 후 `payments`를 `REFUNDED`로 전이·멱등 처리한다. 동기 HTTP는 추가하지 않는다.

**Tech Stack:** 기존과 동일 — Kotlin, Spring Boot 3.4, JPA, Flyway, Kafka Outbox, Testcontainers, k6

**선행 상태 (Phase 0~5 + TX 분리 완료):**
- Saga happy/fail path, reaper, Mock PG TX 밖 호출까지 반영됨
- 남은 구멍: `StandardPaymentSagaService.onApproved`가 가드 0행일 때 WARN만 남김 (환불 없음)

**비목표:**
- 실 PG / 결제 UI / orchestrator 전환
- PENDING orphan 재처리 워커 (별도 Phase 후보 — 본 계획 Task 부록만)
- basic 모드 변경

---

## 파일 구조 (생성·수정)

| 파일 | 역할 |
|------|------|
| `contracts/.../PaymentRefundRequestedEvent.kt` | reservation → payment 환불 요청 계약 |
| `contracts/.../PaymentResultEvent.kt` (기존) | 변경 최소 — 환불 결과 토픽이 필요하면 선택적으로 `PaymentRefundResultEvent` 추가 (아래 결정) |
| `reservation/.../OutboxEventType.kt` | `REFUND_REQUESTED` 값 추가 |
| `reservation/.../StandardOutboxService.kt` | `enqueueRefundRequested(...)` |
| `reservation/.../StandardOutboxPublisher.kt` | `REFUND_REQUESTED` → `payment.refund` 발행 |
| `reservation/.../StandardPaymentSagaService.kt` | 늦은 APPROVED + 현재 status=`CANCELLED`면 refund outbox |
| `reservation/.../KafkaConfig.kt` | `payment.refund` NewTopic (파티션 3) |
| `reservation/.../application.yml` | `app.kafka.topic.payment-refund` |
| `payment/.../db/migration/V2__refund.sql` | `REFUNDED` 수용 (VARCHAR면 DDL 최소 / 인덱스 선택) |
| `payment/.../PaymentStatus.kt` | `REFUNDED` |
| `payment/.../gateway/PaymentGateway.kt` | `refund(request)` (+ Mock 구현) |
| `payment/.../PaymentRefundService.kt` (신규) | refund 이벤트 처리 (TX 분리 패턴 재사용) |
| `payment/.../kafka/PaymentRefundConsumer.kt` (신규) | `payment.refund` 소비 |
| 테스트 2~3개 | saga late-approve → refund outbox / payment refund 멱등 |
| `README.md`, `AGENT.md`, 설계서 §5.4·Phase 6 | 한계 해소 반영 |

### 설계 결정 (구현 전 고정)

1. **환불 트리거 조건 (reservation)**  
   `onApproved`에서 `transitionStatus(PENDING→CONFIRMED)==0`일 때:  
   - 현재 status == `CANCELLED` → **환불 요청 Outbox** (reaper/실패 보상 후 늦은 승인)  
   - 현재 status == `CONFIRMED` → 중복 APPROVED → no-op (기존과 동일)  
   - 그 외 → WARN only  

2. **환불 결과의 방향**  
   - **MVP (추천):** payment만 `REFUNDED`로 갱신 + 로그. reservation은 이미 `CANCELLED`이므로 추가 전이 없음.  
   - **확장 (선택):** `payment.refund.result`를 reservation이 소비해 관측용 플래그/메트릭만 갱신 — Phase 6 DoD에는 넣지 않음.

3. **멱등**  
   - reservation: 동일 reservationId에 refund outbox를 두 번 넣지 않음 (`existsByReservationIdAndEventType` 또는 UNIQUE 부분 인덱스).  
   - payment: `status==REFUNDED` 또는 `status!=APPROVED`면 skip. APPROVED→REFUNDED만 허용.

4. **Mock PG refund**  
   - `refund()`는 짧은 sleep 후 성공 (실패 주입은 prefix `refund-fail-` 정도로 선택 구현).  
   - approve와 같이 **DB TX 밖**에서 호출.

---

## 시퀀스 (목표 동작)

```
PENDING_PAYMENT ──reaper──▶ CANCELLED (+ 좌석 반환)
        │
        └──늦은 payment.result(APPROVED)
              → cascade 실패 (0행)
              → Outbox REFUND_REQUESTED → payment.refund
              → payment: Mock PG refund → REFUNDED
```

---

### Task 1: contracts — 환불 요청 이벤트

**Files:**
- Create: `contracts/src/main/kotlin/com/booking/contracts/PaymentRefundRequestedEvent.kt`
- Modify: (필요 시) 없음 — `ReservationConfirmedEvent` 패턴 복사

- [ ] **Step 1: 이벤트 DTO 추가**

```kotlin
data class PaymentRefundRequestedEvent(
    val paymentId: UUID,       // PaymentResultEvent.paymentId 재사용
    val reservationId: UUID,
    val eventId: Long,
    val userId: String,
    val amount: Long,
    val reason: String,        // e.g. "LATE_APPROVAL_AFTER_CANCEL"
    val occurredAt: Instant,
)
```

- [ ] **Step 2: 커밋**

```bash
git add contracts/
git commit -m "feat(contracts): 결제 환불 요청 이벤트 계약 추가"
```

---

### Task 2: reservation Outbox — REFUND_REQUESTED

**Files:**
- Modify: `reservation/.../domain/OutboxEventType.kt` (또는 String 상수 위치 확인 후)
- Modify: `reservation/.../StandardOutboxService.kt`
- Modify: `reservation/.../StandardOutboxPublisher.kt`
- Modify: `reservation/.../config/KafkaConfig.kt`
- Modify: `reservation/.../resources/application.yml`
- Test: 단위/통합 — publisher 라우팅은 saga 테스트에서 간접 검증 가능

- [ ] **Step 1: `OutboxEventType.REFUND_REQUESTED` 추가**
- [ ] **Step 2: `enqueueRefundRequested(paymentId, reservation, amount, reason)` 구현**  
  - `eventType=REFUND_REQUESTED`, `amount` 필수, paymentId는 outbox에 컬럼이 없으면 payload용으로 `userId`/별도 컬럼 검토  
  - **스키마:** 현재 `reservation_outbox`에 `payment_id`가 없으면 V4 migration으로 `payment_id UUID NULL` 추가 (REFUND만 사용)
- [ ] **Step 3: Publisher에서 `payment.refund` 토픽으로 `PaymentRefundRequestedEvent` produce**
- [ ] **Step 4: `app.kafka.topic.payment-refund: payment.refund` + NewTopic bean**
- [ ] **Step 5: `./gradlew :reservation:compileKotlin` 통과 확인**
- [ ] **Step 6: 커밋**

```bash
git commit -m "feat(reservation): 환불 요청 Outbox와 payment.refund 토픽 추가"
```

---

### Task 3: StandardPaymentSagaService — 늦은 APPROVED → 환불

**Files:**
- Modify: `reservation/.../StandardPaymentSagaService.kt`
- Modify: `reservation/.../StandardPaymentSagaServiceTest.kt`
- Modify: (선택) `ReservationRepository` — status 단건 조회는 `findById`로 충분

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
@Test
fun `onApproved after CANCELLED enqueues refund requested outbox`() {
    val reservation = seedPendingReservation()
    // reaper 경로와 동일하게 CANCELLED + seat released
    jdbcTemplate.update("UPDATE events SET reserved_count = 1 WHERE id = ?", reservation.eventId)
    sagaService.compensateTimeout(reservation.id, reservation.eventId)

    sagaService.onApproved(paymentResult(reservation, PaymentResultStatus.APPROVED))

    assertEquals(ReservationStatus.CANCELLED, reservationRepository.findById(reservation.id).orElseThrow().status)
    val refund = outboxRepository.findAll().single { it.eventType == OutboxEventType.REFUND_REQUESTED }
    assertEquals(reservation.id, refund.reservationId)
}

@Test
fun `onApproved duplicate CONFIRMED does not enqueue refund`() {
    val reservation = seedPendingReservation()
    sagaService.onApproved(paymentResult(reservation, PaymentResultStatus.APPROVED))
    sagaService.onApproved(paymentResult(reservation, PaymentResultStatus.APPROVED))
    assertEquals(0, outboxRepository.findAll().count { it.eventType == OutboxEventType.REFUND_REQUESTED })
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

```bash
./gradlew :reservation:test --tests "com.booking.reservation.service.standard.StandardPaymentSagaServiceTest"
```

- [ ] **Step 3: `onApproved` 분기 구현**

```kotlin
if (updated == 0) {
    val current = reservationRepository.findById(event.reservationId).orElse(null)
    if (current?.status == ReservationStatus.CANCELLED) {
        outboxService.enqueueRefundRequested(...)
        log.warn("Late APPROVED after CANCELLED → refund requested reservationId={}", event.reservationId)
    } else {
        log.warn("Late or duplicate approval ignored reservationId={}", event.reservationId)
    }
    return
}
```

- [ ] **Step 4: 테스트 통과 + 기존 saga 테스트 회귀 통과**
- [ ] **Step 5: 커밋**

```bash
git commit -m "feat(reservation): 늦은 APPROVED에 환불 요청 Outbox를 발행한다"
```

---

### Task 4: payment — Mock refund + consumer

**Files:**
- Modify: `payment/.../PaymentStatus.kt` → `REFUNDED`
- Modify: `payment/.../gateway/PaymentGateway.kt`, `MockPaymentGateway.kt`
- Create: `payment/.../service/PaymentRefundService.kt`
- Create: `payment/.../kafka/PaymentRefundConsumer.kt`
- Modify: `payment/.../application.yml` (topic)
- Modify: `payment/.../config/KafkaConfig.kt` (필요 시 — producer만이면 NewTopic은 reservation이 만들어도 됨)
- Create: `payment/.../PaymentRefundServiceTest.kt`
- Create: `payment/src/main/resources/db/migration/V2__*.sql` (CHECK 없으면 스킵 가능 — status는 VARCHAR)

- [ ] **Step 1: 실패하는 테스트 — APPROVED 결제에 refund 이벤트 → REFUNDED**
- [ ] **Step 2: 실패하는 테스트 — 중복 refund → 한 번만 / REFUNDED no-op**
- [ ] **Step 3: `PaymentGateway.refund` + Mock 구현 (TX 밖 호출)**
- [ ] **Step 4: `PaymentRefundService`  
  - TX1: status가 APPROVED인지 확인 (아니면 skip) — 또는 바로 gateway 후 TX2  
  - 권장: **읽기(승인 여부) → gateway refund (TX 밖) → TX: APPROVED→REFUNDED**  
  - UNIQUE/가드로 이중 환불 방지
- [ ] **Step 5: `PaymentRefundConsumer` `@KafkaListener(topics=payment.refund, groupId=payment-refund)`**  
  - 기존 `groupId=payment`와 분리해 pending 소비와 오프셋 간섭 방지
- [ ] **Step 6: `./gradlew :payment:test` 통과**
- [ ] **Step 7: 커밋**

```bash
git commit -m "feat(payment): payment.refund 소비와 Mock PG 환불·REFUNDED 전이 추가"
```

---

### Task 5: E2E 시나리오 + 문서

**Files:**
- Modify: `docs/superpowers/specs/2026-07-08-payment-saga-msa-design.md` (§5.4 한계 → 해소, Phase 6 DoD)
- Modify: `README.md` (Saga sequence에 refund alt, 알려진 한계 축소)
- Modify: `AGENT.md` (토픽 표에 `payment.refund`)
- Optional: `scripts/k6/standard/payment-late-approve.md` 또는 curl 시나리오를 `docs/test-scenarios.md`에 추가

- [ ] **Step 1: 수동 E2E 체크리스트 (Compose)**

```bash
docker compose --profile single up -d --build
./scripts/reset-standard.sh

# 1) 예약 생성 → PENDING_PAYMENT
# 2) payment를 잠시 중지하거나 PG를 극단 지연시켜 reaper(60s)가 CANCELLED로 만든 뒤
#    (랩용) timeout-seconds를 테스트 프로파일에서 5초로 낮춘 compose override 권장
# 3) 이후 APPROVED가 도착하면 payment status=REFUNDED, reservation=CANCELLED 유지
```

재현이 어렵면 **통합 테스트만으로 DoD** 하고, README에 “단위·통합으로 검증, 수동은 timeout-seconds 단축 시” 명시.

- [ ] **Step 2: README sequenceDiagram에 refund alt 추가**
- [ ] **Step 3: 설계서 §5.4 “알려진 한계”를 “Phase 6에서 환불 보상으로 해소”로 갱신**
- [ ] **Step 4: 포트폴리오 체크리스트 `[x] 늦은 승인 처리`**
- [ ] **Step 5: 커밋**

```bash
git commit -m "docs(docs): Phase 6 늦은 승인 환불 보상을 README·설계서에 반영"
```

---

### Task 6: 회귀 검증

- [ ] **Step 1: 전체 테스트**

```bash
./gradlew :contracts:test :reservation:test :payment:test
```

- [ ] **Step 2: (선택) payment-saga k6 smoke — 기존 happy path 깨지지 않음**
- [ ] **Step 3: 푸시**

```bash
git push origin main
```

---

## DoD (Phase 6 완료 조건)

1. 늦은 APPROVED + 이미 `CANCELLED` → `payment.refund` 이벤트 발행 (테스트로 증명)
2. payment가 환불 후 `REFUNDED` (중복 무해)
3. happy path / 실패 보상 / reaper 기존 테스트 회귀 통과
4. README·설계서에서 “결제만 성공·좌석 없음”을 **미해결 한계가 아니라 해결된 시나리오**로 서술
5. Mock PG refund도 **DB TX 밖** 호출

---

## 함정

1. **환불을 CONFIRMED 중복에도 보내면 안 됨** — status 분기가 필수  
2. **Outbox에 paymentId 없으면** migration으로 컬럼 추가; 이벤트만으로 payment가 `reservation_id`로 lookup해도 됨 (paymentId optional)  
3. **consumer groupId**를 pending과 공유하면 파티션 할당이 꼬일 수 있음 → `payment-refund` 권장  
4. **reaper timeout 단축 E2E**는 CI 플래키하기 쉬움 → 통합 테스트 우선  
5. **basic 모드·PAYMENT_ENABLED=false** 경로는 환불 outbox가 실행될 일 없음 (이벤트 없음)

---

## 부록 (Phase 6 밖 / 다음 후보)

| 후보 | 내용 |
|------|------|
| Phase 6b | PENDING orphan 재처리 워커 (TX1~TX2 사이 크래시) |
| Phase 6c | `payment.refund.result` 관측 이벤트 + 메트릭 |
| Phase 6d | k6 payment-failure **실측 수치** README 확정 |

---

## 구현 진입점

계획 승인 후:

1. **subagent-driven-development** — Task 1부터 체크박스 단위로 진행  
2. 또는 **executing-plans** — 같은 세션에서 Task 단위 커밋

관련 설계 원문: `docs/superpowers/specs/2026-07-08-payment-saga-msa-design.md` §5.4
