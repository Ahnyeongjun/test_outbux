package io.github.ahnyeongjun.outboxtest.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ahnyeongjun.outbox.capture.DefaultOutboxConverter;
import io.github.ahnyeongjun.outbox.capture.OutboxContext;
import io.github.ahnyeongjun.outbox.capture.OutboxEventFlusher;
import io.github.ahnyeongjun.outbox.config.OutboxProperties;
import io.github.ahnyeongjun.outbox.model.Outbox;
import io.github.ahnyeongjun.outbox.publish.OutboxFileWriter;
import io.github.ahnyeongjun.outbox.spi.OutboxConverter;
import io.github.ahnyeongjun.outbox.spi.OutboxStore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JMH 마이크로벤치마크 — outbox 핫패스의 nanosecond 단위 비용 측정.
 *
 * <p>측정 대상:
 * <ul>
 *   <li>{@link DefaultOutboxConverter#convert} — JSON 직렬화 + 민감 필드 마스킹 비용
 *   <li>{@link OutboxEventFlusher#capture} — 테이블 필터 + 컨버터 조회 + ThreadLocal 적재
 *   <li>{@link OutboxFileWriter#write} — JSON 빌드 + gzip 압축 + 디스크 쓰기 (배치 사이즈별)
 * </ul>
 *
 * <p>실행: {@link OutboxBenchmarkRunner#main} 통해 프로그래마틱 실행 또는
 * {@code mvn -f pom-standalone.xml test -Dtest=OutboxBenchmarkRunner}.
 */
public class OutboxBenchmarks {

    // -------------------- DefaultOutboxConverter --------------------

    @State(Scope.Benchmark)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 3, time = 1)
    @Measurement(iterations = 5, time = 1)
    @Fork(1)
    public static class ConverterBench {
        private DefaultOutboxConverter converter;
        private User smallEntity;
        private LargeEntity largeEntity;

        @Setup
        public void setUp() {
            converter = new DefaultOutboxConverter(new ObjectMapper());
            smallEntity = new User(1L, "alice", "p@ss", "tk-abc");
            largeEntity = LargeEntity.sample();
        }

        @Benchmark
        public Outbox convert_smallEntity() {
            return converter.convert(smallEntity, "USER", "CREATED");
        }

        @Benchmark
        public Outbox convert_largeEntity_50fields() {
            return converter.convert(largeEntity, "ORDER", "UPDATED");
        }
    }

    // -------------------- OutboxEventFlusher.capture --------------------

    @State(Scope.Benchmark)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 3, time = 1)
    @Measurement(iterations = 5, time = 1)
    @Fork(1)
    public static class FlusherBench {
        private OutboxEventFlusher flusher;
        private OutboxEventFlusher flusherUnmatched;
        private User entity;

        @Setup
        public void setUp() {
            ObjectMapper mapper = new ObjectMapper();
            DefaultOutboxConverter converter = new DefaultOutboxConverter(mapper);

            OutboxProperties propsMatched = new OutboxProperties();
            propsMatched.setTables(Set.of("user"));
            flusher = new OutboxEventFlusher(
                    new NoopStore(), propsMatched,
                    Map.of("defaultOutboxConverter", converter));

            OutboxProperties propsUnmatched = new OutboxProperties();
            propsUnmatched.setTables(Set.of("only_this_table"));
            flusherUnmatched = new OutboxEventFlusher(
                    new NoopStore(), propsUnmatched,
                    Map.of("defaultOutboxConverter", converter));

            entity = new User(1L, "alice", "p@ss", "tk-abc");
        }

        @Benchmark
        public void capture_tableMatched_outsideTransaction() {
            // 트랜잭션 외부 → flushPending 즉시 호출 + clear. NoopStore 라 INSERT 비용 없음.
            flusher.capture("user", entity, "CREATED");
        }

        @Benchmark
        public void capture_tableNotInWhitelist() {
            // 화이트리스트에 없으면 매우 빠르게 빠져나가야 함 (early return)
            flusherUnmatched.capture("user", entity, "CREATED");
        }

        @Benchmark
        public void capture_suppressed(Blackhole bh) {
            OutboxContext.getOrCreate().suppress();
            try {
                flusher.capture("user", entity, "CREATED");
                bh.consume(entity);
            } finally {
                OutboxContext.clear();
            }
        }
    }

    // -------------------- OutboxFileWriter --------------------

    @State(Scope.Benchmark)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(iterations = 2, time = 1)
    @Measurement(iterations = 3, time = 2)
    @Fork(1)
    public static class FileWriterBench {

        @Param({"10", "100", "1000"})
        public int batchSize;

        private OutboxFileWriter writer;
        private OutboxProperties properties;
        private List<Outbox> batch;
        private Path tempDir;

        @Setup
        public void setUp() throws IOException {
            tempDir = Files.createTempDirectory("outbox-jmh-");
            properties = new OutboxProperties();
            properties.getFile().setPath(tempDir.toString());
            writer = new OutboxFileWriter(properties, new ObjectMapper());

            batch = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++) {
                batch.add(Outbox.builder()
                        .seq((long) (i + 1))
                        .domain("USER")
                        .eventType("UPDATED")
                        .source("INTERNAL")
                        .payload("{\"id\":" + i + ",\"name\":\"user" + i + "\",\"email\":\"u" + i + "@example.com\"}")
                        .build());
            }
        }

        @Benchmark
        public String write_batch() {
            return writer.write(batch);
        }

        @TearDown
        public void tearDown() throws IOException {
            // 누적 파일 정리
            try (var s = Files.walk(tempDir)) {
                s.sorted(java.util.Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(java.io.File::delete);
            }
        }
    }

    // -------------------- Test fixtures --------------------

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class User {
        private Long id;
        private String name;
        private String password;
        private String token;
    }

    @Data
    public static class LargeEntity {
        private Long id;
        private String f01, f02, f03, f04, f05, f06, f07, f08, f09, f10;
        private String f11, f12, f13, f14, f15, f16, f17, f18, f19, f20;
        private String f21, f22, f23, f24, f25, f26, f27, f28, f29, f30;
        private String f31, f32, f33, f34, f35, f36, f37, f38, f39, f40;
        private String password, token, secret, apiKey, refreshToken;

        static LargeEntity sample() {
            LargeEntity e = new LargeEntity();
            e.id = 1L;
            String filler = "0123456789abcdef";
            e.f01 = filler; e.f02 = filler; e.f03 = filler; e.f04 = filler; e.f05 = filler;
            e.f06 = filler; e.f07 = filler; e.f08 = filler; e.f09 = filler; e.f10 = filler;
            e.f11 = filler; e.f12 = filler; e.f13 = filler; e.f14 = filler; e.f15 = filler;
            e.f16 = filler; e.f17 = filler; e.f18 = filler; e.f19 = filler; e.f20 = filler;
            e.f21 = filler; e.f22 = filler; e.f23 = filler; e.f24 = filler; e.f25 = filler;
            e.f26 = filler; e.f27 = filler; e.f28 = filler; e.f29 = filler; e.f30 = filler;
            e.f31 = filler; e.f32 = filler; e.f33 = filler; e.f34 = filler; e.f35 = filler;
            e.f36 = filler; e.f37 = filler; e.f38 = filler; e.f39 = filler; e.f40 = filler;
            e.password = "secret"; e.token = "tk"; e.secret = "s"; e.apiKey = "k"; e.refreshToken = "r";
            return e;
        }
    }

    static class NoopStore implements OutboxStore {
        @Override public void saveAll(List<Outbox> events) {}
        @Override public long countPending() { return 0; }
        @Override public List<Outbox> findPendingWithLock(int limit) { return List.of(); }
        @Override public void markSent(List<Outbox> batch) {}
        @Override public void markFailed(Long id) {}
        @Override public void deleteOldSent() {}
    }
}
