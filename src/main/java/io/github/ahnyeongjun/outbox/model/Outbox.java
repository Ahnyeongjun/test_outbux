package io.github.ahnyeongjun.outbox.model;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Outbox {
    private Long id;
    private Long seq;
    private String domain;
    private String eventType;
    /** INTERNAL | CLOSED_NET */
    private String source;
    private String payload;
    @Builder.Default
    private String status = OutboxStatus.PENDING.name();
    private Instant createdAt;
    private Instant sentAt;
}
