package io.github.ahnyeongjun.outboxtest;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 클래스명 "UsersMapper" → MyBatis 인터셉터의 네임스페이스 → 테이블명 변환 규칙에 따라
 * "users" 테이블로 인식된다. {@code outbox.tables=users} 와 매칭.
 */
@Mapper
public interface UsersMapper {

    @Insert("INSERT INTO users (id, name) VALUES (#{id}, #{name})")
    void insert(User user);
}
