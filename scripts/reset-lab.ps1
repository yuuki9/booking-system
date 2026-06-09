docker compose exec postgres psql -U lab -d reservation_lab -c "UPDATE events SET reserved_count = 0, version = 0 WHERE id = 1; TRUNCATE reservations;"
Write-Host "Lab data reset complete."
