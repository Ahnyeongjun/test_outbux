package io.github.ahnyeongjun.outbox.writer;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ahnyeongjun.outbox.model.Outbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbox Î∞∞ÏπòÎ•?JSON ÏßÅÎ†¨????gzip ?ïÏ∂ï ?åÏùºÎ°??Ä??
 * ?åÏùºÎ™? sync_{seqFrom}_{seqTo}_{timestamp}.json.gz
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxFileWriter {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @Value("${outbox.file.path}")
    private String outboxFilePath;

    private final ObjectMapper objectMapper;

    public String write(List<Outbox> batch) {
        long seqFrom = batch.get(0).getSeq();
        long seqTo   = batch.get(batch.size() - 1).getSeq();
        String fileName = String.format("sync_%d_%d_%s.json.gz",
                seqFrom, seqTo, TS_FMT.format(Instant.now()));

        Path dir      = Paths.get(outboxFilePath);
        Path filePath = dir.resolve(fileName);

        try {
            Files.createDirectories(dir);
            writeGzip(filePath, buildJson(batch, seqFrom, seqTo));
            log.info("Outbox file written: {}", filePath);
            return filePath.toString();
        } catch (IOException e) {
            throw new RuntimeException("Outbox file write failed: " + filePath, e);
        }
    }

    private byte[] buildJson(List<Outbox> batch, long seqFrom, long seqTo) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode meta = root.putObject("meta");
        meta.put("seq_from", seqFrom);
        meta.put("seq_to", seqTo);
        meta.put("source", "INTERNAL");
        meta.put("created_at", Instant.now().toString());

        ArrayNode events = root.putArray("events");
        for (Outbox o : batch) {
            ObjectNode e = events.addObject();
            e.put("seq", o.getSeq());
            e.put("domain", o.getDomain());
            e.put("event_type", o.getEventType());
            e.put("source", o.getSource());
            e.set("payload", objectMapper.readTree(o.getPayload()));
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }

    private void writeGzip(Path path, byte[] data) throws IOException {
        try (GZIPOutputStream gz = new GZIPOutputStream(new FileOutputStream(path.toFile()))) {
            gz.write(data);
        }
    }
}
