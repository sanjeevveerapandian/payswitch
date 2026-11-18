-- Phase 9: settlement.
--
-- CENTRAL_BANK_RESERVE: this bank's money held at the central bank (an ASSET of the bank).
--   Settlement moves real money between banks by moving these reserves.
--   It starts at 0 here and may go negative: in this simulator the central bank lets a bank
--   run an overdraft. (A real central bank would demand pre-funding or collateral.)
INSERT INTO accounts (id, vpa, name, type, allow_negative, raw_balance) VALUES
    ('CENTRAL_BANK_RESERVE', NULL, 'Reserve at the central bank', 'ASSET', TRUE, 0);

-- One row per settlement cycle this bank has applied. The primary key IS the idempotency:
-- the same cycle can never be applied twice.
CREATE TABLE settlement_advices (
    cycle_id   BIGINT      PRIMARY KEY,
    net_amount BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
