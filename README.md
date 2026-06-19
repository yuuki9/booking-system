## 선착순 이벤트 예약

콘서트 티켓이나 선착순 접수처럼, **자리 수는 정해져 있는데 많은 사람이 한꺼번에 신청**하는 상황을 다룹니다.

이벤트마다 받을 수 있는 최대 인원(`capacity`)이 있고, 예약이 하나 들어올 때마다 남은 자리는 하나씩 줄어듭니다.  
같은 상황에서도 **예약을 처리하는 방법**을 여러 가지로 바꿔 보며, 어떤 방식이 더 안정적인지 비교해 볼 수 있습니다.

---

## 실행 모드

| 모드 | 환경변수 | 목적 |
|------|----------|------|
| **benchmark** | `APP_MODE=benchmark` | 4가지 락 전략 비교 실험 (Handler → DB → Kafka) |
| **standard** (기본) | `APP_MODE=standard` | 멱등·중복검사·Redis 선차감·Outbox 포함 |

```bash
# benchmark — 락 전략 4종 k6 비교
APP_MODE=benchmark docker compose --profile single up -d --build
./scripts/reset-benchmark.sh
docker compose run --rm k6 run /scripts/benchmark/05-compare-all.js

# standard — 운영형 흐름 (기본값)
docker compose --profile single up -d --build
./scripts/reset-standard.sh
docker compose run --rm k6 run /scripts/standard/capacity.js
```

---

## System Architecture

![동시성 예약 시스템 아키텍처](docs/architecture.png)

k6/클라이언트 → Nginx → API 서버 3대 → PostgreSQL · Redis · Kafka.  
`docker compose --profile scale3`로 로컬에서 동일 구조를 재현할 수 있습니다.
