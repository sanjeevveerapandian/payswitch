-- Phase 9: multilateral net settlement.

-- A settlement cycle: OPEN (collecting) -> CLOSED (positions fixed) -> SETTLED (money moved).
-- FAILED: the positions did not add up to zero, so nothing was settled.
CREATE TABLE settlement_cycles (
    id             BIGSERIAL    PRIMARY KEY,
    opened_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at      TIMESTAMPTZ,
    settled_at     TIMESTAMPTZ,
    status         VARCHAR(16)  NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'SETTLED', 'FAILED')),
    failure_reason VARCHAR(200)
);
-- At most ONE open cycle, enforced by the database itself.
CREATE UNIQUE INDEX uq_one_open_cycle ON settlement_cycles ((TRUE)) WHERE status = 'OPEN';
INSERT INTO settlement_cycles (status) VALUES ('OPEN');

-- The payments a cycle settles. active = FALSE when the cycle FAILED: its payments are released
-- and roll into the next cycle.
CREATE TABLE settlement_items (
    cycle_id   BIGINT      NOT NULL REFERENCES settlement_cycles (id),
    txn_id     VARCHAR(64) NOT NULL REFERENCES transactions (txn_id),
    payer_bank VARCHAR(16) NOT NULL,
    payee_bank VARCHAR(16) NOT NULL,
    amount     BIGINT      NOT NULL CHECK (amount > 0),
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    PRIMARY KEY (cycle_id, txn_id)
);
-- Safety net: a payment can be in at most one live cycle, so it can never be settled twice.
CREATE UNIQUE INDEX uq_settlement_txn_once ON settlement_items (txn_id) WHERE active;

-- Each bank's position in a cycle: receives minus pays. advised_at: when the bank confirmed it.
CREATE TABLE net_positions (
    cycle_id   BIGINT      NOT NULL REFERENCES settlement_cycles (id),
    bank_code  VARCHAR(16) NOT NULL,
    net_amount BIGINT      NOT NULL,
    advised_at TIMESTAMPTZ,
    PRIMARY KEY (cycle_id, bank_code)
);

-- "The latest recon line for this payment" is looked up by txn_id.
CREATE INDEX idx_recon_items_txn ON recon_items (txn_id, id);

-- The central bank's ledger: the same double-entry tables as a bank's, kept by ledger-core rules.
-- One account per bank: RESERVE-BANKA = the money Bank A holds at the central bank.
CREATE TABLE central_accounts (
    id             VARCHAR(64)  PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    type           VARCHAR(16)  NOT NULL CHECK (type IN ('ASSET', 'LIABILITY')),
    allow_negative BOOLEAN      NOT NULL,
    raw_balance    BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE central_journal_entries (
    id          VARCHAR(64)  PRIMARY KEY,
    -- Second safety net: ONE settlement entry per cycle, ever.
    cycle_id    BIGINT       NOT NULL UNIQUE REFERENCES settlement_cycles (id),
    description VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE central_entry_lines (
    id               BIGSERIAL   PRIMARY KEY,
    journal_entry_id VARCHAR(64) NOT NULL REFERENCES central_journal_entries (id),
    account_id       VARCHAR(64) NOT NULL REFERENCES central_accounts (id),
    amount           BIGINT      NOT NULL CHECK (amount <> 0)
);
CREATE INDEX idx_central_lines_entry ON central_entry_lines (journal_entry_id);
