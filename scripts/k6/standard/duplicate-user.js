/**
 * standard 모드 — 동일 userId 중복 예약 차단 검증
 *
 * 기대:
 * - 동일 userId로 10회 POST → 201 정확히 1건, 나머지 409 DUPLICATE_RESERVATION
 *
 * 실행:
 *   ./scripts/reset-standard.sh
 *   docker compose run --rm k6 run /scripts/standard/duplicate-user.js
 */
import http from 'k6/http';
import { check } from 'k6';
import {
  BASE_URL,
  reservationHeaders,
  reservationPayload,
  reservationUrl,
} from '../lib/common.js';

const lockStrategy = __ENV.LOCK_STRATEGY || 'REDIS';
const duplicateUserId = __ENV.DUPLICATE_USER_ID || 'duplicate-user-standard';

export const options = {
  scenarios: {
    duplicate_user: {
      executor: 'shared-iterations',
      vus: 10,
      iterations: 10,
      maxDuration: '30s',
    },
  },
};

export default function () {
  const res = http.post(
    reservationUrl(lockStrategy),
    reservationPayload(duplicateUserId),
    { headers: reservationHeaders() },
  );
  check(res, {
    'status is 201 or 409': (r) => r.status === 201 || r.status === 409,
  });
}

export function handleSummary(data) {
  const created = data.metrics.http_reqs?.values?.count ?? 0;
  console.log(
    `standard duplicate-user test userId=${duplicateUserId} totalRequests=${created} — verify 201 count === 1 in k6 HTTP tab`,
  );
  return { stdout: JSON.stringify(data.metrics.http_reqs, null, 2) };
}
