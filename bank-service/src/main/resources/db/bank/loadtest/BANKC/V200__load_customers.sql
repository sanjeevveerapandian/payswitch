-- Phase 10 load test: 300 customers at this bank, cust001@bankc .. cust300@bankc.
--   Every 20th customer (cust020, cust040, ...) is "poor": opening balance Rs 500 (50000 paise).
--   Everyone else opens with Rs 2,00,000 (20000000 paise).
-- The load generator (LoadCustomers.java) uses exactly this rule, so it knows who exists.
--
-- Each opening balance is a real journal entry, like the hand-written seeds: debit BANK_CASH,
-- credit the customer. So the books balance from the very first row.
--
-- Numbered 200 so it always runs after every normal migration. Only used with the "loadtest"
-- profile; a database that has it must keep using that profile (Flyway checks applied migrations).

INSERT INTO accounts (id, vpa, name, type, allow_negative, raw_balance)
SELECT 'ACC-LOAD-' || lpad(i::text, 3, '0'),
       'cust' || lpad(i::text, 3, '0') || '@bankc',
       'Load customer ' || i,
       'LIABILITY', FALSE,
       -(CASE WHEN i % 20 = 0 THEN 50000::bigint ELSE 20000000::bigint END)
FROM generate_series(1, 300) AS i;

INSERT INTO journal_entries (id, txn_id, op_type, description)
SELECT 'SEED-LOAD-' || lpad(i::text, 3, '0'), 'SEED-LOAD-' || lpad(i::text, 3, '0'), 'OPENING', 'Opening cash deposit'
FROM generate_series(1, 300) AS i;

INSERT INTO entry_lines (journal_entry_id, account_id, amount)
SELECT 'SEED-LOAD-' || lpad(i::text, 3, '0'), line.account_id, line.amount
FROM generate_series(1, 300) AS i,
     LATERAL (VALUES
         ('BANK_CASH',                          (CASE WHEN i % 20 = 0 THEN 50000::bigint ELSE 20000000::bigint END)),
         ('ACC-LOAD-' || lpad(i::text, 3, '0'), -(CASE WHEN i % 20 = 0 THEN 50000::bigint ELSE 20000000::bigint END))
     ) AS line (account_id, amount);

-- BANK_CASH went up by every deposit: 15 x 50000 + 285 x 20000000 = 5,700,750,000 paise.
-- BIGINT on purpose: 285 * 20000000 does not fit in a 32-bit INTEGER (max ~2.1 billion), and
-- Postgres does plain-number arithmetic in INTEGER unless told otherwise.
UPDATE accounts SET raw_balance = raw_balance + (15 * 50000::bigint + 285 * 20000000::bigint) WHERE id = 'BANK_CASH';
