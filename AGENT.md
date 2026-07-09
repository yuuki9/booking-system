# AGENT.md — booking-system

> AI 에이전트 및 기여자가 이 저장소를 빠르게 이해하기 위한 프로젝트 가이드

## 프로젝트 개요

**선착순 이벤트 예약 시스템**입니다. 콘서트 티켓·한정 수량 이벤트처럼 **정해진 `capacity` 안에서 많은 사용자가 동시에 예약**하는 상황을 다룹니다.

코드는 **두 갈래**로 나뉩니다.

| | basic | standard |
|---|-----------|----------|
| **역할** | 락 Handler 기본 동작 | 실제 운영 |
| **목적** | 4종 Lock Handler → DB → Kafka (동작 검증·k6 실험) | 멱등·중복 방지·Outbox·결제 Saga |
| **구현** | `BasicReservationFlow` | `StandardReservationFlow` |

- **basic** — `NONE`, `OPTIMISTIC`, `PESSIMISTIC`, `REDIS` Handler 동작만 노출합니다. 멱등·Outbox·Redis 선차감·결제는 **의도적으로 제외**합니다.
- **standard** — 실제 서비스에 가까운 코드입니다. 재전송 대응, 중복 예약 차단, 재고 선차감, DB 커밋과 Kafka 발행 분리(Outbox)를 포함합니다.
- **결제 Saga** — `PAYMENT_ENABLED=true`(Compose 기본)일 때 예약·결제를 분리하고 Kafka choreography로 상태를 맞춥니다.

기술 스택: **Kotlin**, **Spring Boot 3.4**, **PostgreSQL**, **Redis**, **Kafka**, **Flyway**, **Docker Compose**, **k6**(부하 테스트)

---

## Gradle 멀티 모듈

| 모듈 | 포트(Compose) | DB | 역할 |
|------|---------------|-----|------|
| `contracts` | — | — | Kafka 이벤트 DTO만 공유 |
| `reservation` | 8080 | `booking_system` | 예약·Outbox·Saga 소비·reaper |
| `payment` | 8081 | `payment_db` | Mock PG·결제 Outbox |

서비스 간 **동기 HTTP 호출 없음**. Kafka 토픽: `reservation.pending`, `payment.result`, `reservation.confirmed`.

---

## 시스템 아키텍처

```
Client / k6
    │
    ▼
 Nginx (scale3 프로필)
    │
    ├── reservation (8080) ── booking_system DB, Redis
    │       reservation.pending ──▶ payment
    │       ◀── payment.result
    │
    └── payment (8081) ── payment_db
            │
            ▼
         Kafka ──▶ reservation-consumer (reservation.confirmed 로그 데모)
```

현재 구조 다이어그램: [`README.md`](README.md) (standard + Saga mermaid).  
구 scale-out 인프라 뷰(payment 미반영): [`docs/architecture.png`](docs/architecture.png)  
Saga 설계: [`docs/superpowers/specs/2026-07-08-payment-saga-msa-design.md`](docs/superpowers/specs/2026-07-08-payment-saga-msa-design.md)

---

## 실행 모드

| 모드 | 환경변수 | 성격 |
|------|----------|------|
| **standard** (기본) | `APP_MODE=standard` | **운영 코드** — 멱등·중복검사·Redis 선차감·Outbox |
| **basic** | `APP_MODE=basic` | **기본 Flow** — Lock Handler 동작 검증·k6 실험 |
| **aws** (Spring profile) | `SPRING_PROFILES_ACTIVE=aws` | **AWS 배포** — standard + RDS SSL·MSK pre-provisioned topic |

`APP_MODE`에 따라 `ReservationCreationFlow` 구현체가 `@ConditionalOnProperty`로 하나만 활성화됩니다.

**결제 플래그:** `PAYMENT_ENABLED` (기본 `false`, Compose `true`)

| | `false` | `true` |
|---|---------|--------|
| 예약 생성 status | `CONFIRMED` | `PENDING_PAYMENT` |
| Outbox | `CONFIRMED` → `reservation.confirmed` | `PENDING` → `reservation.pending` |

---

## 락 전략 (LockStrategy)

| 전략 | Handler | 역할 |
|------|---------|------|
| `NONE` | `NoneLockHandler` | 동시성 제어 없음 (오버부킹 실험용) |
| `OPTIMISTIC` | `OptimisticLockHandler` | `@Version` 기반 낙관적 락 |
| `PESSIMISTIC` | `PessimisticLockHandler` | DB `SELECT FOR UPDATE` |
| `REDIS` | `RedisLockHandler` | Redis 분산 락 |

락 전략 우선순위: **query param** > **헤더 `X-Lock-Strategy`** > **`app.lock-strategy` 기본값**

---

## 예약 생성 흐름

### standard 모드 (결제 off)

```
POST /api/v1/reservations
  → 멱등 키 확인 (X-Idempotency-Key)
  → 중복 사용자 검사
  → Redis 재고 선차감 (선택적)
  → ReservationLockExecutor → LockHandler
  → Outbox 적재 (CONFIRMED)
  → 멱등 키 저장
```

### standard + PAYMENT_ENABLED (Saga)

```
POST /api/v1/reservations
  → (위와 동일 전처리)
  → reserve(..., initialStatus=PENDING_PAYMENT)
  → Outbox PENDING → reservation.pending
  → payment: Mock PG → payment.result
  → APPROVED: CONFIRMED + reservation.confirmed Outbox
  → FAILED: CANCELLED + DB/Redis 좌석 반환 (보상)
  → reaper: payment.result 유실 시 timeout 후 동일 보상
```

Outbox는 `StandardOutboxPublisher`가 폴링하여 Kafka로 발행합니다.

### basic 모드

```
POST /api/v1/reservations
  → ReservationLockExecutor → LockHandler
  → Kafka 직접 publish
```

---

## 디렉토리 구조

```
booking-system/
├── contracts/          # Kafka 이벤트 DTO
├── reservation/        # 예약 서비스 (main 앱 MODULE=reservation)
├── payment/            # 결제 서비스 (MODULE=payment)
├── scripts/
│   ├── reset-*.sh / *.ps1
│   └── k6/             # benchmark/, standard/ (payment-saga, payment-failure)
├── docker-compose.yml
├── Dockerfile          # ARG MODULE
└── docs/
```

---

## 주요 API

| Method | Path | 설명 |
|--------|------|------|
| `POST` | `/api/v1/reservations` | 예약 생성 |
| `GET` | `/api/v1/reservations/{id}` | 예약 조회 (Saga 폴링) |
| `GET` | `/api/v1/events/{id}` | 이벤트(잔여 좌석) 조회 |

**standard 모드 헤더**

- `X-Idempotency-Key` — 동일 키 재전송 시 기존 예약 반환 (200 OK)
- `X-Lock-Strategy` — 락 전략 지정

---

## 로컬 실행

```bash
# standard + payment Saga (Compose 기본)
docker compose --profile single up -d --build
./scripts/reset-standard.sh

# basic — Lock Handler 동작 + k6 부하 비교
APP_MODE=basic docker compose --profile single up -d --build
./scripts/reset-basic.sh

# k6 결제 Saga
docker compose run --rm k6 run /scripts/standard/payment-saga.js
PG_FAILURE_RATE=0.3 docker compose up -d --no-deps --build payment
docker compose run --rm k6 run /scripts/standard/payment-failure.js

# 3대 스케일아웃
docker compose --profile scale3 up -d --build
```

테스트:

```bash
./gradlew test
```

---

## 데이터 모델 (요약)

**booking_system (reservation)**

| 테이블 | 용도 |
|--------|------|
| `events` | 이벤트, `capacity`, `reserved_count`, `price` |
| `reservations` | 예약 (`PENDING_PAYMENT` / `CONFIRMED` / `CANCELLED`) |
| `reservation_outbox` | Kafka Outbox (`PENDING` / `CONFIRMED`) |
| `idempotency_records` | 멱등 키 |

**payment_db (payment)**

| 테이블 | 용도 |
|--------|------|
| `payments` | 결제 시도 (reservation_id UNIQUE) |
| `payment_outbox` | `payment.result` Outbox |

---

## 에이전트 작업 시 참고

- **basic 모드**(`BasicReservationFlow`, `service/basic/`, `service/lock/`)는 결제 Saga 작업 시 **수정 금지**.
- **모드 변경**은 `app.mode` / `APP_MODE` 환경변수로 제어합니다.
- **standard 전용 기능**은 `reservation/.../service/standard/`에 모입니다 (`StandardPaymentSagaService`, `StandardPendingReservationReaper` 등).
- **부하 테스트**는 `scripts/k6/` — benchmark(basic), standard(Saga 포함).
- DB 스키마 변경은 각 모듈 Flyway migration으로만 반영합니다.
- 사용자 대상 실행 설명은 [`README.md`](README.md)를 참고하세요.
