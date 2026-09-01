CREATE TABLE payments (
    id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT chk_payments_status CHECK (status IN ('READY', 'AUTH', 'APPROVED'))
);
