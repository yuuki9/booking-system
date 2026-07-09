CREATE TABLE payments (
    id              UUID PRIMARY KEY,
    reservation_id  UUID NOT NULL,
    event_id        BIGINT NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    amount          BIGINT NOT NULL,
    lock_strategy   VARCHAR(32) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    failure_reason  VARCHAR(32),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_payments_reservation UNIQUE (reservation_id)
);

CREATE TABLE payment_outbox (
    id              UUID PRIMARY KEY,
    payment_id      UUID NOT NULL,
    reservation_id  UUID NOT NULL,
    event_id        BIGINT NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    amount          BIGINT NOT NULL,
    lock_strategy   VARCHAR(32) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    failure_reason  VARCHAR(32),
    occurred_at     TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_outbox_unpublished ON payment_outbox (created_at)
    WHERE published_at IS NULL;
