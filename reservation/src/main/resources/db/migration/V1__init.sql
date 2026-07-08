CREATE TABLE events (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    capacity        INT NOT NULL,
    reserved_count  INT NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE reservations (
    id          UUID PRIMARY KEY,
    event_id    BIGINT NOT NULL REFERENCES events(id),
    user_id     VARCHAR(255) NOT NULL,
    status      VARCHAR(32) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_reservations_event_id ON reservations(event_id);
