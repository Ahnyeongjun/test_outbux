package io.github.ahnyeongjun.outbox.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.github.ahnyeongjun.outbox.model.Outbox;

@Mapper
public interface OutboxMapper {
    void insert(Outbox outbox);
    void batchInsert(@Param("list") List<Outbox> list);
    long countPending();
    List<Outbox> findPendingWithLock(@Param("limit") int limit);
    void markSent(@Param("ids") List<Long> ids);
    void markFailed(@Param("id") Long id);
    void deleteOldSent();
}
