-- Phase 6: a REVERSE can now answer NO_DEBIT ("there was no debit; a later one is blocked").
-- The CHECK from V1 only allowed PENDING / SUCCESS / DECLINED, so widen it.
--
-- Numbered 101, not 3: every bank database has already applied its seed migration V100, and
-- Flyway refuses to run a migration numbered BELOW what a database has already reached.
ALTER TABLE processed_requests DROP CONSTRAINT processed_requests_status_check;
ALTER TABLE processed_requests ADD CONSTRAINT processed_requests_status_check
    CHECK (status IN ('PENDING', 'SUCCESS', 'DECLINED', 'NO_DEBIT'));
