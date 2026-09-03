package com.azhukov.agent.tools.browser;

import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

final class BrowserToolResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BrowserToolResponses() {
    }

    static String success(String key, String value) throws JsonProcessingException {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", true);
        response.put(key, value);
        return MAPPER.writeValueAsString(response);
    }

    static String success(String firstKey, String firstValue, String secondKey, String secondValue)
        throws JsonProcessingException {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", true);
        response.put(firstKey, firstValue);
        response.put(secondKey, secondValue);
        return MAPPER.writeValueAsString(response);
    }

    static String success(Map<String, ?> values) throws JsonProcessingException {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", true);
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            response.putPOJO(entry.getKey(), entry.getValue());
        }
        return MAPPER.writeValueAsString(response);
    }

    static String failure(String error) throws JsonProcessingException {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", false);
        response.put("error", error);
        return MAPPER.writeValueAsString(response);
    }

    static ToolResult failureResult(String error) {
        try {
            return new ToolResult(false, failure(error), error);
        } catch (JsonProcessingException e) {
            return new ToolResult(false, "{\"success\":false,\"error\":\"Browser operation failed\"}", error);
        }
    }

    static boolean looksLikeFailure(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return true;
        }
        String lower = rawResult.toLowerCase();
        return lower.startsWith("element not found:")
            || lower.startsWith("evaluation error:")
            || lower.startsWith("navigation error:")
            || lower.startsWith("dialog error:")
            || lower.startsWith("url blocked by safety policy:")
            || lower.startsWith("blocked by website policy:")
            || lower.startsWith("invalid url:")
            || lower.startsWith("url is empty")
            || lower.startsWith("url has no host")
            || lower.startsWith("only http and https schemes are allowed")
            || lower.contains("not typeable")
            || lower.equals("no element");
    }
}
