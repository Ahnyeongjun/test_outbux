package io.github.ahnyeongjun.outboxtest.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.ahnyeongjun.outbox.adapter.jpa.JpaOutboxStore;
import io.github.ahnyeongjun.outbox.adapter.jpa.OutboxEntity;
import io.github.ahnyeongjun.outbox.model.Outbox;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * H2 위에서 JpaOutboxStore 동작 검증.
 *
 * <p>{@code spring.jpa.hibernate.ddl-auto=create-drop} 로 OutboxEntity 스키마 자동 생성.
 * {@code PESSIMISTIC_WRITE + SKIP LOCKED} 힌트가 H2 에서 동작함을 함께 확인.
 */
@SpringBootTest(classes = JpaOutboxStoreTest.JpaTestApp.class)
class JpaOutboxStoreTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",
                () -> "jdbc:h2:mem:jpa-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        r.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.main.web-application-type", () -> "none");
        // 본 모듈의 OutboxAutoConfig 가 띄우는 스케줄러는 본 테스트와 무관하므로 폴링 비활성화
        r.add("outbox.batch.check-interval-ms", () -> "86400000");
        r.add("outbox.tables", () -> "");
    }

    @Autowired JpaOutboxStore store;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager txm;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txm);
        // 매 테스트 시작 시 outbox 비우기 (컨텍스트는 재사용)
        tx.executeWithoutResult(s -> {
            EntityManager em = emf.createEntityManager();
            try {
                em.getTransaction().begin();
                em.createQuery("DELETE FROM OutboxEntity").executeUpdate();
                em.getTransaction().commit();
            } finally {
                em.close();
            }
        });
    }

    @Test
    void saveAll_persistsAsPending() {
        tx.executeWithoutResult(s -> store.saveAll(List.of(
                outbox("USER", "CREATED"),
                outbox("USER", "UPDATED")
        )));

        assertThat(store.countPending()).isEqualTo(2);
    }

    @Test
    void processBatch_handlerSuccess_marksSent() {
        tx.executeWithoutResult(s -> store.saveAll(List.of(
                outbox("USER", "CREATED"), outbox("USER", "UPDATED")
        )));

        int handled = store.processBatch(tx, 10, batch -> {
            assertThat(batch).hasSize(2);
            assertThat(batch).allSatisfy(o -> assertThat(o.getStatus()).isEqualTo("PENDING"));
        });

        assertThat(handled).isEqualTo(2);
        assertThat(store.countPending()).isZero();
        assertThat(countByStatus("SENT")).isEqualTo(2);
    }

    @Test
    void processBatch_handlerThrows_marksFailed_andDoesNotPropagate() {
        tx.executeWithoutResult(s -> store.saveAll(List.of(
                outbox("USER", "CREATED"), outbox("USER", "UPDATED")
        )));

        int handled = store.processBatch(tx, 10, batch -> {
            throw new RuntimeException("simulated failure");
        });

        assertThat(handled).isEqualTo(2);
        assertThat(countByStatus("FAILED")).isEqualTo(2);
        assertThat(store.countPending()).isZero();
    }

    @Test
    void processBatch_emptyPending_returnsZero() {
        boolean[] called = {false};
        int handled = store.processBatch(tx, 10, b -> called[0] = true);

        assertThat(handled).isZero();
        assertThat(called[0]).isFalse();
    }

    @Test
    void processBatch_respectsLimit() {
        tx.executeWithoutResult(s -> store.saveAll(List.of(
                outbox("USER", "CREATED"),
                outbox("USER", "UPDATED"),
                outbox("USER", "DELETED")
        )));

        List<Integer> seenSizes = new ArrayList<>();
        int handled = store.processBatch(tx, 2, batch -> seenSizes.add(batch.size()));

        assertThat(handled).isEqualTo(2);
        assertThat(seenSizes).containsExactly(2);
        assertThat(store.countPending()).isEqualTo(1);
    }

    @Test
    void processBatch_seqEqualsId() {
        tx.executeWithoutResult(s -> store.saveAll(List.of(outbox("USER", "CREATED"))));

        List<Outbox> captured = new ArrayList<>();
        store.processBatch(tx, 10, captured::addAll);

        assertThat(captured).hasSize(1);
        Outbox o = captured.get(0);
        assertThat(o.getSeq()).isEqualTo(o.getId());
    }

    @Test
    void deleteOldSent_removesOnlyOldSentRows() {
        tx.executeWithoutResult(s -> store.saveAll(List.of(
                outbox("USER", "CREATED"), outbox("USER", "UPDATED")
        )));
        store.processBatch(tx, 10, b -> { /* success → SENT */ });

        // SENT 1건의 sent_at 을 8일 전으로 → cleanup 대상
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            OutboxEntity row = em.createQuery(
                    "SELECT o FROM OutboxEntity o WHERE o.status = 'SENT' ORDER BY o.id ASC",
                    OutboxEntity.class).setMaxResults(1).getSingleResult();
            row.setSentAt(Instant.now().minus(Duration.ofDays(8)));
            em.getTransaction().commit();
        } finally {
            em.close();
        }

        tx.executeWithoutResult(s -> store.deleteOldSent());

        // 오래된 SENT 1건 삭제, 최근 SENT 1건 남음
        assertThat(countAll()).isEqualTo(1);
        assertThat(countByStatus("SENT")).isEqualTo(1);
    }

    private Outbox outbox(String domain, String eventType) {
        return Outbox.builder()
                .domain(domain).eventType(eventType).source("INTERNAL").payload("{}")
                .build();
    }

    private long countByStatus(String status) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery(
                    "SELECT COUNT(o) FROM OutboxEntity o WHERE o.status = :s", Long.class)
                    .setParameter("s", status).getSingleResult();
        } finally {
            em.close();
        }
    }

    private long countAll() {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery(
                    "SELECT COUNT(o) FROM OutboxEntity o", Long.class).getSingleResult();
        } finally {
            em.close();
        }
    }

    @SpringBootApplication
    @EntityScan(basePackageClasses = OutboxEntity.class)
    static class JpaTestApp {

        @Bean
        JpaOutboxStore jpaOutboxStore(EntityManagerFactory emf) {
            // Spring tx 와 연동되는 thread-bound shared EM 생성
            return new JpaOutboxStore(SharedEntityManagerCreator.createSharedEntityManager(emf));
        }
    }
}
