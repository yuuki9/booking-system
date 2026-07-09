/**
 * standard + PAYMENT_ENABLED — 결제 Saga happy path
 *
 * 기대:
 * - 200 동시 요청 → 201 ≈ capacity(100), 409 ≈ 나머지
 * - 201 응답 status === PENDING_PAYMENT
 * - Saga 정착 후 reservedCount === min(201 수, capacity)
 *
 * 실행:
 *   docker compose --profile single up -d --build
 *   ./scripts/reset-standard.sh
 *   docker compose run --rm k6 run /scripts/standard/payment-saga.js
 */
import http from 'k6/http';
import { check } from 'k6';
import {
  BASE_URL,
  fetchEvent,
  reservationHeaders,
  reservationPayload,
  reservationUrl,
  waitForSagaSettling,
} from '../lib/common.js';

const lockStrategy = __ENV.LOCK_STRATEGY || 'REDIS';
const concurrent = Number(__ENV.CONCURRENT || 200);

export const options = {
  scenarios: {
    payment_saga: {
      executor: 'shared-iterations',
      vus: concurrent,
      iterations: concurrent,
      maxDuration: '120s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<15000'],
  },
};

export function setup() {
  const event = fetchEvent();
  return { capacity: event.capacity };
}

export default function (data) {
  const userId = `saga-${__VU}-${__ITER}`;
  const res = http.post(reservationUrl(lockStrategy), reservationPayload(userId), {
    headers: reservationHeaders(),
  });

  if (res.status === 201) {
    const body = res.json();
    check(body, {
      'created as PENDING_PAYMENT': (b) => b.status === 'PENDING_PAYMENT',
    });
  } else {
    check(res, {
      'capacity exceeded': (r) => r.status === 409,
    });
  }
}

export function teardown(data) {
  waitForSagaSettling(Number(__ENV.SETTLE_SECONDS || 15));

  const event = fetchEvent();
  const expectedReserved = Math.min(data.capacity, concurrent);

  const ok =
    event.reservedCount === expectedReserved &&
    event.reservedCount + event.remainingCapacity === event.capacity;

  console.log(
    `payment-saga BASE_URL=${BASE_URL} LOCK_STRATEGY=${lockStrategy} ` +
      `capacity=${event.capacity} reservedCount=${event.reservedCount} ` +
      `remaining=${event.remainingCapacity} expected=${expectedReserved} ` +
      `integrity=${ok ? 'PASS' : 'FAIL'}`,
  );

  check(event, {
    'reservedCount matches successful reservations': (e) => e.reservedCount === expectedReserved,
    'capacity integrity': (e) => e.reservedCount + e.remainingCapacity === e.capacity,
  });
}

export function handleSummary(data) {
  return { stdout: JSON.stringify(data.metrics.http_reqs, null, 2) };
}
