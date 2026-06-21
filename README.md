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
> **basic** 모드는 로컬/Docker Compose에서 Lock Handler 동작·k6 부하 비교용입니다.

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

## System Architecture

![동시성 예약 시스템 아키텍처](docs/architecture.png)

k6/클라이언트 → Nginx → API 서버 3대 → PostgreSQL · Redis · Kafka.  
`docker compose --profile scale3`로 로컬에서 동일 구조를 재현할 수 있습니다.
