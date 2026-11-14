-- The background resolver "leases" a stuck payment before working on it: until next_attempt_at,
-- no other resolver (another thread, or another copy of the switch) will pick it up.
-- It is a separate column on purpose: claiming must NOT bump the optimistic-lock version.
ALTER TABLE transactions ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- The resolver's query: "unfinished payments, oldest first".
CREATE INDEX idx_transactions_stuck ON transactions (state, updated_at);
