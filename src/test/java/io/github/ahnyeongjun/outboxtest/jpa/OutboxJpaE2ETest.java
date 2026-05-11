package io.github.ahnyeongjun.outboxtest.jpa;

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
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ahnyeongjun.outbox.publish.OutboxScheduler;
import io.github.ahnyeongjun.outbox.store.OutboxDialect;

/**
 * JPA 어댑터 E2E — Hibernate {@code PostInsert/Update/Delete} 이벤트 기반 캡처가 전체 파이프라인에서
 * 동작하는지 검증한다.
 *
 * <p>특히 검증하는 것:
 * <ul>
 *   <li>JPA save → PostInsertEvent → outbox 캡처 → beforeCommit 시점에 outbox INSERT
 *   <li>JPA update → PostUpdateEvent → UPDATED 이벤트 캡처
 *   <li>JPA delete → PostDeleteEvent → DELETED 이벤트 캡처
 *   <li>Hibernate flush 와 TransactionSynchronization.beforeCommit 의 순서 — 우리가 가정한 순서대로 작동하는지
 *   <li>최종적으로 스케줄러가 outbox 를 picked → gzip 파일 생성까지
 * </ul>
 *
 * <p>외부 패키지({@code outboxtest.jpa})에 배치 — production ComponentScan 오염 회피.
 */
@SpringBootTest(classes = OutboxJpaE2ETest.JpaTestApp.class)
class OutboxJpaE2ETest {

    private static final Path OUT_DIR = Path.of(
            System.getProperty("java.io.tmpdir"),
            "outbox-jpa-e2e-" + UUID.randomUUID()
    );

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",
                () -> "jdbc:h2:mem:jpa-e2e-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        r.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.jpa.show-sql", () -> "false");
        r.add("spring.main.web-application-type", () -> "none");
        r.add("outbox.tables", () -> "product");
        r.add("outbox.batch.size", () -> "100");
        r.add("outbox.batch.time-trigger-ms", () -> "0");
        r.add("outbox.batch.check-interval-ms", () -> "86400000");
        r.add("outbox.file.path", OUT_DIR::toString);
        // outbox 테이블만 직접 만들어둠 (Hibernate ddl-auto 가 product 테이블은 자동 생성)
        r.add("spring.sql.init.mode", () -> "always");
        r.add("spring.sql.init.schema-locations", () -> "classpath:jpa-e2e-outbox-schema.sql");
    }

    @AfterAll
    static void cleanup() throws IOException {
        if (Files.exists(OUT_DIR)) {
            try (Stream<Path> walk = Files.walk(OUT_DIR)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    @Autowired ProductJpaRepository repo;
    @Autowired OutboxScheduler scheduler;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired ObjectMapper objectMapper;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txm);
        jdbc.execute("DELETE FROM product");
        jdbc.execute("DELETE FROM outbox");
    }

    @Test
    void jpaInsert_capturesAsCreated_andFlushesToFile() throws Exception {
        tx.executeWithoutResult(s -> {
            repo.save(new Product(null, "Widget", 1000));
            repo.save(new Product(null, "Gadget", 2500));
        });

        // outbox 에 2건 PENDING — Hibernate PostInsert → beforeCommit → outbox INSERT
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE status='PENDING' AND domain='PRODUCT' AND event_type='CREATED'",
                Long.class)).isEqualTo(2L);

        scheduler.processOutbox();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE status='SENT'", Long.class))
                .isEqualTo(2L);
        try (Stream<Path> files = Files.list(OUT_DIR)) {
            Path file = files.findFirst().orElseThrow();
            JsonNode root = objectMapper.readTree(decompress(file));
            assertThat(root.get("events").size()).isEqualTo(2);
            assertThat(root.get("events").get(0).get("domain").asText()).isEqualTo("PRODUCT");
            assertThat(root.get("events").get(0).get("event_type").asText()).isEqualTo("CREATED");
        }
    }

    @Test
    void jpaUpdate_capturesAsUpdated() {
        // 사전 데이터 (캡처되지만 별도 트랜잭션이라 미리 ㄱㄱ)
        Long pid = tx.execute(s -> {
            Product p = repo.save(new Product(null, "Widget", 1000));
            return p.getId();
        });
        jdbc.execute("DELETE FROM outbox");  // 초기 INSERT 흔적 제거

        // 업데이트 트랜잭션
        tx.executeWithoutResult(s -> {
            Product p = repo.findById(pid).orElseThrow();
            p.setPrice(2000);
            // repo.save() 호출 안 해도 dirty checking 으로 flush 시 UPDATE 됨
        });

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE event_type='UPDATED' AND domain='PRODUCT'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void jpaDelete_capturesAsDeleted() {
        Long pid = tx.execute(s -> repo.save(new Product(null, "Widget", 1000)).getId());
        jdbc.execute("DELETE FROM outbox");

        tx.executeWithoutResult(s -> repo.deleteById(pid));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE event_type='DELETED' AND domain='PRODUCT'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void multipleEntitiesInSameTransaction_allCaptured() {
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 5; i++) repo.save(new Product(null, "Item-" + i, i * 100));
        });

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE status='PENDING' AND event_type='CREATED'",
                Long.class)).isEqualTo(5L);
    }

    @Test
    void rollback_undoesBothEntityAndOutbox() {
        try {
            tx.executeWithoutResult(s -> {
                repo.save(new Product(null, "Will-Rollback", 999));
                throw new RuntimeException("force rollback");
            });
        } catch (RuntimeException expected) {
            // ignore
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class)).isZero();
    }

    @Test
    void flushOrder_PostInsertEventFiresBeforeBeforeCommit_soOutboxSeesAllEvents() {
        // Hibernate flush(=PostInsertEvent) 가 TransactionSynchronization.beforeCommit 보다 먼저 일어남을
        // 행위로 검증: 한 tx 에서 N 건 save → outbox 에 N 건이 정확히 같이 commit 되어 있어야 함.
        int n = 10;
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < n; i++) repo.save(new Product(null, "P-" + i, i));
        });

        long entityCount = jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class);
        long outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE domain='PRODUCT'", Long.class);

        assertThat(entityCount).isEqualTo(n);
        assertThat(outboxCount).as("flush(=PostInsertEvent) 가 beforeCommit 보다 먼저여서 outbox 가 모든 이벤트를 봐야 함")
                .isEqualTo(n);
    }

    private byte[] decompress(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(new FileInputStream(file.toFile()));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    @SpringBootApplication
    @EntityScan(basePackageClasses = Product.class)
    @EnableJpaRepositories(basePackageClasses = ProductJpaRepository.class)
    static class JpaTestApp {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        OutboxDialect h2TestDialect() {
            return new H2TestDialect();
        }
    }

    /** H2 호환 dialect — outbox 테이블이 H2 에 있을 때 PG/MySQL 방언 대신 사용. */
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
