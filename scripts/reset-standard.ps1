$ErrorActionPreference = "Stop"

docker compose exec postgres psql -U lab -d booking_system -c `
  "UPDATE events SET reserved_count = 0, version = 0 WHERE id = 1;
   TRUNCATE reservation_outbox, idempotency_records, reservations;"

docker compose exec redis redis-cli SET event:1:remaining 100

docker compose exec postgres psql -U lab -d payment_db -c "TRUNCATE payments, payment_outbox;" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "payment_db reset skipped (database or tables may not exist yet)"
}

Write-Host "standard reset complete (DB + Redis inventory + payment_db)."
