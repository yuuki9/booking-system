# AGENT.md — booking-system

> AI 에이전트 및 기여자가 이 저장소를 빠르게 이해하기 위한 프로젝트 가이드

## 프로젝트 개요

**선착순 이벤트 예약 시스템**입니다. 콘서트 티켓·한정 수량 이벤트처럼 **정해진 `capacity` 안에서 많은 사용자가 동시에 예약**하는 상황을 다룹니다.

코드는 **두 갈래**로 나뉩니다.

| | benchmark | standard |
|---|-----------|----------|
| **역할** | 성능 측정·비교용 | 실제 운영용 |
| **목적** | 락 전략 4종을 **공정하게** k6로 벤치마킹 | 멱등·중복 방지·Outbox 등 **프로덕션 하드닝** |
| **구현** | `BenchmarkReservationFlow` | `StandardReservationFlow` |

- **benchmark** — 동시성 전략(`NONE`, `OPTIMISTIC`, `PESSIMISTIC`, `REDIS`)만 격리해 측정합니다. 멱등·Outbox·Redis 선차감 같은 부가 로직은 **의도적으로 빼서** 전략 간 차이만 비교합니다.
- **standard** — 실제 서비스에 가까운 코드입니다. 재전송 대응, 중복 예약 차단, 재고 선차감, DB 커밋과 Kafka 발행 분리(Outbox)를 포함합니다.

기술 스택: **Kotlin**, **Spring Boot 3.4**, **PostgreSQL**, **Redis**, **Kafka**, **Flyway**, **Docker Compose**, **k6**(부하 테스트)

---

## 시스템 아키텍처

```
Client / k6
    │
    ▼
 Nginx (scale3 프로필)
    │
    ▼
 API 서버 (1~3대)
    │
    ├── PostgreSQL  (events, reservations, outbox, idempotency)
    ├── Redis       (분산 락, standard 모드 재고 선차감)
    └── Kafka       (reservation.confirmed 이벤트)
            │
            ▼
     reservation-consumer (별도 프로세스)
```

아키텍처 다이어그램: [`docs/architecture.png`](docs/architecture.png)

---

## 실행 모드

| 모드 | 환경변수 | 성격 |
|------|----------|------|
| **standard** (기본) | `APP_MODE=standard` | **운영 코드** — 멱등·중복검사·Redis 선차감·Outbox |
| **benchmark** | `APP_MODE=benchmark` | **벤치마크 코드** — 락 전략 성능 비교 전용 |

`APP_MODE`에 따라 `ReservationCreationFlow` 구현체가 `@ConditionalOnProperty`로 하나만 활성화됩니다. benchmark는 실험용 단순 경로, standard는 배포 대상 경로입니다.

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

### standard 모드

```
POST /api/v1/reservations
  → 멱등 키 확인 (X-Idempotency-Key)
  → 중복 사용자 검사
  → Redis 재고 선차감 (선택적)
  → ReservationLockExecutor → LockHandler
  → Outbox 적재 (또는 Kafka 직접 publish)
  → 멱등 키 저장
```

Outbox는 `StandardOutboxPublisher`가 폴링하여 Kafka로 발행합니다.

### benchmark 모드

```
POST /api/v1/reservations
  → ReservationLockExecutor → LockHandler
  → Kafka 직접 publish
```

---

## 디렉토리 구조

```
booking-system/
├── src/main/kotlin/com/lab/reservation/
│   ├── BookingSystemApplication.kt   # 진입점
│   ├── api/                            # REST Controller, DTO, 예외 처리
│   ├── config/                         # AppMode, Redis, Kafka, 초기 데이터
│   ├── domain/                         # JPA 엔티티, LockStrategy enum
│   ├── exception/                      # 도메인 예외
│   ├── kafka/                          # 이벤트 publish/consume
│   ├── repository/                     # Spring Data JPA
│   └── service/
│       ├── ReservationService.kt       # 예약 조회·생성 진입
│       ├── ReservationCreationFlow.kt  # 모드별 Flow 인터페이스
│       ├── ReservationLockExecutor.kt  # LockHandler 라우팅
│       ├── LockStrategyResolver.kt
│       ├── lock/                       # 4종 LockHandler 구현
│       ├── standard/                   # standard 모드 전용 (Outbox, Redis 재고)
│       └── benchmark/                  # benchmark 모드 Flow
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/                   # Flyway (V1 init, V2 hardening)
├── src/test/kotlin/                    # 단위·통합·벤치마크 테스트
├── scripts/
│   ├── reset-*.sh / *.ps1              # DB·Redis 초기화
│   └── k6/                             # 부하 테스트 스크립트
├── docker-compose.yml                  # postgres, redis, kafka, app, nginx, k6
├── nginx/                              # scale3 로드밸런싱 설정
├── docs/                               # 아키텍처 문서
└── README.md                           # 사용자용 실행 가이드
```

---

## 주요 API

| Method | Path | 설명 |
|--------|------|------|
| `POST` | `/api/v1/reservations` | 예약 생성 |
| `GET` | `/api/v1/reservations/{id}` | 예약 조회 |
| `GET` | `/api/v1/events/{id}` | 이벤트(잔여 좌석) 조회 |

**standard 모드 헤더**

- `X-Idempotency-Key` — 동일 키 재전송 시 기존 예약 반환 (200 OK)
- `X-Lock-Strategy` — 락 전략 지정

---

## 로컬 실행

```bash
# standard (기본)
docker compose --profile single up -d --build
./scripts/reset-standard.sh

# benchmark — 락 전략 비교
APP_MODE=benchmark docker compose --profile single up -d --build
./scripts/reset-benchmark.sh
docker compose run --rm k6 run /scripts/benchmark/05-compare-all.js

# 3대 스케일아웃
docker compose --profile scale3 up -d --build
```

테스트:

```bash
./gradlew test
```

---

## 데이터 모델 (요약)

| 테이블 | 용도 |
|--------|------|
| `events` | 이벤트, `capacity`, `reserved_count`, `version` |
| `reservations` | 예약 (event_id + user_id 유니크) |
| `reservation_outbox` | Kafka Outbox (standard) |
| `idempotency_records` | 멱등 키 → reservation_id 매핑 |

---

## 에이전트 작업 시 참고

- **모드 변경**은 `app.mode` / `APP_MODE` 환경변수로 제어합니다. Flow 구현체를 직접 교체하지 않습니다.
- **락 전략 추가** 시 `LockStrategy` enum + `ReservationLockHandler` 구현 + Spring Bean 등록이 필요합니다.
- **standard 전용 기능**(Outbox, Redis 재고, 멱등)은 `service/standard/` 패키지에 모여 있습니다.
- **부하 테스트**는 `scripts/k6/` 아래 benchmark·standard 스크립트를 사용합니다.
- DB 스키마 변경은 Flyway migration(`src/main/resources/db/migration/`)으로만 반영합니다.
- 사용자 대상 실행 설명은 [`README.md`](README.md)를, 이 파일은 **코드베이스 내비게이션**용입니다.
