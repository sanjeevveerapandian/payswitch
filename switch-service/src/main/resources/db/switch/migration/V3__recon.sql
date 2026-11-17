-- One reconciliation run per time window. The window IS the identity of the run: asking for the
-- same window again continues (or simply re-reports) the same run, it never starts a second one.
CREATE TABLE recon_runs (
    id          BIGSERIAL   PRIMARY KEY,
    window_from TIMESTAMPTZ NOT NULL,
    window_to   TIMESTAMPTZ NOT NULL,
    started_at  TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    status      VARCHAR(16) NOT NULL CHECK (status IN ('NEW', 'RUNNING', 'COMPLETED', 'PARTIAL')),
    counts      JSONB,
    -- While RUNNING, nobody else may run this window until lease_until. If the runner crashes,
    -- the lease runs out and the next run takes over where it stopped.
    lease_until TIMESTAMPTZ,
    CONSTRAINT uq_recon_window UNIQUE (window_from, window_to),
    CHECK (window_from < window_to)
);

-- One line per payment (or unknown bank txnId) looked at in a run. Written once; a rerun skips
-- every txn that already has a line, which is what makes reruns safe.
CREATE TABLE recon_items (
    id           BIGSERIAL    PRIMARY KEY,
    run_id       BIGINT       NOT NULL REFERENCES recon_runs (id),
    txn_id       VARCHAR(64)  NOT NULL,
    category     VARCHAR(20)  NOT NULL CHECK (category IN ('MATCHED', 'SWITCH_ONLY', 'BANK_ONLY',
                     'AMOUNT_MISMATCH', 'RESOLVED_DEEMED', 'DEEMED_UNRESOLVED', 'UNFINISHED')),
    switch_state VARCHAR(20),               -- NULL for BANK_ONLY: the switch has no such txn
    bank_view    VARCHAR(500) NOT NULL,     -- what each bank says about this txnId
    action_taken VARCHAR(100) NOT NULL,
    resolved_at  TIMESTAMPTZ,
    CONSTRAINT uq_recon_item UNIQUE (run_id, txn_id)
);

-- Reconciliation reads payments by creation time.
CREATE INDEX idx_transactions_created ON transactions (created_at);
