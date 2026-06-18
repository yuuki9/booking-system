export const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
export const EVENT_ID = Number(__ENV.EVENT_ID || 1);

export function reservationPayload(userId) {
  return JSON.stringify({
    eventId: EVENT_ID,
    userId: userId,
  });
}

export function reservationUrl(lockStrategy) {
  return `${BASE_URL}/api/v1/reservations?lockStrategy=${lockStrategy}`;
}

export function reservationHeaders(idempotencyKey) {
  const headers = { 'Content-Type': 'application/json' };
  if (idempotencyKey) {
    headers['X-Idempotency-Key'] = idempotencyKey;
  }
  return headers;
}
