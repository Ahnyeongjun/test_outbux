package io.github.ahnyeongjun.outbox.config;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {

    private File file = new File();
    private Batch batch = new Batch();

    /**
     * Outbox 에 기록할 테이블 목록.
     * 스키마 prefix 없이 테이블명만 기입.
     */
    private Set<String> tables = Set.of();

    /**
     * SQL 방언. 비워두면 DataSource URL 에서 자동 감지(postgresql/mysql/mariadb).
     * 명시 시 우선 적용 — postgresql | mysql | mariadb 중 택일.
     */
    private String dialect;

    /** PostgreSQL 전용 시퀀스 이름 */
    private String sequenceName = "outbox_seq_seq";

    @Getter
    @Setter
    public static class File {
        private String path = "D:/files/outbox";
    }

    @Getter
    @Setter
    public static class Batch {
        private int size = 1000;
        private long timeTriggerMs = 60_000;
        private long checkIntervalMs = 5_000;
    }
}
