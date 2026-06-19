/**
 * standard 모드 — 정원 100 / 500 동시 요청
 *
 * 기대:
 * - 201 ≈ 100
 * - 409 (CAPACITY_EXCEEDED 등) ≈ 400
 * - reservedCount === 100
 *
 * 실행:
 *   APP_MODE=standard docker compose --profile single up -d --build
 *   ./scripts/reset-standard.sh
 *   docker compose run --rm k6 run /scripts/standard/capacity.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, reservationHeaders, reservationPayload, reservationUrl } from '../lib/common.js';

const lockStrategy = __ENV.LOCK_STRATEGY || 'REDIS';

export const options = {
  scenarios: {
    standard_capacity: {
      executor: 'shared-iterations',
      vus: 500,
      iterations: 500,
      maxDuration: '120s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<10000'],
  },
};

export default function () {
  const userId = `standard-${__VU}-${__ITER}`;
  const res = http.post(reservationUrl(lockStrategy), reservationPayload(userId), {
    headers: reservationHeaders(),
  });
  check(res, {
    'status is 201 or 409': (r) => r.status === 201 || r.status === 409,
  });
}

export function handleSummary(data) {
  console.log(`standard capacity test BASE_URL=${BASE_URL} LOCK_STRATEGY=${lockStrategy}`);
  return { stdout: JSON.stringify(data.metrics.http_reqs, null, 2) };
}
