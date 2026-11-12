-- Two internal accounts every bank has.
--
-- SWITCH_SETTLEMENT: what this bank owes the switch (positive) or is owed by it (negative).
--   Ravi pays out  -> money moves Ravi -> SWITCH_SETTLEMENT (bank now owes the switch).
--   Anu gets paid  -> money moves SWITCH_SETTLEMENT -> Anu (switch now owes the bank).
--   It legitimately swings both ways during the day, so allow_negative = true.
--
-- BANK_CASH: real cash customers deposited. The other side of every opening deposit.
INSERT INTO accounts (id, vpa, name, type, allow_negative, raw_balance) VALUES
    ('SWITCH_SETTLEMENT', NULL, 'Settlement with the switch', 'LIABILITY', TRUE,  0),
    ('BANK_CASH',         NULL, 'Bank cash and reserves',     'ASSET',     FALSE, 0);
