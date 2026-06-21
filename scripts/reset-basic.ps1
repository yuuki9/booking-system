$ErrorActionPreference = "Stop"

docker compose exec postgres psql -U lab -d booking_system -c `
  "UPDATE events SET reserved_count = 0, version = 0 WHERE id = 1;
   TRUNCATE reservations;"

Write-Host "basic reset complete (DB reservations)."
