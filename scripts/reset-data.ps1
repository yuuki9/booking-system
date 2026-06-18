docker compose exec postgres psql -U lab -d booking_system -c "UPDATE events SET reserved_count = 0, version = 0 WHERE id = 1; TRUNCATE reservation_outbox, idempotency_records, reservations;"
docker compose exec redis redis-cli SET event:1:remaining 100
Write-Host "Phase B reset complete (DB + Redis inventory)."
