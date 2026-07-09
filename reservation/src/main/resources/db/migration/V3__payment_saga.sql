ALTER TABLE events
    ADD COLUMN price BIGINT NOT NULL DEFAULT 10000;

ALTER TABLE reservation_outbox
    ADD COLUMN event_type VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED',
    ADD COLUMN amount BIGINT;

CREATE INDEX idx_reservations_pending_created
    ON reservations (created_at)
    WHERE status = 'PENDING_PAYMENT';
