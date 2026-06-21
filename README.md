## 선착순 이벤트 예약

콘서트 티켓이나 선착순 접수처럼, **자리 수는 정해져 있는데 많은 사람이 한꺼번에 신청**하는 상황을 다룹니다.

이벤트마다 받을 수 있는 최대 인원(`capacity`)이 있고, 예약이 하나 들어올 때마다 남은 자리는 하나씩 줄어듭니다.  
같은 상황에서도 **예약을 처리하는 방법**을 여러 가지로 바꿔 보며, 어떤 방식이 더 안정적인지 비교해 볼 수 있습니다.

---

## 실행 모드

| 모드 | 환경변수 | 목적 |
|------|----------|------|
| **basic** | `APP_MODE=basic` | 의도적으로 단순화한 비교용 실행 모드 (4종 Lock Handler → DB → Kafka) |
| **standard** (기본) | `APP_MODE=standard` | 실무형 시나리오 검증 (멱등·중복검사·Redis 선차감·Outbox). **AWS 환경에서 검증 완료** |

> **standard** 모드는 RDS·ElastiCache·MSK 등 AWS 구성 위에서 멱등 재전송, 중복 예약 차단, Outbox 기반 Kafka 발행 등 실무형 흐름을 검증했습니다.  
> **basic** 모드는 Lock Handler 비교용 단순 Flow이며, k6 벤치마크도 AWS API 대상으로 실행했습니다.

AWS API 기동 예:

```bash
SPRING_PROFILES_ACTIVE=aws \
DB_HOST=<rds-endpoint> DB_USER=... DB_PASSWORD=... \
REDIS_HOST=<elasticache-endpoint> \
KAFKA_BOOTSTRAP_SERVERS=<msk-bootstrap-brokers> \
java -jar app.jar
```

Consumer: `SPRING_PROFILES_ACTIVE=aws,consumer` (동일 DB/Redis/Kafka 환경변수)

```bash
# basic — 의도적으로 단순화한 비교용 실행 모드
APP_MODE=basic docker compose --profile single up -d --build
./scripts/reset-basic.sh
docker compose run --rm k6 run /scripts/benchmark/05-compare-all.js

# standard — 실무형 시나리오 검증 (기본값)
docker compose --profile single up -d --build
./scripts/reset-standard.sh
docker compose run --rm k6 run /scripts/standard/capacity.js
```

---

## k6 벤치마크 요약 (AWS)

`SPRING_PROFILES_ACTIVE=aws` · RDS · ElastiCache · MSK · ALB 뒤 API에 k6를 실행한 결과입니다.  
정원 **100**, 이벤트 1개 기준.

### basic — Lock Handler 4종 (`01`~`04`, 동시 200건 · NONE만 150건)

| 전략 | 201 | 409 | p95 | reservedCount | 정원 |
|------|-----|-----|-----|---------------|------|
| NONE | 132 | 18 | 88ms | **132** | ❌ 초과 예약 |
| OPTIMISTIC | 100 | 100 | 115ms | 100 | ✅ |
| PESSIMISTIC | 100 | 100 | 340ms | 100 | ✅ |
| REDIS | 100 | 100 | 168ms | 100 | ✅ |

→ **정확성:** NONE만 초과 예약 · **지연:** PESSIMISTIC(행 잠금) > REDIS > OPTIMISTIC

### basic — Scale-out (`06-scale-out.js`, REDIS · 200건)

| 구성 | 201 | p95 | reservedCount |
|------|-----|-----|---------------|
| API 1대 | 100 | 165ms | 100 |
| API 3대 + ALB | 100 | 138ms | 100 |

→ 다중 인스턴스에서도 **정원 100 유지** (REDIS 분산 락)

### standard — 실무 시나리오

| 시나리오 | 조건 | 201 | 409 | 검증 |
|----------|------|-----|-----|------|
| **capacity** | 500 동시 · REDIS | 100 | 400 | ✅ 정원 준수 |
| **duplicate-user** | **같은 userId** 10회 | 1 | 9 | ✅ 1인 1예약 |

→ **duplicate-user:** 한 사용자가 같은 이벤트를 연속 신청해도 예약 1건만 성공 (`DUPLICATE_RESERVATION`)

---

## System Architecture

![동시성 예약 시스템 아키텍처](docs/architecture.png)

k6/클라이언트 → Nginx → API 서버 3대 → PostgreSQL · Redis · Kafka.  
`docker compose --profile scale3`로 로컬에서 동일 구조를 재현할 수 있습니다.
