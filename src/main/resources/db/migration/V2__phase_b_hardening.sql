-- Phase B: 현업 1스텝 하드닝
-- 1) 동일 사용자 중복 예약 방지
-- 2) Kafka Outbox (DB 커밋과 이벤트 발행 분리)
-- 3) 멱등성 키 저장 (X-Idempotency-Key 재전송 대응)

ALTER TABLE reservations
    ADD CONSTRAINT uk_reservations_event_user UNIQUE (event_id, user_id);

CREATE TABLE reservation_outbox (
    id              UUID PRIMARY KEY,
    reservation_id  UUID NOT NULL,
    event_id        BIGINT NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    lock_strategy   VARCHAR(32) NOT NULL,
    confirmed_at    TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_unpublished ON reservation_outbox (created_at)
    WHERE published_at IS NULL;

CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    reservation_id  UUID NOT NULL REFERENCES reservations (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
