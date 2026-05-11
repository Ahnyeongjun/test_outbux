package io.github.ahnyeongjun.outbox.publish;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ahnyeongjun.outbox.config.OutboxProperties;
import io.github.ahnyeongjun.outbox.model.Outbox;

class OutboxFileWriterTest {

    @TempDir Path tempDir;

    private OutboxFileWriter writer;
    private OutboxProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new OutboxProperties();
        properties.getFile().setPath(tempDir.toString());
        writer = new OutboxFileWriter(properties, mapper);
    }

    @Test
    void write_createsGzipFileWithSeqRange() throws Exception {
        List<Outbox> batch = List.of(
                Outbox.builder().seq(1001L).domain("USER").eventType("CREATED")
                        .source("INTERNAL").payload("{\"id\":1}").build(),
                Outbox.builder().seq(1002L).domain("USER").eventType("UPDATED")
                        .source("INTERNAL").payload("{\"id\":2}").build()
        );

        String path = writer.write(batch);

        assertThat(Path.of(path)).exists();
        assertThat(Path.of(path).getFileName().toString())
                .matches(Pattern.compile("sync_1001_1002_\\d{8}T\\d{6}Z\\.json\\.gz"));
    }

    @Test
    void write_contentIsValidJsonInsideGzip() throws Exception {
        List<Outbox> batch = List.of(
                Outbox.builder().seq(1L).domain("USER").eventType("CREATED")
                        .source("INTERNAL").payload("{\"id\":42,\"name\":\"alice\"}").build()
        );

        String path = writer.write(batch);
        JsonNode root = mapper.readTree(decompress(path));

        assertThat(root.get("meta").get("seq_from").asLong()).isEqualTo(1L);
        assertThat(root.get("meta").get("seq_to").asLong()).isEqualTo(1L);
        assertThat(root.get("meta").get("source").asText()).isEqualTo("INTERNAL");
        assertThat(root.get("meta").get("created_at").asText()).isNotEmpty();

        JsonNode event = root.get("events").get(0);
        assertThat(event.get("seq").asLong()).isEqualTo(1L);
        assertThat(event.get("domain").asText()).isEqualTo("USER");
        assertThat(event.get("event_type").asText()).isEqualTo("CREATED");
        assertThat(event.get("payload").get("id").asLong()).isEqualTo(42L);
        assertThat(event.get("payload").get("name").asText()).isEqualTo("alice");
    }

    @Test
    void write_createsDirectoryIfMissing() throws Exception {
        Path nested = tempDir.resolve("a/b/c");
        properties.getFile().setPath(nested.toString());

        String path = writer.write(List.of(
                Outbox.builder().seq(1L).domain("USER").eventType("CREATED")
                        .source("INTERNAL").payload("{}").build()
        ));

        assertThat(Files.exists(nested)).isTrue();
        assertThat(Path.of(path).getParent()).isEqualTo(nested);
    }

    @Test
    void write_singleEventSeqFromEqualsSeqTo() throws Exception {
        String path = writer.write(List.of(
                Outbox.builder().seq(99L).domain("USER").eventType("CREATED")
                        .source("INTERNAL").payload("{}").build()
        ));

        assertThat(Path.of(path).getFileName().toString()).contains("sync_99_99_");
    }

    @Test
    void onlyOneFilePerBatch() throws Exception {
        writer.write(List.of(
                Outbox.builder().seq(1L).domain("USER").eventType("CREATED")
                        .source("INTERNAL").payload("{}").build()
        ));

        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    private byte[] decompress(String path) throws IOException {
        try (InputStream in = new GZIPInputStream(new FileInputStream(path));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
