package com.azhukov.agent.client.langchain4j;

import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LangChain4jToolSchemaMapper {

    private LangChain4jToolSchemaMapper() {}

    static JsonObjectSchema toJsonObjectSchema(Map<String, Object> schema) {
        if (schema == null) {
            return JsonObjectSchema.builder().build();
        }
        JsonSchemaElement element = toJsonSchemaElement(schema, true);
        if (element instanceof JsonObjectSchema objectSchema) {
            return objectSchema;
        }
        return JsonObjectSchema.builder().build();
    }

    private static JsonSchemaElement toJsonSchemaElement(Object raw) {
        return toJsonSchemaElement(raw, false);
    }

    private static JsonSchemaElement toJsonSchemaElement(Object raw, boolean topLevel) {
        if (raw instanceof String typeName) {
            return schemaForType(typeName, "", Map.of());
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return JsonStringSchema.builder().build();
        }
        Map<String, Object> spec = stringKeyMap(rawMap);
        String description = descriptionOf(spec);

        if (topLevel && isObjectLike(spec)) {
            return objectSchema(description, spec);
        }

        List<JsonSchemaElement> anyOf = unionSchemas(spec.get("anyOf"));
        if (anyOf.isEmpty()) {
            anyOf = unionSchemas(spec.get("oneOf"));
        }
        if (anyOf.size() == 1) {
            return anyOf.get(0);
        }
        if (anyOf.size() > 1) {
            return JsonAnyOfSchema.builder()
                .description(description)
                .anyOf(anyOf)
                .build();
        }

        List<String> enumValues = stringList(spec.get("enum"));
        if (!enumValues.isEmpty()) {
            return JsonEnumSchema.builder()
                .description(description)
                .enumValues(enumValues)
                .build();
        }

        Object type = spec.get("type");
        if (type instanceof List<?> types) {
            return schemaForTypeList(types, description, spec);
        }
        if (type instanceof String typeName) {
            return schemaForType(typeName, description, spec);
        }
        if (spec.containsKey("properties") || spec.containsKey("additionalProperties")) {
            return objectSchema(description, spec);
        }
        if (spec.containsKey("items")) {
            return arraySchema(description, spec);
        }
        return JsonStringSchema.builder().description(description).build();
    }

    private static JsonSchemaElement schemaForTypeList(List<?> types, String description, Map<String, Object> spec) {
        List<JsonSchemaElement> variants = new ArrayList<>();
        for (Object item : types) {
            if (!(item instanceof String typeName)) {
                continue;
            }
            if ("null".equals(typeName) && types.size() > 1) {
                continue;
            }
            variants.add(schemaForType(typeName, description, spec));
        }
        if (variants.isEmpty()) {
            return JsonStringSchema.builder().description(description).build();
        }
        if (variants.size() == 1) {
            return variants.get(0);
        }
        return JsonAnyOfSchema.builder()
            .description(description)
            .anyOf(variants)
            .build();
    }

    private static JsonSchemaElement schemaForType(String type, String description, Map<String, Object> spec) {
        return switch (type) {
            case "object" -> objectSchema(description, spec);
            case "array" -> arraySchema(description, spec);
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "null" -> new JsonNullSchema();
            case "string" -> JsonStringSchema.builder().description(description).build();
            default -> JsonStringSchema.builder().description(description).build();
        };
    }

    private static JsonObjectSchema objectSchema(String description, Map<String, Object> spec) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder()
            .description(description);
        Map<String, JsonSchemaElement> properties = properties(spec.get("properties"));
        if (!properties.isEmpty()) {
            builder.addProperties(properties);
        }
        List<String> required = required(spec.get("required"), properties);
        if (!required.isEmpty()) {
            builder.required(required);
        }
        Object additionalProperties = spec.get("additionalProperties");
        if (additionalProperties instanceof Boolean allowed) {
            builder.additionalProperties(allowed);
        }
        return builder.build();
    }

    private static JsonArraySchema arraySchema(String description, Map<String, Object> spec) {
        JsonSchemaElement items = spec.containsKey("items")
            ? toJsonSchemaElement(spec.get("items"))
            : JsonStringSchema.builder().build();
        return JsonArraySchema.builder()
            .description(description)
            .items(items)
            .build();
    }

    private static Map<String, JsonSchemaElement> properties(Object rawProperties) {
        if (!(rawProperties instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            properties.put(entry.getKey().toString(), toJsonSchemaElement(entry.getValue()));
        }
        return properties;
    }

    private static List<String> required(Object rawRequired, Map<String, JsonSchemaElement> properties) {
        if (!(rawRequired instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .filter(properties::containsKey)
            .distinct()
            .toList();
    }

    private static List<JsonSchemaElement> unionSchemas(Object rawUnion) {
        if (!(rawUnion instanceof List<?> rawList)) {
            return List.of();
        }
        List<JsonSchemaElement> variants = rawList.stream()
            .map(LangChain4jToolSchemaMapper::toJsonSchemaElement)
            .toList();
        List<JsonSchemaElement> nonNull = variants.stream()
            .filter(element -> !(element instanceof JsonNullSchema))
            .toList();
        return nonNull.isEmpty() ? variants : nonNull;
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
            .filter(item -> item != null)
            .map(Object::toString)
            .distinct()
            .toList();
    }

    private static String descriptionOf(Map<String, Object> spec) {
        Object desc = spec.get("description");
        return desc != null ? desc.toString() : "";
    }

    private static boolean isObjectLike(Map<String, Object> spec) {
        Object type = spec.get("type");
        return "object".equals(type)
            || spec.containsKey("properties")
            || spec.containsKey("additionalProperties");
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> rawMap) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return result;
    }
}
