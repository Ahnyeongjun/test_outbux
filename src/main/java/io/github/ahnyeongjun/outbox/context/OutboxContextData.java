package io.github.ahnyeongjun.outbox.context;

import java.util.ArrayList;
import java.util.List;

import io.github.ahnyeongjun.outbox.model.Outbox;
import lombok.Getter;

@Getter
public class OutboxContextData {

    private final List<Outbox> pendingEvents = new ArrayList<>();
    private boolean syncRegistered = false;
    private boolean suppressed = false;
    private String customEventType = null;

    public void addEvent(Outbox outbox) {
        pendingEvents.add(outbox);
    }

    public void markSyncRegistered() {
        this.syncRegistered = true;
    }

    public void suppress() {
        this.suppressed = true;
    }

    public void unsuppress() {
        this.suppressed = false;
    }

    public void setCustomEventType(String eventType) {
        this.customEventType = eventType;
    }

    public void clearCustomEventType() {
        this.customEventType = null;
    }

    public boolean hasCustomEventType() {
        return customEventType != null && !customEventType.isEmpty();
    }

    public String getCustomEventType() {
        return customEventType;
    }

    public boolean hasPendingEvents() {
        return !pendingEvents.isEmpty();
    }
}
