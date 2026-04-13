package io.github.ahnyeongjun.outbox.repository;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.ahnyeongjun.outbox.mapper.OutboxMapper;
import io.github.ahnyeongjun.outbox.model.Outbox;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class OutboxRepository {

    private final OutboxMapper outboxMapper;

    @Transactional
    public void save(Outbox outbox) {
        outboxMapper.insert(outbox);
    }

    /** ?∏Îûú??Öò beforeCommit() ?êÏÑú ?ºÍ¥Ñ ?Ä??*/
    @Transactional
    public void saveAll(List<Outbox> events) {
        outboxMapper.batchInsert(events);
    }

    public long countPending() {
        return outboxMapper.countPending();
    }

    public List<Outbox> findPendingWithLock(int limit) {
        return outboxMapper.findPendingWithLock(limit);
    }

    @Transactional
    public void markSent(List<Outbox> batch) {
        List<Long> ids = batch.stream().map(Outbox::getId).collect(Collectors.toList());
        outboxMapper.markSent(ids);
    }

    @Transactional
    public void markFailed(Long id) {
        outboxMapper.markFailed(id);
    }

    @Transactional
    public void deleteOldSent() {
        outboxMapper.deleteOldSent();
    }
}
