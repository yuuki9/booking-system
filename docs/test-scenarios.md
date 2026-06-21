# 동시성 테스트 앱 — 테스트 시나리오 & 실행 방법

> 예약 정원형 도메인(이벤트 1개, capacity=N)을 기준으로 Lock 전략·Scale-out·Kafka·k6 실험 절차를 정리합니다.

## 실행 모드

| 모드 | `APP_MODE` | k6 경로 | reset |
|------|------------|---------|-------|
| **basic** | `basic` | `scripts/k6/benchmark/` | `reset-basic.sh` |
| **standard** | `standard` (기본) | `scripts/k6/standard/` | `reset-standard.sh` |

---

## 사전 준비

### 인프라 기동

```bash
# 단일 앱 스택 기동 (PostgreSQL, Redis, Kafka, App x1, Consumer)
docker compose --profile single up -d --build --build

# 헬스체크
curl http://localhost:8080/actuator/health
```

### 시드 데이터 확인

```bash
curl http://localhost:8080/api/v1/events/1
```

기대 응답 예시:

```json
{
  "id": 1,
  "name": "Concurrency Test Event",
  "capacity": 100,
  "reservedCount": 0,
  "remainingCapacity": 100
}
```

### 데이터 초기화 (실험 반복 시)

```bash
# PostgreSQL reservedCount 리셋 + 예약 테이블 비우기
docker compose exec postgres psql -U lab -d reservation_lab -c \
  "UPDATE events SET reserved_count = 0, version = 0 WHERE id = 1; TRUNCATE reservations;"
```

또는 제공되는 reset 스크립트:

```bash
./scripts/reset-basic.sh
```

---

## 공통 API

### 예약 생성

```bash
curl -X POST "http://localhost:8080/api/v1/reservations?lockStrategy=OPTIMISTIC" \
  -H "Content-Type: application/json" \
  -d '{"eventId": 1, "userId": "user-1"}'
```

### 락 전략 지정 방법

| 우선순위 | 방식 | 예시 |
|----------|------|------|
| 1 (최우선) | Query Parameter | `?lockStrategy=PESSIMISTIC` |
| 2 | Request Header | `X-Lock-Strategy: REDIS` |
| 3 (기본값) | 환경변수 | `LOCK_STRATEGY=NONE` |

허용 값: `NONE` | `OPTIMISTIC` | `PESSIMISTIC` | `REDIS`

---

## 시나리오 1 — Lock 없음 (NONE): 초과 예약 재현

**목적**: 락 없이 동시 요청 시 `reservedCount`가 정원을 초과하는지 확인 (의도적 버그 재현)

| 항목 | 값 |
|------|-----|
| 정원 | 100 |
| 동시 요청 | 150 |
| 기대 결과 | `reservedCount` > 100 (초과 예약 발생) |

### k6 실행

```bash
docker compose run --rm k6 run /scripts/benchmark/01-none-overbooking.js
```

### 수동 검증

```bash
# 예약 후 정원 확인
curl http://localhost:8080/api/v1/events/1 | jq '.reservedCount, .capacity'

# DB 직접 확인
docker compose exec postgres psql -U lab -d reservation_lab -c \
  "SELECT reserved_count, capacity FROM events WHERE id = 1;"
docker compose exec postgres psql -U lab -d reservation_lab -c \
  "SELECT COUNT(*) FROM reservations;"
```

### 성공 기준

- [ ] HTTP 201 응답이 100건을 **초과**
- [ ] `events.reserved_count` 또는 `reservations` COUNT가 capacity(100) 초과
- [ ] k6 리포트에 `CAPACITY_EXCEEDED` 없이 다수 201 (NONE은 검증 없이 증가)

---

## 시나리오 2 — Optimistic Lock: 경합 시 충돌

**목적**: `@Version` 기반 낙관적 락이 동시 쓰기를 막는지, 충돌률·처리량 측정

| 항목 | 값 |
|------|-----|
| 정원 | 100 |
| 동시 요청 | 200 |
| 기대 결과 | 정확히 100건만 성공, 나머지 `409 OPTIMISTIC_LOCK_CONFLICT` 또는 `CAPACITY_EXCEEDED` |

### k6 실행

```bash
docker compose run --rm k6 run /scripts/benchmark/02-optimistic-contention.js
```

### 수동 검증

```bash
curl http://localhost:8080/api/v1/events/1
```

### 성공 기준

- [ ] `reservedCount` === 100 (초과 없음)
- [ ] `reservations` COUNT === 100
- [ ] k6: 201 ≈ 100, 409 ≈ 100
- [ ] 409 응답 body에 `OPTIMISTIC_LOCK_CONFLICT` 또는 `CAPACITY_EXCEEDED` 포함

---

## 시나리오 3 — Pessimistic Lock: DB 행 잠금

**목적**: `SELECT FOR UPDATE`로 정원 준수 + 대기 시간·처리량 측정

| 항목 | 값 |
|------|-----|
| 정원 | 100 |
| 동시 요청 | 200 |
| 기대 결과 | 100 성공, 초과 예약 0, NONE 대비 지연 증가 가능 |

### k6 실행

```bash
docker compose run --rm k6 run /scripts/benchmark/03-pessimistic-throughput.js
```

### 성공 기준

- [ ] `reservedCount` === 100
- [ ] 초과 예약 없음
- [ ] p95 latency가 OPTIMISTIC/REDIS와 비교 기록 (벤치마크 표 작성)

---

## 시나리오 4 — Redis Distributed Lock: 분산 락

**목적**: Redis `SETNX` 기반 분산 락으로 정원 준수, 락 획득 실패율 측정

| 항목 | 값 |
|------|-----|
| 정원 | 100 |
| 동시 요청 | 200 |
| 기대 결과 | 100 성공, `DISTRIBUTED_LOCK_FAILED` 또는 `CAPACITY_EXCEEDED` |

### k6 실행

```bash
docker compose run --rm k6 run /scripts/benchmark/04-redis-distributed-lock.js
```

### Redis 락 키 확인 (선택)

```bash
docker compose exec redis redis-cli KEYS "event:*:lock"
```

### 성공 기준

- [ ] `reservedCount` === 100
- [ ] 초과 예약 없음
- [ ] 409 중 `DISTRIBUTED_LOCK_FAILED` 비율 기록

---

## 시나리오 5 — 전략 A/B/C/D 비교 (단일 인스턴스)

**목적**: 동일 부하·동일 정원에서 4전략 처리량·지연·오류율 나란히 비교

### 절차

1. 데이터 초기화 (`reset-basic.sh`)
2. 각 전략별 k6 실행 (01~04 또는 통합 `05-compare-all.js`)
3. 결과를 표로 정리

### k6 실행 (통합 비교)

```bash
docker compose run --rm k6 run /scripts/benchmark/05-compare-all.js
```

### 기록할 메트릭

| 전략 | http_reqs (201) | http_req_failed (409/5xx) | p50 (ms) | p95 (ms) | p99 (ms) | reservedCount 최종 |
|------|-----------------|----------------------------|----------|----------|----------|-------------------|
| NONE | | | | | | (>100 기대) |
| OPTIMISTIC | | | | | | 100 |
| PESSIMISTIC | | | | | | 100 |
| REDIS | | | | | | 100 |

### 성공 기준

- [ ] 4전략 모두 동일 VU·동일 duration으로 실행
- [ ] NONE만 초과 예약, 나머지 3전략은 정원 준수
- [ ] 비교 표 작성 완료

---

## 시나리오 6 — Scale-out 1대 → 3대

**목적**: 앱 인스턴스 1대 vs 3대에서 REDIS/PESSIMISTIC 전략의 정원 준수 및 처리량 변화

### 6-A: 단일 인스턴스 (baseline)

```bash
docker compose --profile single up -d --build
./scripts/reset-basic.sh
docker compose run --rm k6 run -e LOCK_STRATEGY=REDIS /scripts/benchmark/06-scale-out.js
```

### 6-B: 3 인스턴스 + Nginx LB

```bash
docker compose --profile scale3 up -d
./scripts/reset-basic.sh
docker compose run --rm k6 run -e LOCK_STRATEGY=REDIS /scripts/benchmark/06-scale-out.js
```

### Nginx 업스트림 확인

```bash
# 3대 기동 시 로그에서 인스턴스 분산 확인
docker compose logs app-1 app-2 app-3 --tail 20
```

### 성공 기준

- [ ] 1대·3대 모두 `reservedCount` === 100 (초과 없음)
- [ ] 3대 시 Nginx가 요청을 분산 (각 app 로그에 예약 처리 흔적)
- [ ] REDIS 전략: 3대에서도 분산 락으로 정원 유지
- [ ] PESSIMISTIC: DB 단일 행 락이므로 3대여도 정원 유지 (처리량은 DB 병목 가능)

### 비교 포인트

| 구성 | 인스턴스 | 전략 | p95 | 성공(201) | 비고 |
|------|----------|------|-----|-----------|------|
| single | 1 | REDIS | | | |
| scale3 | 3 | REDIS | | | LB + 분산 락 |
| single | 1 | PESSIMISTIC | | | DB 병목 |
| scale3 | 3 | PESSIMISTIC | | | 행 락 직렬화 |

---

## 시나리오 7 — Kafka 비동기 후처리

**목적**: DB 예약 성공 후 `reservation.confirmed` 이벤트 발행·소비 검증 (동시성 경로와 분리)

### 절차

```bash
./scripts/reset-basic.sh

# Consumer 로그 tail
docker compose logs -f reservation-consumer &

# 예약 10건
for i in $(seq 1 10); do
  curl -s -X POST "http://localhost:8080/api/v1/reservations?lockStrategy=OPTIMISTIC" \
    -H "Content-Type: application/json" \
    -d "{\"eventId\": 1, \"userId\": \"kafka-user-$i\"}"
done
```

### 검증

```bash
# Consumer 로그에 10건 CONFIRMED 처리 메시지
docker compose logs reservation-consumer | grep -c "ReservationConfirmed"

# (선택) Kafka 토픽 메시지 수
docker compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic reservation.confirmed \
  --from-beginning --timeout-ms 5000 | wc -l
```

### 성공 기준

- [ ] DB 예약 10건과 Consumer 처리 10건 일치
- [ ] 예약 실패(409) 건은 Kafka 메시지 없음
- [ ] Consumer 지연은 동시성 실험 KPI에서 제외 (사후 처리)

---

## 시나리오 8 — 스모크 테스트 (CI/로컬 빠른 확인)

**목적**: 배포·빌드 후 최소 동작 확인

```bash
docker compose up -d --wait
./scripts/reset-basic.sh

# 헬스
curl -f http://localhost:8080/actuator/health

# 전략별 1건씩
for s in NONE OPTIMISTIC PESSIMISTIC REDIS; do
  curl -sf -X POST "http://localhost:8080/api/v1/reservations?lockStrategy=$s" \
    -H "Content-Type: application/json" \
    -d "{\"eventId\": 1, \"userId\": \"smoke-$s\"}" || echo "FAIL: $s"
done

curl -f http://localhost:8080/api/v1/events/1
```

### 성공 기준

- [ ] 4전략 모두 201 (정원 내 4건)
- [ ] `reservedCount` === 4

---

## k6 스크립트 구조 (예정)

```
scripts/
├── reset-basic.sh
├── reset-standard.sh
└── k6/
    └── scenarios/
        ├── 01-none-overbooking.js
        ├── 02-optimistic-contention.js
        ├── 03-pessimistic-throughput.js
        ├── 04-redis-distributed-lock.js
        ├── 05-compare-all.js
        └── 06-scale-out.js
```

### 공통 k6 옵션 예시

```javascript
export const options = {
  scenarios: {
    contention: {
      executor: 'shared-iterations',
      vus: 200,
      iterations: 200,
      maxDuration: '30s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<5000'],
  },
};
```

환경변수:

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `BASE_URL` | API 베이스 URL | `http://nginx:80` (compose 내부) |
| `LOCK_STRATEGY` | 요청 시 사용할 전략 | `OPTIMISTIC` |
| `EVENT_ID` | 대상 이벤트 ID | `1` |

---

## 실험 체크리스트 (실행 순서 권장)

1. [ ] 시나리오 8 — 스모크
2. [ ] 시나리오 1 — NONE 초과 예약 확인
3. [ ] 시나리오 2~4 — 전략별 정원 준수
4. [ ] 시나리오 5 — 4전략 비교 표
5. [ ] 시나리오 6 — Scale-out 1 vs 3
6. [ ] 시나리오 7 — Kafka 후처리

---

## 트러블슈팅

| 증상 | 확인 |
|------|------|
| 503 / connection refused | `docker compose ps`, Nginx·App 기동 여부 |
| 항상 409 | `reset-basic.sh`로 정원 리셋 |
| REDIS 전략만 실패 | `docker compose logs redis`, Redis 연결 설정 |
| Kafka 메시지 없음 | Producer 로그, `reservation.confirmed` 토픽 존재 여부 |
| 3대인데 한 인스턴스만 처리 | Nginx upstream 설정, `scale3` profile 적용 여부 |

---

## 참고

- 동시성 **검증 KPI**: `reservedCount`, HTTP 상태 코드 분포, k6 latency
- Kafka·Consumer는 **부가 경로** — 예약 성공 건수와 Consumer 처리 건수 일치만 확인
- `NONE`은 교육용; 운영에서는 사용하지 않음
