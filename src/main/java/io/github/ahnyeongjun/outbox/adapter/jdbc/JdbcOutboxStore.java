package io.github.ahnyeongjun.outbox.adapter.jdbc;

import java.sql.Timestamp;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.ahnyeongjun.outbox.model.Outbox;
import io.github.ahnyeongjun.outbox.spi.OutboxStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * JDBC 기반 {@link OutboxStore} 구현체 — 본 모듈의 단일 표준 저장소.
 *
 * <p>락 전략: {@code SELECT ... FOR UPDATE SKIP LOCKED} (PostgreSQL/MySQL 공통).
 * 락 메커니즘은 외부에 노출되지 않으며 {@link #processBatch} 가 트랜잭션 안에서 모두 처리한다.
 *
 * <p><b>JPA/Hibernate 프로젝트에서도 그대로 사용</b>: {@code JdbcTemplate} 은 활성 트랜잭션의
 * Connection 을 공유하므로 {@code JpaTransactionManager} 가 관리하는 비즈니스 트랜잭션에
 * 자연스럽게 합류한다. JPA 비즈니스 INSERT 와 outbox INSERT 가 같은 커밋 단위로 묶여 원자적.
 */
@Slf4j
@RequiredArgsConstructor
public class JdbcOutboxStore implements OutboxStore {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final OutboxDialect dialect;

    private static final RowMapper<Outbox> ROW_MAPPER = (rs, rowNum) -> {
        long rawSeq = rs.getLong("seq");
        Long seq = rs.wasNull() ? null : rawSeq;
        Timestamp sentAt = rs.getTimestamp("sent_at");
        return Outbox.builder()
                .id(rs.getLong("id"))
                .seq(seq)
                .domain(rs.getString("domain"))
                .eventType(rs.getString("event_type"))
                .source(rs.getString("source"))
                .payload(rs.getString("payload"))
                .status(rs.getString("status"))
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .sentAt(sentAt != null ? sentAt.toInstant() : null)
                .build();
    };

    @Override
    @Transactional
    public void saveAll(List<Outbox> events) {
        SqlParameterSource[] params = events.stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("domain", e.getDomain())
                        .addValue("eventType", e.getEventType())
                        .addValue("source", e.getSource())
                        .addValue("payload", e.getPayload()))
                .toArray(SqlParameterSource[]::new);
        jdbcTemplate.batchUpdate(dialect.insertSql(), params);
    }

    @Override
    public long countPending() {
        Long count = jdbcTemplate.getJdbcTemplate()
                .queryForObject(dialect.countPendingSql(), Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public int processBatch(TransactionTemplate tx, int limit, Consumer<List<Outbox>> handler) {
        Integer count = tx.execute(status -> {
            List<Outbox> batch = findPendingWithLock(limit);
            if (batch.isEmpty()) return 0;
            try {
                handler.accept(batch);
                markSent(batch);
            } catch (RuntimeException e) {
                log.error("Outbox processBatch handler failed: {}", e.getMessage(), e);
                batch.forEach(o -> markFailed(o.getId()));
            }
            return batch.size();
        });
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public void deleteOldSent() {
        jdbcTemplate.getJdbcTemplate().update(dialect.deleteOldSentSql());
    }

    // -------------------- 내부 (락 기제) — SPI 노출 안 함 --------------------

    private List<Outbox> findPendingWithLock(int limit) {
        return jdbcTemplate.query(
                dialect.selectPendingWithLockSql(),
                new MapSqlParameterSource("limit", limit),
                ROW_MAPPER);
    }

    private void markSent(List<Outbox> batch) {
        List<Long> ids = batch.stream().map(Outbox::getId).collect(Collectors.toList());
        jdbcTemplate.update(dialect.markSentSql(), new MapSqlParameterSource("ids", ids));
    }

    private void markFailed(Long id) {
        jdbcTemplate.update(dialect.markFailedSql(), new MapSqlParameterSource("id", id));
    }
}
