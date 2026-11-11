-- Every amount is BIGINT paise. Raw balances are debit-positive (see ledger-core AccountType).

CREATE TABLE accounts (
    id             VARCHAR(64)  PRIMARY KEY,
    vpa            VARCHAR(64)  UNIQUE,          -- NULL for internal accounts
    name           VARCHAR(100) NOT NULL,
    type           VARCHAR(16)  NOT NULL CHECK (type IN ('ASSET', 'LIABILITY')),
    allow_negative BOOLEAN      NOT NULL,
    raw_balance    BIGINT       NOT NULL DEFAULT 0,
    -- Safety net. Java checks first; if a bug ever slips past, the database still refuses.
    CONSTRAINT no_negative_balance CHECK (
        allow_negative
        OR (type = 'LIABILITY' AND raw_balance <= 0)
        OR (type = 'ASSET'     AND raw_balance >= 0)
    )
);

CREATE TABLE journal_entries (
    id          VARCHAR(64)  PRIMARY KEY,
    txn_id      VARCHAR(64)  NOT NULL,
    op_type     VARCHAR(16)  NOT NULL,
    description VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Second safety net: one money movement per (txnId, op), even if idempotency had a bug.
    CONSTRAINT uq_journal_txn_op UNIQUE (txn_id, op_type)
);

CREATE TABLE entry_lines (
    id               BIGSERIAL   PRIMARY KEY,
    journal_entry_id VARCHAR(64) NOT NULL REFERENCES journal_entries (id),
    account_id       VARCHAR(64) NOT NULL REFERENCES accounts (id),
    amount           BIGINT      NOT NULL CHECK (amount <> 0)
);
CREATE INDEX idx_entry_lines_entry   ON entry_lines (journal_entry_id);
CREATE INDEX idx_entry_lines_account ON entry_lines (account_id);

-- The idempotency table: one row per (txnId, op) this bank has ever handled.
CREATE TABLE processed_requests (
    id         BIGSERIAL   PRIMARY KEY,
    txn_id     VARCHAR(64) NOT NULL,
    op_type    VARCHAR(16) NOT NULL,
    status     VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'SUCCESS', 'DECLINED')),
    response   TEXT,                              -- the exact JSON we answered with
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_processed_txn_op UNIQUE (txn_id, op_type)
);
