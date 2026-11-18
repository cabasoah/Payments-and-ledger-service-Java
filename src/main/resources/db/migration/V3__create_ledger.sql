-- Double-entry ledger. See docs/DECISIONS.md for the chart of accounts and posting table.

CREATE TABLE ledger_accounts (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID,
    type        VARCHAR(32) NOT NULL,
    currency    CHAR(3)     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ledger_accounts_merchant_fk FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT ledger_accounts_type_valid CHECK (type IN ('PROCESSOR_RECEIVABLE', 'MERCHANT_PAYABLE')),
    CONSTRAINT ledger_accounts_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    -- Merchant accounts belong to a merchant; platform accounts do not.
    CONSTRAINT ledger_accounts_owner CHECK ((type = 'MERCHANT_PAYABLE') = (merchant_id IS NOT NULL)),
    CONSTRAINT ledger_accounts_unique UNIQUE NULLS NOT DISTINCT (type, merchant_id, currency),
    -- Target of ledger_entries' composite FK, so an entry's currency must match its account's.
    CONSTRAINT ledger_accounts_id_currency_unique UNIQUE (id, currency)
);

CREATE TABLE journal_transactions (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID        NOT NULL,
    kind       VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT journal_transactions_payment_fk FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT journal_transactions_kind_valid CHECK (kind IN ('CAPTURE', 'REFUND'))
);

CREATE INDEX journal_transactions_payment_id_idx ON journal_transactions (payment_id);

-- A payment is captured into the ledger at most once.
CREATE UNIQUE INDEX journal_transactions_one_capture_per_payment
    ON journal_transactions (payment_id) WHERE kind = 'CAPTURE';

CREATE TABLE ledger_entries (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_transaction_id UUID        NOT NULL,
    account_id             UUID        NOT NULL,
    direction              VARCHAR(6)  NOT NULL,
    amount_minor           BIGINT      NOT NULL,
    currency               CHAR(3)     NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ledger_entries_journal_fk
        FOREIGN KEY (journal_transaction_id) REFERENCES journal_transactions (id),
    CONSTRAINT ledger_entries_account_currency_fk
        FOREIGN KEY (account_id, currency) REFERENCES ledger_accounts (id, currency),
    CONSTRAINT ledger_entries_direction_valid CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entries_amount_positive CHECK (amount_minor > 0)
);

CREATE INDEX ledger_entries_journal_transaction_id_idx ON ledger_entries (journal_transaction_id);
CREATE INDEX ledger_entries_account_id_idx ON ledger_entries (account_id);

-- Append-only: corrections are new reversing entries, never edits.
CREATE FUNCTION ledger_reject_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger tables are append-only: % on % rejected', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();

-- TRUNCATE does not fire row triggers, so it needs its own statement trigger.
CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();

CREATE TRIGGER journal_transactions_append_only
    BEFORE UPDATE OR DELETE ON journal_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();

CREATE TRIGGER journal_transactions_no_truncate
    BEFORE TRUNCATE ON journal_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();
