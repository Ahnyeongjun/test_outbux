-- E2E 테스트용 H2 스키마.
-- Spring Boot DataSource 초기화 단계에서 실행되어 @Scheduled 스케줄러 첫 실행 전에 outbox 테이블 존재 보장.

CREATE TABLE IF NOT EXISTS users (
    id   BIGINT      PRIMARY KEY,
    name VARCHAR(50)
);

CREATE TABLE IF NOT EXISTS outbox (
    id         BIGINT      AUTO_INCREMENT PRIMARY KEY,
    seq        BIGINT,
    domain     VARCHAR(50) NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    source     VARCHAR(20) NOT NULL,
    payload    CLOB,
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at    TIMESTAMP
);
