ALTER TABLE transactions
    ADD COLUMN reconciliation_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_reconciliation_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_reconciliation_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_reconciliation_code VARCHAR(20),
    ADD COLUMN reconciliation_exhausted BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE transactions
SET next_reconciliation_at = expired_at
WHERE status = 'PENDING'
  AND type = 'CHARGE';

CREATE INDEX idx_transactions_reconciliation_due
    ON transactions (status, next_reconciliation_at)
    WHERE type = 'CHARGE'
      AND status = 'PENDING'
      AND reconciliation_exhausted = FALSE;
