-- Bank C customers. Kumar Rs 4,000 = 400000 paise. Priya Rs 2,500 = 250000 paise.
INSERT INTO accounts (id, vpa, name, type, allow_negative, raw_balance) VALUES
    ('ACC-KUMAR', 'kumar@bankc', 'Kumar', 'LIABILITY', FALSE, -400000),
    ('ACC-PRIYA', 'priya@bankc', 'Priya', 'LIABILITY', FALSE, -250000);

UPDATE accounts SET raw_balance = raw_balance + 650000 WHERE id = 'BANK_CASH';

INSERT INTO journal_entries (id, txn_id, op_type, description) VALUES
    ('SEED-KUMAR', 'SEED-KUMAR', 'OPENING', 'Opening cash deposit'),
    ('SEED-PRIYA', 'SEED-PRIYA', 'OPENING', 'Opening cash deposit');

INSERT INTO entry_lines (journal_entry_id, account_id, amount) VALUES
    ('SEED-KUMAR', 'BANK_CASH',  400000), ('SEED-KUMAR', 'ACC-KUMAR', -400000),
    ('SEED-PRIYA', 'BANK_CASH',  250000), ('SEED-PRIYA', 'ACC-PRIYA', -250000);
