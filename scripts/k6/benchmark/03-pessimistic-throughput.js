import http from 'k6/http';
import { check } from 'k6';
import { reservationHeaders, reservationPayload, reservationUrl } from '../lib/common.js';

export const options = {
  scenarios: {
    contention: {
      executor: 'shared-iterations',
      vus: 200,
      iterations: 200,
      maxDuration: '60s',
    },
  },
};

export default function () {
  const userId = `pes-user-${__VU}-${__ITER}`;
  const res = http.post(reservationUrl('PESSIMISTIC'), reservationPayload(userId), {
    headers: reservationHeaders(),
  });
  check(res, {
    'status is 201 or 409': (r) => r.status === 201 || r.status === 409,
  });
}
