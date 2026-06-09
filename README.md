# Reservation Concurrency Test App

Spring Boot + Kotlin 기반 정원형 예약 **동시성 테스트 앱**. 4가지 락 전략을 단일 앱에서 비교합니다.

## Lock Strategies

| Strategy | Description |
|----------|-------------|
| `NONE` | 락 없음 — 초과 예약 재현 (의도적) |
| `OPTIMISTIC` | JPA `@Version` 낙관적 락 |
| `PESSIMISTIC` | `SELECT FOR UPDATE` 비관적 락 |
| `REDIS` | Redis 분산 락 |

전략 우선순위: `?lockStrategy=` → `X-Lock-Strategy` 헤더 → `LOCK_STRATEGY` 환경변수

## Quick Start

### Single App (1 instance)

```bash
docker compose --profile single up -d --build
curl http://localhost:8080/api/v1/events/1
```

### Scale-out (3 instances + Nginx)

```bash
docker compose --profile scale3 up -d --build
curl http://localhost/api/v1/events/1
```

### Reset test data

```bash
# Linux/macOS
./scripts/reset-lab.sh

# Windows PowerShell
./scripts/reset-lab.ps1
```

### k6 load test

```bash
# Reset → run scenario
docker compose --profile single --profile k6 run --rm k6 run /scripts/scenarios/02-optimistic-contention.js

# Scale-out (nginx)
docker compose --profile k6 run --rm -e BASE_URL=http://nginx:80 k6 run /scripts/scenarios/06-scale-out.js
```

## API

```bash
curl -X POST "http://localhost:8080/api/v1/reservations?lockStrategy=OPTIMISTIC" \
  -H "Content-Type: application/json" \
  -d '{"eventId": 1, "userId": "user-1"}'
```

## Docs

- [Design Spec](docs/superpowers/specs/2026-06-10-reservation-concurrency-lab-design.md)
- [Test Scenarios](docs/test-scenarios.md)
- [Git Conventions](GIT_CONVENTIONS.md)

## GitHub

```bash
git remote add origin https://github.com/yuuki9/reservation-concurrency-lab.git
git branch -M main
git push -u origin main
```

브랜치·커밋 규칙은 [GIT_CONVENTIONS.md](GIT_CONVENTIONS.md)를 참고하세요.

## Local Development (without Docker)

```bash
./gradlew bootRun
```

Requires PostgreSQL, Redis, Kafka running locally (defaults in `application.yml`).
