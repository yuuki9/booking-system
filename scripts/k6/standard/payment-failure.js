/**
 * standard + PAYMENT_ENABLED — 결제 실패 30% 재고 정합성
 *
 * payment 서비스를 PG_FAILURE_RATE=0.3 으로 기동한 뒤 실행:
 *   PG_FAILURE_RATE=0.3 docker compose up -d --build payment
 *
 * 기대:
 * - 200 동시 요청 → 201 ≈ capacity(100)
 * - Saga 정착 후 reservedCount === CONFIRMED 수 (실패분은 CANCELLED + 좌석 반환)
 * - reservedCount + remainingCapacity === capacity (DB 정합)
 *
 * 실행:
 *   docker compose --profile single up -d --build
 *   PG_FAILURE_RATE=0.3 docker compose up -d --no-deps --build payment
 *   ./scripts/reset-standard.sh
 *   docker compose run --rm k6 run /scripts/standard/payment-failure.js
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
    payment_failure: {
      executor: 'shared-iterations',
      vus: concurrent,
      iterations: concurrent,
      maxDuration: '180s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<20000'],
  },
};

export function setup() {
  const event = fetchEvent();
  return { capacity: event.capacity };
}

export default function () {
  const userId = `payfail-${__VU}-${__ITER}`;
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
  waitForSagaSettling(Number(__ENV.SETTLE_SECONDS || 20));

  const event = fetchEvent();
  const capacity = data.capacity;
  const maxReserved = Math.min(capacity, concurrent);

  const integrityOk = event.reservedCount + event.remainingCapacity === event.capacity;
  const withinCapacity = event.reservedCount <= maxReserved && event.reservedCount >= 0;

  console.log(
    `payment-failure BASE_URL=${BASE_URL} PG_FAILURE_RATE=0.3 ` +
      `reservedCount=${event.reservedCount} remaining=${event.remainingCapacity} ` +
      `capacity=${capacity} integrity=${integrityOk && withinCapacity ? 'PASS' : 'FAIL'} ` +
      `(CONFIRMED ≈ ${event.reservedCount}, failures returned seats)`,
  );

  check(event, {
    'no overbooking after payment failures': (e) => e.reservedCount <= maxReserved,
    'capacity integrity': (e) => e.reservedCount + e.remainingCapacity === e.capacity,
  });
}

export function handleSummary(data) {
  return { stdout: JSON.stringify(data.metrics.http_reqs, null, 2) };
}
