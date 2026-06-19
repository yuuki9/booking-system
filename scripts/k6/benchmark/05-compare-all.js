import http from 'k6/http';
import { check, group } from 'k6';
import { reservationHeaders, reservationPayload, reservationUrl } from '../lib/common.js';

const strategies = ['NONE', 'OPTIMISTIC', 'PESSIMISTIC', 'REDIS'];

export const options = {
  scenarios: Object.fromEntries(
    strategies.map((strategy) => [
      strategy.toLowerCase(),
      {
        executor: 'shared-iterations',
        vus: 50,
        iterations: 50,
        maxDuration: '30s',
        exec: 'runStrategy',
        env: { STRATEGY: strategy },
      },
    ]),
  ),
};

export function runStrategy() {
  const strategy = __ENV.STRATEGY;
  group(strategy, () => {
    const userId = `${strategy}-${__VU}-${__ITER}`;
    const res = http.post(reservationUrl(strategy), reservationPayload(userId), {
      headers: reservationHeaders(),
    });
    check(res, {
      'status is 201 or 409': (r) => r.status === 201 || r.status === 409,
    });
  });
}
