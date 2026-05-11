package io.github.ahnyeongjun.outboxtest.bench;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH 벤치마크 실행기.
 *
 * <p>{@code @Disabled} 로 기본 {@code mvn test} 에서 빠지며, 명시 실행:
 * <pre>
 * mvn -f pom-standalone.xml test -Dtest=OutboxBenchmarkRunner -DfailIfNoTests=false -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition
 * </pre>
 * 또는 {@link #main}.
 *
 * <p>결과는 {@code target/jmh-result.json} + stdout 표/그래프 형식.
 */
@Tag("benchmark")
@Disabled("JMH 벤치마크는 일반 테스트와 분리. -Djunit.jupiter.conditions.deactivate 또는 main 으로 실행.")
public class OutboxBenchmarkRunner {

    @Test
    void runAll() throws RunnerException {
        runBenchmarks();
    }

    public static void main(String[] args) throws RunnerException {
        runBenchmarks();
    }

    private static void runBenchmarks() throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(OutboxBenchmarks.class.getSimpleName() + ".*")
                .resultFormat(org.openjdk.jmh.results.format.ResultFormatType.JSON)
                .result("target/jmh-result.json")
                .shouldFailOnError(true)
                .build();
        new Runner(opt).run();
    }
}
