package io.github.ahnyeongjun.outboxtest.load;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.github.ahnyeongjun.outboxtest.User;
import io.github.ahnyeongjun.outboxtest.UsersMapper;

/**
 * Gatling 부하 테스트용 HTTP 엔드포인트.
 * MyBatis insert → Outbox 캡처 → beforeCommit → outbox INSERT 의 전체 경로를 트리거한다.
 */
@RestController
public class UsersController {

    @Autowired
    private UsersMapper usersMapper;

    @PostMapping("/users")
    @Transactional
    public Map<String, Object> create(@RequestBody User user) {
        usersMapper.insert(user);
        return Map.of("id", user.getId(), "name", user.getName());
    }
}
