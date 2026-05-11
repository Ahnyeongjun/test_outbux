package io.github.ahnyeongjun.outbox.capture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ahnyeongjun.outbox.model.Outbox;
import lombok.AllArgsConstructor;
import lombok.Getter;

class DefaultOutboxConverterTest {

    private DefaultOutboxConverter converter;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        converter = new DefaultOutboxConverter(mapper);
    }

    @Test
    void convert_setsDomainEventTypeAndSource() {
        Outbox outbox = converter.convert(new User(1L, "alice", "secret"), "USER", "CREATED");

        assertThat(outbox.getDomain()).isEqualTo("USER");
        assertThat(outbox.getEventType()).isEqualTo("CREATED");
        assertThat(outbox.getSource()).isEqualTo("INTERNAL");
        assertThat(outbox.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void convert_excludesSensitiveFields() throws Exception {
        Outbox outbox = converter.convert(
                new Account(1L, "alice", "p@ss", "tk-abc", "ak-xyz"),
                "ACCOUNT", "UPDATED");

        JsonNode payload = mapper.readTree(outbox.getPayload());
        assertThat(payload.has("password")).isFalse();
        assertThat(payload.has("token")).isFalse();
        assertThat(payload.has("apiKey")).isFalse();
        assertThat(payload.get("id").asLong()).isEqualTo(1L);
        assertThat(payload.get("username").asText()).isEqualTo("alice");
    }

    @Test
    void convert_fallsBackToEmptyPayloadOnSerializationFailure() {
        Outbox outbox = converter.convert(new Unserializable(), "ANY", "CREATED");

        assertThat(outbox.getPayload()).isEqualTo("{}");
    }

    @Test
    void safeMapper_doesNotPolluteBaseMapper() throws Exception {
        converter.convert(new Account(1L, "a", "p", "t", "k"), "ACCOUNT", "CREATED");

        // base mapper 는 필터를 갖지 않으므로 평범하게 동작해야 함
        String json = mapper.writeValueAsString(new User(1L, "u", "secret"));
        assertThat(json).contains("password");
    }

    @Getter
    @AllArgsConstructor
    static class User {
        private Long id;
        private String username;
        private String password;
    }

    @Getter
    @AllArgsConstructor
    static class Account {
        private Long id;
        private String username;
        private String password;
        private String token;
        private String apiKey;
    }

    static class Unserializable {
        public String getBoom() { throw new RuntimeException("boom"); }
    }
}
