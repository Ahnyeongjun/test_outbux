package io.github.ahnyeongjun.outboxtest.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.gatling.app.Gatling;
import io.gatling.core.config.GatlingPropertiesBuilder;
import io.github.ahnyeongjun.outbox.adapter.jdbc.OutboxDialect;
import io.github.ahnyeongjun.outbox.publish.OutboxScheduler;

/**
 * Gatling HTTP 부하 테스트.
 *
 * <p>{@code @Disabled} 로 일반 {@code mvn test} 에서 빠지며, 명시 실행:
 * <pre>
 * mvn -f pom-standalone.xml test -Dtest=OutboxLoadTest -DfailIfNoTests=false \
 *     -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition
 * </pre>
 *
 * <p>결과 리포트: {@code target/gatling/usersinsertsimulation-<timestamp>/index.html}.
 */
@Tag("load")
@Disabled("Gatling 부하 테스트는 일반 테스트와 분리. -Djunit.jupiter.conditions.deactivate 로 활성화.")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        classes = OutboxLoadTest.LoadTestApp.class
)
class OutboxLoadTest {

    private static final int SERVER_PORT = 18080;
    private static final Path OUT_DIR = Path.of(
            System.getProperty("java.io.tmpdir"),
            "outbox-load-" + UUID.randomUUID()
    );

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("server.port", () -> String.valueOf(SERVER_PORT));
        r.add("spring.datasource.url",
                () -> "jdbc:h2:mem:load-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        r.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        r.add("spring.sql.init.mode", () -> "always");
        r.add("spring.sql.init.schema-locations", () -> "classpath:e2e-schema.sql");
        r.add("outbox.tables", () -> "users");
        // 배치 트리거 적당히 빠르게 (5초 OR 200건)
        r.add("outbox.batch.size", () -> "200");
        r.add("outbox.batch.time-trigger-ms", () -> "5000");
        r.add("outbox.batch.check-interval-ms", () -> "2000");
        r.add("outbox.file.path", OUT_DIR::toString);
    }

    @AfterAll
    static void cleanup() throws IOException {
        if (Files.exists(OUT_DIR)) {
            try (Stream<Path> walk = Files.walk(OUT_DIR)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxScheduler scheduler;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE TABLE users");
        jdbc.execute("TRUNCATE TABLE outbox");
        jdbc.execute("ALTER TABLE outbox ALTER COLUMN id RESTART WITH 1");
    }

    @Test
    void httpInsertLoad_capturesOutboxAndFlushesFiles() throws Exception {
        System.setProperty("gatling.baseUrl", "http://localhost:" + SERVER_PORT);

        GatlingPropertiesBuilder props = new GatlingPropertiesBuilder()
                .simulationClass(UsersInsertSimulation.class.getName())
                .resultsDirectory("target/gatling")
                .runDescription("outbox http load");

        int exitCode = Gatling.fromMap(props.build());
        assertThat(exitCode).as("Gatling 어설션 통과").isZero();

        // 부하 종료 후 잠시 대기 (스케줄러가 마지막 배치 처리할 시간)
        Thread.sleep(7_000);
        scheduler.processOutbox();
        Thread.sleep(500);
        scheduler.processOutbox();

        long users = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        long pending = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE status='PENDING'", Long.class);
        long sent = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE status='SENT'", Long.class);
        long failed = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE status='FAILED'", Long.class);

        System.out.println("[LOAD-RESULT] users=" + users
                + " outbox(pending=" + pending + " sent=" + sent + " failed=" + failed + ")");

        assertThat(users).isGreaterThan(0);
        assertThat(users).isEqualTo(pending + sent + failed)
                .withFailMessage("user INSERT 수와 outbox 캡처 수가 1:1 이어야 함 (users=%d, outbox=%d)",
                        users, pending + sent + failed);
        assertThat(failed).isZero();
        assertThat(pending).as("스케줄러가 모든 PENDING 을 처리해야 함").isZero();

        long fileCount;
        try (Stream<Path> files = Files.list(OUT_DIR)) {
            fileCount = files.count();
        }
        assertThat(fileCount).as("gzip 파일이 1개 이상 생성").isGreaterThan(0);
    }

    @SpringBootApplication(
            scanBasePackages = "io.github.ahnyeongjun.outboxtest",
            exclude = {
                    HibernateJpaAutoConfiguration.class,
                    JpaRepositoriesAutoConfiguration.class
            })
    @org.mybatis.spring.annotation.MapperScan("io.github.ahnyeongjun.outboxtest")
    static class LoadTestApp {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        OutboxDialect h2TestDialect() {
            return new H2TestDialect();
        }
    }

    static class H2TestDialect implements OutboxDialect {
        @Override public String insertSql() {
            return "INSERT INTO outbox (domain, event_type, source, payload, status, created_at) " +
                   "VALUES (:domain, :eventType, :source, :payload, 'PENDING', CURRENT_TIMESTAMP)";
        }
        @Override public String selectPendingWithLockSql() {
            return "SELECT id, id AS seq, domain, event_type, source, payload, status, created_at, sent_at " +
                   "FROM outbox WHERE status = 'PENDING' ORDER BY id ASC LIMIT :limit";
        }
        @Override public String deleteOldSentSql() {
            return "DELETE FROM outbox WHERE status = 'SENT' AND sent_at < DATEADD('DAY', -7, CURRENT_TIMESTAMP)";
        }
    }
}
