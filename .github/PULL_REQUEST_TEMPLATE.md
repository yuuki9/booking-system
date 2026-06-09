## Summary

<!-- 무엇을 바꿨는지 1~3문장 -->

## Change type

- [ ] `feat` — 새 기능
- [ ] `fix` — 버그 수정
- [ ] `docs` / `chore` — 문서·설정만
- [ ] `bench` — k6·부하 실험 (`scope: bench`)

## Lock / infra (해당 시)

| 항목 | 내용 |
|------|------|
| Lock strategy | NONE / OPTIMISTIC / PESSIMISTIC / REDIS / 해당 없음 |
| Scale | 1 instance / 3 instances (scale3) / 해당 없음 |
| k6 scenario | 예: `02-optimistic-contention.js` / 해당 없음 |

## Test plan

- [ ] `./gradlew build` 통과
- [ ] `docker compose --profile single up -d --build` 기동 확인
- [ ] (해당 시) `docs/test-scenarios.md` 시나리오 재현
- [ ] (해당 시) k6 실행 후 `reservedCount`·201/409 비율 기록

## Notes

<!-- 리뷰어에게 알릴 점, 알려진 제한(NONE 전략 등) -->
