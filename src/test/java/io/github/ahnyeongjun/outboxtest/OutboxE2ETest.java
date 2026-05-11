package io.github.ahnyeongjun.outboxtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ahnyeongjun.outbox.store.OutboxDialect;
import io.github.ahnyeongjun.outbox.publish.OutboxScheduler;

/**
 * 전체 파이프라인 통합 검증:
 * <pre>
 * MyBatis Mapper.insert → OutboxInterceptor → OutboxEventFlusher
 *  → beforeCommit → JdbcOutboxStore.saveAll → outbox 테이블 INSERT
 *  → OutboxScheduler.processOutbox → OutboxFileWriter → gzip 파일
 * </pre>
 *
 * <p>H2 메모리 DB 위에서 동작하며, 실제 MySQL/PostgreSQL 방언 대신 H2 호환 dialect 를 빈으로 주입.
 * 테스트 패키지가 {@code io.github.ahnyeongjun.outboxtest} 인 이유는
 * {@link io.github.ahnyeongjun.outbox.config.OutboxAutoConfig} 의 ComponentScan 이
 * {@code io.github.ahnyeongjun.outbox} 하위만 스캔하므로 이 테스트의 {@link TestApp} 빈이
 * 다른 테스트 컨텍스트(OutboxAutoConfigTest 등)를 오염시키지 않도록 격리한 것.
 */
@SpringBootTest(classes = OutboxE2ETest.TestApp.class)
class OutboxE2ETest {

    private static final Path OUT_DIR = Path.of(
            System.getProperty("java.io.tmpdir"),
            "outbox-e2e-" + UUID.randomUUID()
    );

    private static final String DB_NAME = "e2e-" + UUID.randomUUID();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DB_NAME + ";DB_CLOSE_DELAY=-1");
        r.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        // 스케줄러 첫 실행이 컨텍스트 시작 직후 발생하므로 그 전에 outbox 테이블이 있어야 한다.
        r.add("spring.sql.init.mode", () -> "always");
        r.add("spring.sql.init.schema-locations", () -> "classpath:e2e-schema.sql");
        r.add("outbox.tables", () -> "users");
        r.add("outbox.batch.size", () -> "100");
        r.add("outbox.batch.time-trigger-ms", () -> "0");           // 항상 시간 트리거 충족
        r.add("outbox.batch.check-interval-ms", () -> "86400000");  // 자동 스케줄 실질 비활성
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

    @Autowired UsersMapper usersMapper;
    @Autowired OutboxScheduler scheduler;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE TABLE users");
        jdbc.execute("TRUNCATE TABLE outbox");
        jdbc.execute("ALTER TABLE outbox ALTER COLUMN id RESTART WITH 1");
    }

    @Test
    void mybatisInsert_through_outbox_to_gzipFile() throws Exception {
        // 1. 비즈니스 트랜잭션 내에서 MyBatis insert 2건
        new TransactionTemplate(txm).executeWithoutResult(status -> {
            usersMapper.insert(new User(1L, "alice"));
            usersMapper.insert(new User(2L, "bob"));
        });

        // 2. 비즈니스 데이터 + outbox 가 같은 커밋에서 함께 반영
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE status='PENDING' AND domain='USERS' AND event_type='CREATED'",
                Long.class)).isEqualTo(2L);

        // payload 에 민감 필드 없음 확인 (User 엔티티엔 없지만 직렬화 동작 검증)
        List<String> payloads = jdbc.queryForList(
                "SELECT payload FROM outbox ORDER BY id ASC", String.class);
        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0)).contains("\"name\":\"alice\"");
        assertThat(payloads.get(1)).contains("\"name\":\"bob\"");

        // 3. 스케줄러 수동 실행 → SENT 마킹 + gzip 파일 생성
        scheduler.processOutbox();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE status='SENT'", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE status='PENDING'", Long.class)).isZero();

        // 4. 생성된 gzip 파일 압축 해제 후 JSON 구조 검증
        try (Stream<Path> files = Files.list(OUT_DIR)) {
            Path file = files.findFirst().orElseThrow(() -> new AssertionError("no file written"));
            assertThat(file.getFileName().toString()).matches("sync_\\d+_\\d+_\\d{8}T\\d{6}Z\\.json\\.gz");

            JsonNode root = objectMapper.readTree(decompress(file));
            assertThat(root.get("meta").get("source").asText()).isEqualTo("INTERNAL");
            assertThat(root.get("events").size()).isEqualTo(2);

            JsonNode first = root.get("events").get(0);
            assertThat(first.get("domain").asText()).isEqualTo("USERS");
            assertThat(first.get("event_type").asText()).isEqualTo("CREATED");
            assertThat(first.get("source").asText()).isEqualTo("INTERNAL");
            assertThat(first.get("payload").get("name").asText()).isEqualTo("alice");

            assertThat(root.get("events").get(1).get("payload").get("name").asText()).isEqualTo("bob");
        }
    }

    @Test
    void runSuppressed_blocksOutboxCaptureForInboundEvents() {
        // 폐쇄망에서 받은 이벤트 재적용 시나리오: 캡처 차단되어야 함
        new TransactionTemplate(txm).executeWithoutResult(status ->
                io.github.ahnyeongjun.outbox.capture.OutboxContext.runSuppressed(() ->
                        usersMapper.insert(new User(99L, "from-closed-net"))));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class)).isZero();
    }

    private byte[] decompress(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(new FileInputStream(file.toFile()));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    /**
     * JPA 관련 auto-config 는 H2 테스트에서 빈 EntityManagerFactory 만 부가 생성하므로 제외.
     * (이 E2E 는 MyBatis 경로만 검증)
     */
    @SpringBootApplication(exclude = {
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class
    })
    static class TestApp {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /**
         * H2 호환 dialect (테스트 전용). 실제 MySQL/PostgreSQL 방언은 H2 와 일부 SQL 차이가 있어
         * 자동 선택된 방언 대신 직접 빈 등록하여 OutboxAutoConfig 의 @ConditionalOnMissingBean 분기로 우회한다.
         */
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
