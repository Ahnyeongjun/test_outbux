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
     * Debezium debezium.source.table.include.list 와 동일하게 맞출 것.
     * 스키마 prefix(insusr.) 없이 테이블명만 기입.
     */
    private Set<String> tables = Set.of();

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
