package com.azhukov.agent.bot.session;

import com.azhukov.agent.bot.config.SharedObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.concurrent.ConcurrentHashMap;
/**
 * JPA {@link AttributeConverter} that serializes a {@link ConcurrentHashMap} to a JSON string
 * for storage in the {@code bot_sessions.metadata} column and deserializes it back.
 * <p>
 * On null or deserialization error, returns an empty {@link ConcurrentHashMap} so the entity
 * always has a usable metadata map.
 */
@Converter
public class MetadataConverter implements AttributeConverter<ConcurrentHashMap<String, String>, String> {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = SharedObjectMapper.get();
    private static final TypeReference<ConcurrentHashMap<String, String>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(ConcurrentHashMap<String, String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public ConcurrentHashMap<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ConcurrentHashMap<>();
        }
        try {
            ConcurrentHashMap<String, String> result = MAPPER.readValue(dbData, TYPE);
            return result != null ? result : new ConcurrentHashMap<>();
        } catch (Exception e) {
            return new ConcurrentHashMap<>();
        }
    }
}