package com.azhukov.agent.client.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.lang.reflect.Type;

public class JacksonMcpJsonMapper implements McpJsonMapper {

    private final ObjectMapper objectMapper;

    public JacksonMcpJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> T readValue(String s, Class<T> aClass) throws IOException {
        return objectMapper.readValue(s, aClass);
    }

    @Override
    public <T> T readValue(byte[] bytes, Class<T> aClass) throws IOException {
        return objectMapper.readValue(bytes, aClass);
    }

    @Override
    public <T> T readValue(String s, TypeRef<T> typeRef) throws IOException {
        return objectMapper.readValue(s, new TypeReference<T>() {
            @Override
            public Type getType() {
                return typeRef.getType();
            }
        });
    }

    @Override
    public <T> T readValue(byte[] bytes, TypeRef<T> typeRef) throws IOException {
        return objectMapper.readValue(bytes, new TypeReference<T>() {
            @Override
            public Type getType() {
                return typeRef.getType();
            }
        });
    }

    @Override
    public <T> T convertValue(Object o, Class<T> aClass) {
        return objectMapper.convertValue(o, aClass);
    }

    @Override
    public <T> T convertValue(Object o, TypeRef<T> typeRef) {
        return objectMapper.convertValue(o, new TypeReference<T>() {
            @Override
            public Type getType() {
                return typeRef.getType();
            }
        });
    }

    @Override
    public String writeValueAsString(Object o) throws IOException {
        return objectMapper.writeValueAsString(o);
    }

    @Override
    public byte[] writeValueAsBytes(Object o) throws IOException {
        return objectMapper.writeValueAsBytes(o);
    }
}
