package com.azhukov.agent.client.langchain4j;

import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jToolSchemaMapperTest {

    @Test
    void preservesPrimitiveArrayObjectEnumAndRequiredTypes() {
        Map<String, Object> nestedProperties = new LinkedHashMap<>();
        nestedProperties.put("count", Map.of("type", "integer", "description", "count"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("enabled", Map.of("type", "boolean", "description", "enabled"));
        properties.put("limit", Map.of("type", "integer"));
        properties.put("score", Map.of("type", "number"));
        properties.put("tags", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("payload", Map.of("type", "object", "properties", nestedProperties));
        properties.put("mode", Map.of("type", "string", "enum", List.of("fast", "deep")));

        JsonObjectSchema schema = LangChain4jToolSchemaMapper.toJsonObjectSchema(Map.of(
            "type", "object",
            "properties", properties,
            "required", List.of("enabled", "missing", "tags")
        ));

        assertThat(schema.properties().get("enabled")).isInstanceOf(JsonBooleanSchema.class);
        assertThat(schema.properties().get("limit")).isInstanceOf(JsonIntegerSchema.class);
        assertThat(schema.properties().get("score")).isInstanceOf(JsonNumberSchema.class);
        assertThat(schema.properties().get("mode")).isInstanceOf(JsonEnumSchema.class);
        assertThat(schema.required()).containsExactly("enabled", "tags");

        JsonArraySchema tags = (JsonArraySchema) schema.properties().get("tags");
        assertThat(tags.items()).isInstanceOf(JsonStringSchema.class);

        JsonObjectSchema payload = (JsonObjectSchema) schema.properties().get("payload");
        assertThat(payload.properties().get("count")).isInstanceOf(JsonIntegerSchema.class);
    }

    @Test
    void handlesAnyOfAndDefaultsBareArraysToStringItems() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("value", Map.of("anyOf", List.of(
            Map.of("type", "string"),
            Map.of("type", "object", "properties", Map.of())
        )));
        properties.put("names", Map.of("type", "array"));

        JsonObjectSchema schema = LangChain4jToolSchemaMapper.toJsonObjectSchema(Map.of(
            "type", "object",
            "properties", properties
        ));

        assertThat(schema.properties().get("value")).isInstanceOf(JsonAnyOfSchema.class);
        JsonArraySchema names = (JsonArraySchema) schema.properties().get("names");
        assertThat(names.items()).isInstanceOf(JsonStringSchema.class);
    }

    @Test
    void stripsTopLevelCombinatorsWithoutDroppingObjectPropertiesLikeHermes() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "query"));

        JsonObjectSchema schema = LangChain4jToolSchemaMapper.toJsonObjectSchema(Map.of(
            "type", "object",
            "properties", properties,
            "required", List.of("query"),
            "anyOf", List.of(Map.of("required", List.of("query")))
        ));

        assertThat(schema.properties()).containsKey("query");
        assertThat(schema.properties().get("query")).isInstanceOf(JsonStringSchema.class);
        assertThat(schema.required()).containsExactly("query");
    }

    @Test
    void collapsesNullableUnionsToNonNullBranchLikeHermes() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("maybe", Map.of("anyOf", List.of(
            Map.of("type", "string", "description", "optional text"),
            Map.of("type", "null")
        )));
        properties.put("typed", Map.of("type", List.of("string", "null")));

        JsonObjectSchema schema = LangChain4jToolSchemaMapper.toJsonObjectSchema(Map.of(
            "type", "object",
            "properties", properties
        ));

        assertThat(schema.properties().get("maybe")).isInstanceOf(JsonStringSchema.class);
        assertThat(schema.properties().get("typed")).isInstanceOf(JsonStringSchema.class);
    }
}
