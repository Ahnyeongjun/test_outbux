-- Outbox 패턴 DDL (메인 PostgreSQL 인스턴스, insusr 스키마)
CREATE SEQUENCE IF NOT EXISTS outbox_seq_seq START 1;

CREATE TABLE IF NOT EXISTS outbox (
    id          BIGSERIAL   PRIMARY KEY,
    seq         BIGINT      NOT NULL DEFAULT NEXTVAL('outbox_seq_seq'),
    domain      VARCHAR(50) NOT NULL,
    event_type  VARCHAR(20) NOT NULL,
    source      VARCHAR(20) NOT NULL,           -- INTERNAL | CLOSED_NET
    payload     JSONB,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    sent_at     TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_seq     ON outbox (status, seq ASC);
CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox (status, created_at);

-- 폐쇄망 수신측 중복 처리 (수신 서버에서 실행)
CREATE TABLE IF NOT EXISTS processed_seq (
    seq          BIGINT    PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL DEFAULT NOW()
);
