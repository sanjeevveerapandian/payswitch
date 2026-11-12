-- Bank A customers. Each opening balance is a real journal entry: debit BANK_CASH, credit customer.
-- Ravi Rs 5,000 = 500000 paise. Meena Rs 2,000 = 200000 paise.
INSERT INTO accounts (id, vpa, name, type, allow_negative, raw_balance) VALUES
    ('ACC-RAVI',  'ravi@banka',  'Ravi',  'LIABILITY', FALSE, -500000),
    ('ACC-MEENA', 'meena@banka', 'Meena', 'LIABILITY', FALSE, -200000);

UPDATE accounts SET raw_balance = raw_balance + 700000 WHERE id = 'BANK_CASH';

INSERT INTO journal_entries (id, txn_id, op_type, description) VALUES
    ('SEED-RAVI',  'SEED-RAVI',  'OPENING', 'Opening cash deposit'),
    ('SEED-MEENA', 'SEED-MEENA', 'OPENING', 'Opening cash deposit');

INSERT INTO entry_lines (journal_entry_id, account_id, amount) VALUES
    ('SEED-RAVI',  'BANK_CASH',  500000), ('SEED-RAVI',  'ACC-RAVI',  -500000),
    ('SEED-MEENA', 'BANK_CASH',  200000), ('SEED-MEENA', 'ACC-MEENA', -200000);
