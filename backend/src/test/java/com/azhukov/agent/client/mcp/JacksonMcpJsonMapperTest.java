package com.azhukov.agent.client.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonMcpJsonMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(mapper);

    @Test
    void readValueFromString() throws IOException {
        Map<String, Object> r = jsonMapper.readValue("{\"x\":1}", Map.class);
        assertThat(r).containsEntry("x", 1);
    }

    @Test
    void readValueFromBytes() throws IOException {
        Map<String, Object> r = jsonMapper.readValue("{\"x\":1}".getBytes(), Map.class);
        assertThat(r).containsEntry("x", 1);
    }

    @Test
    void readValueTypeRefFromString() throws IOException {
        Map<String, Object> r = jsonMapper.readValue("{\"x\":1}", new TypeRef<>() {
            @Override public java.lang.reflect.Type getType() { return new TypeReference<Map<String, Object>>() {}.getType(); }
        });
        assertThat(r).containsEntry("x", 1);
    }

    @Test
    void writeValueAsString() throws IOException {
        assertThat(jsonMapper.writeValueAsString(Map.of("x", 1))).contains("x");
    }

    @Test
    void readValueTypeRefFromBytes() throws IOException {
        Map<String, Object> r = jsonMapper.readValue("{\"x\":1}".getBytes(), new TypeRef<>() {
            @Override public java.lang.reflect.Type getType() { return new TypeReference<Map<String, Object>>() {}.getType(); }
        });
        assertThat(r).containsEntry("x", 1);
    }

    @Test
    void convertValueClass() {
        Map<String, Object> r = jsonMapper.convertValue(Map.of("x", 1), Map.class);
        assertThat(r).containsEntry("x", 1);
    }

    @Test
    void convertValueTypeRef() {
        Map<String, Object> r = jsonMapper.convertValue(Map.of("x", 1), new TypeRef<>() {
            @Override public java.lang.reflect.Type getType() { return new TypeReference<Map<String, Object>>() {}.getType(); }
        });
        assertThat(r).containsEntry("x", 1);
    }
}
