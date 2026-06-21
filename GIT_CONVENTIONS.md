# Git 컨벤션

브랜치 · 커밋 · 태그 규칙을 통일해 동시성 테스트 앱의 코드 리뷰와 재현 가능한 배포를 단순하게 만듭니다.  
커밋 제목은 `type(scope): 설명` 형식을 씁니다.

## 이 저장소 구조 요약

| 경로 · 구분 | 역할 |
|-------------|------|
| `src/main/kotlin/com/lab/reservation/api/` | REST API, DTO, 전역 예외 처리 |
| `src/main/kotlin/com/lab/reservation/service/` | 예약·이벤트 서비스, 락 전략 해석(`LockStrategyResolver`) |
| `src/main/kotlin/com/lab/reservation/service/lock/` | 동시성 전략 구현 (`NONE`, `OPTIMISTIC`, `PESSIMISTIC`, `REDIS`) |
| `src/main/kotlin/com/lab/reservation/kafka/` | 예약 확정 이벤트 발행·소비 |
| `src/main/kotlin/com/lab/reservation/domain/` | `Event`, `Reservation`, `LockStrategy` 엔티티·열거형 |
| `src/main/kotlin/com/lab/reservation/config/` | Redis Lock, Kafka, 시드 데이터 |
| `src/main/resources/db/migration/` | Flyway 스키마 마이그레이션 |
| `scripts/k6/` | k6 부하 테스트 시나리오 |
| `scripts/` | 실험 데이터 리셋 (`reset-basic.*`, `reset-standard.*`) |
| `docker-compose.yml`, `Dockerfile`, `nginx/` | 로컬·Scale-out 실행 환경 |
| `docs/` | 설계 스펙, 테스트 시나리오, 구현 계획 |

## GitHub 원격 저장소

| 항목 | 값 |
|------|-----|
| 원격 URL | `https://github.com/yuuki9/reservation-concurrency-lab.git` |
| 기본 브랜치 | `main` |

### 최초 연결 (로컬에 아직 `.git`이 없을 때)

```bash
git init
git remote add origin https://github.com/yuuki9/reservation-concurrency-lab.git
git branch -M main
```

### 최초 푸시

```bash
git add .
git commit -m "feat(app): 동시성 테스트 앱 초기 구현"
git push -u origin main
```

이미 원격에 커밋이 있다면 `git pull --rebase origin main` 후 푸시합니다.

## 브랜치 전략

| 브랜치 | 목적 | 설명 |
|--------|------|------|
| `main` | 기준 브랜치 | 실험 가능한 안정 상태 유지 |
| `feature/*` | 기능 개발 | 예: `feature/k6-scale-out-scenario` |
| `fix/*` | 버그 수정 | 예: `fix/redis-lock-release` |
| `chore/*` | 설정·문서·CI만 | 예: `chore/docs-test-scenarios` |

### 네이밍 예시

- 기능: `feature/kafka-dead-letter`, `feature/actuator-metrics`
- 수정: `fix/optimistic-lock-409-mapping`
- 잡무: `chore/deploy-scale3-compose`

작업 브랜치는 `feature/*`, `fix/*`, `chore/*` 중 하나로 통일합니다. k6·벤치마크도 `feature/bench-*` 로 충분합니다.

## 커밋 메시지

### 언어

- **커밋 로그(제목·본문)는 한글을 사용합니다.** PR 설명도 동일하게 맞추는 것을 권장합니다.
- `feat`, `fix`, `lock` 같은 **타입·scope는 영어**로 두고, 콜론 뒤 **설명은 한글**로 씁니다.

### 형식

```
type(scope): 짧은 설명 (한 줄)
```

- **scope**: 아래 **5개 중 하나**만 씁니다. 애매하면 가장 넓은 `app` 또는 `repo`를 택합니다.
- 본문이 필요하면 한 줄 띄우고 무엇을·왜 했는지 보강합니다.
- scope를 더 쪼개지 않습니다. 세부 내용은 **커밋 설명(한글)** 에 적습니다.

### scope 가이드 (5개)

| scope | 넣으면 되는 변경 |
|--------|------------------|
| `app` | `src/` 전반 — API, 락 전략, Kafka, 도메인, Flyway, 테스트 |
| `bench` | `scripts/k6/` — k6 시나리오·부하 실험 |
| `deploy` | `Dockerfile`, `docker-compose.yml`, `nginx/`, `scripts/reset-*.sh` |
| `docs` | `README.md`, `docs/`, `GIT_CONVENTIONS.md` |
| `repo` | `build.gradle.kts`, `.gitignore`, `.github/`, Gradle wrapper 등 루트 설정 |

`app`과 `bench`를 동시에 건드리면 **더 핵심인 쪽 하나**만 scope로 씁니다.

### 실제 로그 예시

- `feat(app): Redis 분산 락 핸들러에 TransactionTemplate 적용`
- `fix(app): OPTIMISTIC 충돌 시 409 응답 코드 매핑 수정`
- `feat(bench): Scale-out 시나리오에 LOCK_STRATEGY 환경변수 지원`
- `chore(deploy): scale3 프로필에 Nginx 업스트림 추가`
- `docs: test-scenarios에 single 프로필 기동 명령 반영`

### type

| 타입 | 의미 |
|------|------|
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `docs` | 문서만 |
| `style` | 포맷 등 (동작 변화 없음) |
| `refactor` | 리팩터링 (동작 유지) |
| `test` | 테스트 추가·수정 |
| `chore` | 빌드·설정·잡무 (scope로 구체화) |

## 권장 워크플로

1. `main`에서 작업 브랜치 생성
2. 위 컨벤션에 맞게 커밋
3. PR로 리뷰 (`.github/PULL_REQUEST_TEMPLATE.md` 작성)
4. 머지 전 `./gradlew build` 통과 확인
5. 동시성 실험 변경이면 `docs/test-scenarios.md`의 해당 시나리오로 재현 여부 기록

### 실험·벤치마크 PR 시 권장 기록

- 사용한 락 전략 (`NONE` / `OPTIMISTIC` / `PESSIMISTIC` / `REDIS`)
- 인스턴스 수 (1대 / 3대)
- k6 시나리오 파일명·VU·iteration
- `reservedCount` 최종값, 201/409 비율, p95 (가능하면)

## 버전 태그 (`v*`)

- **릴리스 지점**에만 `v0.1.0`처럼 태그합니다. 매 커밋마다 태그할 필요는 없습니다.
- 이미 원격에 올린 태그는 무심코 지우면 clone·CI가 깨질 수 있습니다.

## 참고

- PR은 `.github/PULL_REQUEST_TEMPLATE.md`를 채워 올립니다. **레포 안에 `pr-body-*.md` 같은 별도 본문 파일을 새로 두지 않는 것**을 권장합니다.
- `build/`, `.gradle/` 은 커밋하지 않습니다 (`.gitignore` 적용).
- `NONE` 전략은 **의도적 초과 예약 재현용**입니다. 운영 배포 대상이 아님을 PR·문서에 명시하는 것을 권장합니다.

### PR 본문이 “마크다운이 안 먹는 것처럼” 보일 때

- GitHub PR 설명은 기본이 **GitHub Flavored Markdown** 입니다. 화면에 `\r\n` 같은 글자가 그대로 보이면, 본문에 **실제 줄바꿈이 아니라 역슬래시+문자**가 들어간 것입니다.
- 인라인 코드는 **백틱** `` `LOCK_STRATEGY` `` 를 씁니다.

### PR 본문을 넣는 방법 (권장 순)

1. **GitHub 웹**에서 PR 생성·수정: `.github/PULL_REQUEST_TEMPLATE.md`가 설명란에 자동으로 들어갑니다.
2. **GitHub CLI**: `gh pr create` 시 `--body-file`에 템플릿을 채운 파일을 넘깁니다.
