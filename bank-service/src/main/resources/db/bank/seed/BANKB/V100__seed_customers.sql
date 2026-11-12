-- Bank B customers. Anu's shop Rs 1,000 = 100000 paise. Kiran Rs 3,000 = 300000 paise.
INSERT INTO accounts (id, vpa, name, type, allow_negative, raw_balance) VALUES
    ('ACC-ANU',   'anu@bankb',   'Anu''s shop', 'LIABILITY', FALSE, -100000),
    ('ACC-KIRAN', 'kiran@bankb', 'Kiran',       'LIABILITY', FALSE, -300000);

UPDATE accounts SET raw_balance = raw_balance + 400000 WHERE id = 'BANK_CASH';

INSERT INTO journal_entries (id, txn_id, op_type, description) VALUES
    ('SEED-ANU',   'SEED-ANU',   'OPENING', 'Opening cash deposit'),
    ('SEED-KIRAN', 'SEED-KIRAN', 'OPENING', 'Opening cash deposit');

INSERT INTO entry_lines (journal_entry_id, account_id, amount) VALUES
    ('SEED-ANU',   'BANK_CASH',  100000), ('SEED-ANU',   'ACC-ANU',   -100000),
    ('SEED-KIRAN', 'BANK_CASH',  300000), ('SEED-KIRAN', 'ACC-KIRAN', -300000);
