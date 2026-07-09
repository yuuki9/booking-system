#!/usr/bin/env bash
set -euo pipefail

# standard 모드: DB + Outbox + Idempotency + Redis 재고 초기화
docker compose exec postgres psql -U lab -d booking_system -c \
  "UPDATE events SET reserved_count = 0, version = 0 WHERE id = 1;
   TRUNCATE reservation_outbox, idempotency_records, reservations;"

docker compose exec redis redis-cli SET event:1:remaining 100

docker compose exec postgres psql -U lab -d payment_db -c \
  "TRUNCATE payments, payment_outbox;" || true

echo "standard reset complete (DB + Redis inventory + payment_db)."
