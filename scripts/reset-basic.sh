#!/usr/bin/env bash
set -euo pipefail

# basic 모드: 락 Handler 동작 실험용 (DB 예약·이벤트 카운터만 리셋)
docker compose exec postgres psql -U lab -d booking_system -c \
  "UPDATE events SET reserved_count = 0, version = 0 WHERE id = 1;
   TRUNCATE reservations;"

echo "basic reset complete (DB reservations)."
