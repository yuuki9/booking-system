ALTER TABLE reservation_outbox
    ADD COLUMN payment_id UUID;

CREATE UNIQUE INDEX idx_reservation_outbox_refund_once
    ON reservation_outbox (reservation_id)
    WHERE event_type = 'REFUND_REQUESTED';
