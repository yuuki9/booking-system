import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, reservationHeaders, reservationPayload, reservationUrl } from '../lib/common.js';

const lockStrategy = __ENV.LOCK_STRATEGY || 'REDIS';

export const options = {
  scenarios: {
    scale_out: {
      executor: 'shared-iterations',
      vus: 200,
      iterations: 200,
      maxDuration: '60s',
    },
  },
};

export default function () {
  const userId = `scale-${__VU}-${__ITER}`;
  const res = http.post(reservationUrl(lockStrategy), reservationPayload(userId), {
    headers: reservationHeaders(),
  });
  check(res, {
    'status is 201 or 409': (r) => r.status === 201 || r.status === 409,
  });
}

export function handleSummary(data) {
  console.log(`BASE_URL=${BASE_URL} LOCK_STRATEGY=${lockStrategy}`);
  return { stdout: JSON.stringify(data.metrics.http_req_duration, null, 2) };
}
