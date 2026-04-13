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
     * Outbox ??ê¸°ë¡???Œì´ë¸?ëª©ë¡.
     * Debezium debezium.source.table.include.list ?€ ?™ì¼?˜ê²Œ ? ì???ê²?
     * ?¤í‚¤ë§?prefix(insusr.) ?†ì´ ?Œì´ë¸”ëª…ë§?ê¸°ì….
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
