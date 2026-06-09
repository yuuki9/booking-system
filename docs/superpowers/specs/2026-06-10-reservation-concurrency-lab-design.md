# Reservation Concurrency Test App — Design Spec

**Date:** 2026-06-10  
**Status:** Approved

## Goal

Spring Boot + Kotlin 기반 정원형 예약 **동시성 테스트 앱**으로 4가지 락 전략을 단일 앱에서 비교·검증한다.

## Decisions Summary

| 항목 | 결정 |
|------|------|
| 구조 | 단일 앱 + Strategy 패턴 |
| 도메인 | 정원형 (이벤트 1개, capacity N) |
| Kafka | 예약 확정 이벤트만 (`reservation.confirmed`) |
| 인프라 | Docker Compose (PG, Redis, Kafka, App 1~3, Nginx, k6) |
| 락 전환 | 환경변수 기본값 + 요청 파라미터/헤더 오버라이드 |

## Architecture

```
k6 → Nginx LB → App (x1~x3) → PostgreSQL
                            → Redis (REDIS 전략)
                            → Kafka → Consumer (로그/메트릭)
```

### Components

| Component | Responsibility |
|-----------|----------------|
| `ReservationController` | REST API, 전략 파라미터 수신 |
| `LockStrategyResolver` | env → header → query 우선순위로 전략 결정 |
| `ReservationService` | 전략별 예약 처리 위임 |
| `*LockReservationHandler` | NONE / OPTIMISTIC / PESSIMISTIC / REDIS 구현 |
| `ReservationEventPublisher` | DB 커밋 후 Kafka 발행 |
| `ReservationConfirmedConsumer` | `@Profile("consumer")` — 이벤트 소비 |

## Domain Model

### Event

| Field | Type | Notes |
|-------|------|-------|
| id | Long | PK |
| name | String | |
| capacity | Int | 총 정원 |
| reservedCount | Int | 현재 예약 수 |
| version | Long | `@Version` — Optimistic Lock |

### Reservation

| Field | Type | Notes |
|-------|------|-------|
| id | UUID | PK |
| eventId | Long | FK |
| userId | String | k6 VU 식별 |
| status | Enum | CONFIRMED |
| createdAt | Instant | |

### Seed

앱 기동 시 Flyway + `DataInitializer`로 이벤트 1건 생성: `capacity=100`, `reservedCount=0`.

## API

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/reservations` | 예약 생성 |
| GET | `/api/v1/reservations/{id}` | 예약 조회 |
| GET | `/api/v1/events/{id}` | 이벤트 현황 |
| GET | `/actuator/health` | 헬스체크 |

### Lock Strategy Resolution

1. Query: `?lockStrategy=OPTIMISTIC`
2. Header: `X-Lock-Strategy: REDIS`
3. Env: `LOCK_STRATEGY` (default: `OPTIMISTIC`)

### Error Codes

| HTTP | Code | Condition |
|------|------|-----------|
| 409 | `CAPACITY_EXCEEDED` | 정원 초과 |
| 409 | `OPTIMISTIC_LOCK_CONFLICT` | Version 충돌 |
| 409 | `DISTRIBUTED_LOCK_FAILED` | Redis 락 획득 실패 |
| 400 | `INVALID_LOCK_STRATEGY` | 잘못된 전략값 |
| 404 | `EVENT_NOT_FOUND` | 이벤트 없음 |

## Lock Strategies

| Strategy | Implementation | Test Purpose |
|----------|----------------|-------------|
| NONE | Read count → increment, no version/lock | 초과 예약 재현 |
| OPTIMISTIC | JPA `@Version`, conflict → 409 | 충돌률 측정 |
| PESSIMISTIC | `@Lock(PESSIMISTIC_WRITE)` on Event | DB 행 락, 대기 시간 |
| REDIS | `RedisLockRegistry` per eventId → DB update | 분산 락 오버헤드 |

## Kafka

- **Topic:** `reservation.confirmed`
- **Publish:** `@TransactionalEventListener(phase = AFTER_COMMIT)` 또는 서비스 내 커밋 후 발행
- **Payload:** `reservationId`, `eventId`, `userId`, `lockStrategy`, `confirmedAt`
- **Consumer:** `SPRING_PROFILES_ACTIVE=consumer` — 로그 출력

## Docker Compose

| Service | Role |
|---------|------|
| postgres | Primary DB |
| redis | Distributed lock |
| kafka + zookeeper (or KRaft) | Event bus |
| app / app-1,2,3 | API (`profile: api`) |
| reservation-consumer | Kafka consumer |
| nginx | LB (`scale3` profile) |
| k6 | Load test runner |

### Profiles

- **default / single:** app 1대, port 8080
- **scale3:** app-1,2,3 + nginx on port 80

## Testing

상세 시나리오: [`docs/test-scenarios.md`](../../test-scenarios.md)

## Project Structure

```
reservation-concurrency-lab/
├── build.gradle.kts
├── src/main/kotlin/com/lab/reservation/
│   ├── api/
│   ├── config/
│   ├── domain/
│   ├── exception/
│   ├── kafka/
│   ├── repository/
│   └── service/lock/
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
├── docker-compose.yml
├── Dockerfile
├── nginx/nginx.conf
└── scripts/
    ├── reset-lab.sh
    └── k6/scenarios/
```

## Out of Scope

- 인증/인가
- 예약 취소
- 다중 이벤트 관리 UI
- 프로덕션급 모니터링 (Prometheus 등은 추후)
