-- JPA E2E 테스트용 outbox 테이블 (Hibernate ddl-auto 가 만들지 않는 부분).
-- product 테이블은 @Entity 정의로 Hibernate 가 자동 생성.

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
