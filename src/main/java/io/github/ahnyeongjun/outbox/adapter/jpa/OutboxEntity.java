package io.github.ahnyeongjun.outbox.adapter.jpa;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA 어댑터용 outbox 엔티티.
 *
 * <p>{@code id} 가 시퀀스 역할을 겸한다 — JDBC 어댑터 PG 방언이 별도 {@code seq} 컬럼을
 * 사용하는 것과 달리, JPA 어댑터에서는 단순성을 위해 id = seq 모델을 따른다.
 *
 * <p>스키마 예시 — JPA 사용 시 별도 {@code seq} 컬럼 불필요:
 * <pre>
 * -- PostgreSQL
 * CREATE TABLE outbox (
 *     id         BIGSERIAL    PRIMARY KEY,
 *     domain     VARCHAR(50)  NOT NULL,
 *     event_type VARCHAR(20)  NOT NULL,
 *     source     VARCHAR(20)  NOT NULL,
 *     payload    TEXT,                  -- 또는 JSONB (별도 타입 매핑 필요)
 *     status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
 *     created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
 *     sent_at    TIMESTAMP
 * );
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "outbox")
public class OutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String domain;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    @Column(nullable = false, length = 20)
    private String source;

    /**
     * JSON 직렬화된 페이로드. PostgreSQL 의 JSONB 컬럼을 쓰려면 별도 Hibernate 타입 매핑이 필요.
     * 기본은 텍스트로 취급해 어떤 DB 든 호환된다.
     */
    @Column
    private String payload;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @PrePersist
    void prePersist() {
        if (status == null) status = "PENDING";
        if (createdAt == null) createdAt = Instant.now();
    }
}
