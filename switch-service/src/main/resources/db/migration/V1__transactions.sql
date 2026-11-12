-- transactions: ONE row per payment, holding its CURRENT state.
CREATE TABLE transactions (
    txn_id     VARCHAR(64) PRIMARY KEY,
    payer_vpa  VARCHAR(64) NOT NULL,
    payee_vpa  VARCHAR(64) NOT NULL,
    payer_bank VARCHAR(16),                      -- NULL when the handle is unknown
    payee_bank VARCHAR(16),
    amount     BIGINT      NOT NULL CHECK (amount > 0),   -- paise
    state      VARCHAR(20) NOT NULL CHECK (state IN (
                   'INITIATED', 'DEBIT_SENT', 'DEBITED', 'CREDIT_SENT', 'SUCCESS', 'FAILED',
                   'REVERSAL_SENT', 'REVERSED', 'DEEMED_APPROVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Optimistic locking: +1 on every state change. An UPDATE only succeeds if the
    -- version is still the one the writer read.
    version    BIGINT      NOT NULL DEFAULT 0
);

-- txn_events: MANY rows per payment, one per state change. Insert only: the audit trail.
CREATE TABLE txn_events (
    id         BIGSERIAL    PRIMARY KEY,
    txn_id     VARCHAR(64)  NOT NULL REFERENCES transactions (txn_id),
    from_state VARCHAR(20),                      -- NULL for the creation event
    to_state   VARCHAR(20)  NOT NULL,
    reason     VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_txn_events_txn ON txn_events (txn_id, id);
