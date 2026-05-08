package io.github.ahnyeongjun.outbox.adapter.jdbc;

import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.annotation.Transactional;

import io.github.ahnyeongjun.outbox.model.Outbox;
import lombok.RequiredArgsConstructor;

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
    public List<Outbox> findPendingWithLock(int limit) {
        return jdbcTemplate.query(
                dialect.selectPendingWithLockSql(),
                new MapSqlParameterSource("limit", limit),
                ROW_MAPPER);
    }

    @Override
    @Transactional
    public void markSent(List<Outbox> batch) {
        List<Long> ids = batch.stream().map(Outbox::getId).collect(Collectors.toList());
        jdbcTemplate.update(dialect.markSentSql(), new MapSqlParameterSource("ids", ids));
    }

    @Override
    @Transactional
    public void markFailed(Long id) {
        jdbcTemplate.update(dialect.markFailedSql(), new MapSqlParameterSource("id", id));
    }

    @Override
    @Transactional
    public void deleteOldSent() {
        jdbcTemplate.getJdbcTemplate().update(dialect.deleteOldSentSql());
    }
}
