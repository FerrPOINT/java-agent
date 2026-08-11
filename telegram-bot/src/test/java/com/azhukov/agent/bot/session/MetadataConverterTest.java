package com.azhukov.agent.bot.session;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataConverterTest {

    private final MetadataConverter converter = new MetadataConverter();

    @Test
    void convertToDatabaseColumn_serializesMapToJson() {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        map.put("goal", "test the code");
        map.put("subgoal", "write tests");

        String json = converter.convertToDatabaseColumn(map);

        assertThat(json).contains("goal");
        assertThat(json).contains("test the code");
        assertThat(json).contains("subgoal");
        assertThat(json).contains("write tests");
    }

    @Test
    void convertToDatabaseColumn_emptyMapReturnsNull() {
        assertThat(converter.convertToDatabaseColumn(new ConcurrentHashMap<>())).isNull();
    }

    @Test
    void convertToDatabaseColumn_nullMapReturnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void convertToEntityAttribute_deserializesJsonToMap() {
        String json = "{\"goal\":\"test\",\"subgoal\":\"verify\"}";

        ConcurrentHashMap<String, String> result = converter.convertToEntityAttribute(json);

        assertThat(result).isNotEmpty();
        assertThat(result.get("goal")).isEqualTo("test");
        assertThat(result.get("subgoal")).isEqualTo("verify");
    }

    @Test
    void convertToEntityAttribute_nullReturnsEmptyMap() {
        ConcurrentHashMap<String, String> result = converter.convertToEntityAttribute(null);

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void convertToEntityAttribute_blankReturnsEmptyMap() {
        ConcurrentHashMap<String, String> result = converter.convertToEntityAttribute("   ");

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void convertToEntityAttribute_invalidJsonReturnsEmptyMap() {
        ConcurrentHashMap<String, String> result = converter.convertToEntityAttribute("not json at all {{{");

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void roundTrip_preservesData() {
        ConcurrentHashMap<String, String> original = new ConcurrentHashMap<>();
        original.put("key1", "value1");
        original.put("key2", "value2");
        original.put("key3", "value with spaces");

        String json = converter.convertToDatabaseColumn(original);
        ConcurrentHashMap<String, String> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void convertToEntityAttribute_emptyJsonObjectReturnsEmptyMap() {
        ConcurrentHashMap<String, String> result = converter.convertToEntityAttribute("{}");

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void convertToDatabaseColumn_specialCharacters() {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        map.put("path", "/home/user/file.txt");
        map.put("json", "{\"nested\":\"value\"}");

        String json = converter.convertToDatabaseColumn(map);
        ConcurrentHashMap<String, String> restored = converter.convertToEntityAttribute(json);

        assertThat(restored.get("path")).isEqualTo("/home/user/file.txt");
        assertThat(restored.get("json")).isEqualTo("{\"nested\":\"value\"}");
    }
}