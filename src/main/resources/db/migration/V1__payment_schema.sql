CREATE TABLE payments (
    id           BIGINT                   NOT NULL,
    order_id     BIGINT                   NOT NULL,
    total_amount NUMERIC(18, 4)           NOT NULL,
    currency     VARCHAR(10)              NOT NULL,
    method       VARCHAR(30)              NOT NULL,
    status       VARCHAR(30)              NOT NULL,
    version      BIGINT                   NOT NULL DEFAULT 0,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_payments PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uq_payments_order_id_active
    ON payments (order_id)
    WHERE status IN ('PENDING', 'REVIEW_REQUIRED');
CREATE INDEX idx_payments_order_id ON payments (order_id);

CREATE TABLE transactions (
    id                      BIGINT                   NOT NULL,
    payment_id              BIGINT                   NOT NULL,
    provider_transaction_id VARCHAR(100),
    provider_response_code  VARCHAR(20),
    checkout_url            TEXT,
    amount                  NUMERIC(18, 4)           NOT NULL,
    currency                VARCHAR(10)              NOT NULL,
    type                    VARCHAR(30)              NOT NULL,
    status                  VARCHAR(30)              NOT NULL,
    failure_reason          TEXT,
    version                 BIGINT                   NOT NULL DEFAULT 0,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    expired_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_transactions PRIMARY KEY (id),
    CONSTRAINT fk_transactions_payment FOREIGN KEY (payment_id) REFERENCES payments (id)
);

CREATE INDEX idx_transactions_payment_id ON transactions (payment_id);
CREATE UNIQUE INDEX uq_transactions_pending_charge
    ON transactions (payment_id)
    WHERE type = 'CHARGE' AND status = 'PENDING';
CREATE UNIQUE INDEX uq_transactions_provider_id
    ON transactions (provider_transaction_id)
    WHERE provider_transaction_id IS NOT NULL;

CREATE TABLE idempotency_keys (
    id             BIGINT                   NOT NULL,
    scope          VARCHAR(50)              NOT NULL,
    key_hash       VARCHAR(64)              NOT NULL,
    request_hash   VARCHAR(64)              NOT NULL,
    state          VARCHAR(20)              NOT NULL,
    payment_id     BIGINT,
    expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (id),
    CONSTRAINT fk_idempotency_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT uq_idempotency_scope_key UNIQUE (scope, key_hash)
);

CREATE INDEX idx_idempotency_expiry ON idempotency_keys (expires_at);
