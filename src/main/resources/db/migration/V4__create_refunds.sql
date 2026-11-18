CREATE TABLE refunds (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id   UUID        NOT NULL,
    amount_minor BIGINT      NOT NULL,
    currency     CHAR(3)     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT refunds_payment_fk FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT refunds_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT refunds_currency_format CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX refunds_payment_id_idx ON refunds (payment_id);
