-- One row per (merchant, Idempotency-Key). The row is written in the same transaction as the
-- business change, so a committed row is always COMPLETED; IN_PROGRESS exists only inside the
-- open transaction. No FK to merchants: an unknown merchant rolls back with 404 anyway.
CREATE TABLE idempotency_keys (
    merchant_id     UUID         NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT idempotency_keys_pk PRIMARY KEY (merchant_id, idempotency_key),
    CONSTRAINT idempotency_keys_key_not_blank CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT idempotency_keys_status_valid CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT idempotency_keys_completed_has_response CHECK (
        status <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL)),
    CONSTRAINT idempotency_keys_expiry_after_creation CHECK (expires_at > created_at)
);

CREATE INDEX idempotency_keys_expires_at_idx ON idempotency_keys (expires_at);
