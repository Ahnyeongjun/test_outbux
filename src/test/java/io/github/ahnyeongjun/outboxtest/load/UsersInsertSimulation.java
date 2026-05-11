package io.github.ahnyeongjun.outboxtest.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import java.util.concurrent.atomic.AtomicLong;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

/**
 * 가틀링 시나리오 — outbox 캡처가 동작하는 상태에서 사용자 INSERT HTTP 부하.
 *
 * <p>임계값(thresholds):
 * <ul>
 *   <li>전체 평균 응답 < 300ms
 *   <li>p95 응답 < 800ms
 *   <li>에러율 0%
 * </ul>
 */
public class UsersInsertSimulation extends Simulation {

    private static final String BASE_URL =
            System.getProperty("gatling.baseUrl", "http://localhost:18080");

    /** 동시 PK 충돌 방지용 카운터 (JVM 내 단일 시뮬레이션 가정) */
    private static final AtomicLong ID_SEQ = new AtomicLong(1);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private final ScenarioBuilder scenario = scenario("users-insert")
            .exec(session -> session.set("uid", ID_SEQ.getAndIncrement()))
            .exec(
                    http("POST /users")
                            .post("/users")
                            .body(StringBody("{\"id\":#{uid},\"name\":\"u-#{uid}\"}"))
                            .check(status().is(200))
            );

    {
        // 8 VU/s 로 시작해 30s 동안 32 VU/s 까지 증가 (총 ~600 req 정도)
        setUp(scenario.injectOpen(rampUsersPerSec(8).to(32).during(30))
                .protocols(httpProtocol)
        ).assertions(
                global().responseTime().mean().lt(300),
                global().responseTime().percentile(95).lt(800),
                global().failedRequests().count().is(0L)
        );
    }
}
