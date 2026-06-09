import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, reservationHeaders, reservationPayload, reservationUrl } from '../lib/common.js';

export const options = {
  scenarios: {
    overbooking: {
      executor: 'shared-iterations',
      vus: 150,
      iterations: 150,
      maxDuration: '60s',
    },
  },
};

export default function () {
  const userId = `none-user-${__VU}-${__ITER}`;
  const res = http.post(reservationUrl('NONE'), reservationPayload(userId), {
    headers: reservationHeaders(),
  });
  check(res, {
    'status is 201 or 409': (r) => r.status === 201 || r.status === 409,
  });
}

export function handleSummary(data) {
  console.log(`BASE_URL=${BASE_URL}`);
  return { stdout: JSON.stringify(data.metrics, null, 2) };
}
