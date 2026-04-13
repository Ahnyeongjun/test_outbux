package io.github.ahnyeongjun.outbox.context;

import java.util.ArrayList;
import java.util.List;

import io.github.ahnyeongjun.outbox.model.Outbox;
import lombok.Getter;

@Getter
public class OutboxContextData {

    private final List<Outbox> pendingEvents = new ArrayList<>();
    private boolean syncRegistered = false;
    /** ?μ‡„λ§??μ‹  ?μ© ??suppress() λ΅??¤μ • ???Έν„°?‰ν„° μΊ΅μ² μ°¨λ‹¨ */
    private boolean suppressed = false;

    public void addEvent(Outbox outbox) {
        pendingEvents.add(outbox);
    }

    public void markSyncRegistered() {
        this.syncRegistered = true;
    }

    public void suppress() {
        this.suppressed = true;
    }

    public boolean hasPendingEvents() {
        return !pendingEvents.isEmpty();
    }
}
