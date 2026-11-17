CREATE TABLE merchants (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT merchants_name_not_blank CHECK (btrim(name) <> '')
);

-- Status transitions are enforced in Java (PaymentStatus); the database only checks valid values.
CREATE TABLE payments (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id    UUID         NOT NULL,
    amount_minor   BIGINT       NOT NULL,
    currency       CHAR(3)      NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    captured_minor BIGINT       NOT NULL DEFAULT 0,
    refunded_minor BIGINT       NOT NULL DEFAULT 0,
    processor_ref  VARCHAR(255),
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT payments_merchant_fk FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT payments_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT payments_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT payments_status_valid CHECK (status IN (
        'PENDING', 'AUTHORIZED', 'CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'FAILED')),
    CONSTRAINT payments_captured_range CHECK (captured_minor >= 0 AND captured_minor <= amount_minor),
    CONSTRAINT payments_refunded_range CHECK (refunded_minor >= 0 AND refunded_minor <= captured_minor),
    CONSTRAINT payments_processor_ref_unique UNIQUE (processor_ref)
);

CREATE INDEX payments_merchant_id_idx ON payments (merchant_id);
