# 선착순 이벤트 예약

선착순 이벤트·공연 예약처럼 **자리가 한정된 서비스**를 가정한 백엔드 프로젝트입니다.  
많은 사용자가 동시에 예약할 때 생기는 문제(마감 넘침, 중복 처리)를 다루고,  
그걸 막는 **4가지 처리 방식**을 하나의 앱에서 바꿔 가며 비교할 수 있습니다.

Kotlin · Spring Boot 3 · PostgreSQL · Redis · Kafka · Docker Compose · k6

---

## System Architecture

![동시성 예약 시스템 아키텍처](docs/architecture.png)

k6/클라이언트 → Nginx → API 서버 3대 → PostgreSQL · Redis · Kafka.  
`docker compose --profile scale3`로 로컬에서 동일 구조를 재현할 수 있습니다.

## 어떻게 동작하나요

이벤트마다 최대 인원(`capacity`)이 있고, 예약이 들어올 때마다 남은 자리가 줄어듭니다.  
서버 설정이나 요청마다 **동시 예약 처리 방식**을 바꿔 보면서 동작 차이를 확인할 수 있습니다.

---

## 동시 예약 처리 방식

| 방식 | 한 줄 설명 | 언제 쓰면 좋을까 |
|------|-----------|-----------------|
| `NONE` | 따로 막지 않음 | 문제 상황 재현용 (의도적으로 마감 넘침 발생) |
| `OPTIMISTIC` | 충돌 나면 다시 시도 | 트래픽이 많지 않을 때, DB 부담을 줄이고 싶을 때 |
| `PESSIMISTIC` | DB에서 순서대로 처리 | 정확성을 최우선으로 할 때 |
| `REDIS` | Redis로 한 줄 세우기 | API 서버가 여러 대일 때 |

방식 지정: URL `?lockStrategy=` → 헤더 `X-Lock-Strategy` → 환경변수 `LOCK_STRATEGY`

---

## 바로 실행해 보기

```bash
# 1. 서비스 띄우기
docker compose --profile single up -d --build

# 2. 이벤트 조회 (100석 이벤트)
curl http://localhost:8080/api/v1/events/1

# 3. 예약하기
curl -X POST "http://localhost:8080/api/v1/reservations?lockStrategy=OPTIMISTIC" \
  -H "Content-Type: application/json" \
  -d '{"eventId": 1, "userId": "user-1"}'
```

| 하고 싶은 것 | 명령 |
|-------------|------|
| 서버 3대 + 로드밸런서 | `docker compose --profile scale3 up -d --build` |
| 예약 데이터 초기화 | `./scripts/reset-data.sh` · `./scripts/reset-data.ps1` |
| 동시 접속 부하 테스트 | [테스트 시나리오](docs/test-scenarios.md) |
| Docker 없이 로컬 실행 | `./gradlew bootRun` (PostgreSQL · Redis · Kafka 필요) |

---

## API

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/api/v1/events/{id}` | 이벤트 정보 · 남은 자리 |
| `POST` | `/api/v1/reservations` | 예약하기 |
| `GET` | `/api/v1/reservations/{id}` | 내 예약 조회 |
| `GET` | `/actuator/health` | 서버 상태 확인 |

---

## 더 자세히

| 문서 | 내용 |
|------|------|
| [Design Spec](docs/superpowers/specs/2026-06-10-booking-system-design.md) | 설계 · 데이터 모델 · 에러 코드 |
| [Test Scenarios](docs/test-scenarios.md) | 부하 테스트 시나리오 · 실험 방법 |
| [Git Conventions](GIT_CONVENTIONS.md) | 브랜치 · 커밋 규칙 |
