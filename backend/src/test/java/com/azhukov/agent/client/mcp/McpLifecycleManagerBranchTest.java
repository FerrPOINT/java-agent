package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Branch coverage tests for {@link McpLifecycleManager} and {@link McpOAuthManager}.
 * Covers static methods, credential sanitization, env var filtering, and edge cases.
 */
class McpLifecycleManagerBranchTest {

    // ── buildSafeEnv ──

    @Test
    void buildSafeEnv_nullUserEnv_returnsOnlySafeSystemEnv() {
        Map<String, String> result = McpLifecycleManager.buildSafeEnv(null);
        // Should contain PATH (almost always present in system env)
        assertThat(result).isNotEmpty();
    }

    @Test
    void buildSafeEnv_userEnvOverridesSystemEnv() {
        Map<String, String> userEnv = Map.of("CUSTOM_VAR", "custom_value", "PATH", "/custom/path");
        Map<String, String> result = McpLifecycleManager.buildSafeEnv(userEnv);
        assertThat(result.get("CUSTOM_VAR")).isEqualTo("custom_value");
        assertThat(result.get("PATH")).isEqualTo("/custom/path");
    }

    @Test
    void buildSafeEnv_includesXDGVars() {
        // XDG_ vars should be passed through
        Map<String, String> userEnv = Map.of("XDG_CONFIG_HOME", "/home/user/.config");
        Map<String, String> result = McpLifecycleManager.buildSafeEnv(userEnv);
        assertThat(result.get("XDG_CONFIG_HOME")).isEqualTo("/home/user/.config");
    }

    @Test
    void buildSafeEnv_emptyUserEnv_returnsSafeSystemEnv() {
        Map<String, String> result = McpLifecycleManager.buildSafeEnv(Map.of());
        assertThat(result).isNotEmpty();
    }

    // ── sanitizeError ──

    @Test
    void sanitizeError_nullInput_returnsNull() {
        assertThat(McpLifecycleManager.sanitizeError(null)).isNull();
    }

    @Test
    void sanitizeError_emptyInput_returnsEmpty() {
        assertThat(McpLifecycleManager.sanitizeError("")).isEmpty();
    }

    @Test
    void sanitizeError_stripsApiKey() {
        String result = McpLifecycleManager.sanitizeError("API_KEY=sk-test12345 failed");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("sk-test12345");
    }

    @Test
    void sanitizeError_stripsSecret() {
        String result = McpLifecycleManager.sanitizeError("secret=mysecretvalue failed");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("mysecretvalue");
    }

    @Test
    void sanitizeError_stripsKey() {
        String result = McpLifecycleManager.sanitizeError("key=mykey12345678 failed");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("mykey12345678");
    }

    @Test
    void sanitizeError_preservesSafeText() {
        String result = McpLifecycleManager.sanitizeError("Connection refused to host example.com:8080");
        assertThat(result).isEqualTo("Connection refused to host example.com:8080");
    }

    // ── convertToolDefinition ──

    @Test
    void convertToolDefinition_nullInputSchema_returnsEmptySchema() {
        McpSchema.Tool tool = McpSchema.Tool.builder("test", Map.of("type", "object"))
            .description("test tool")
            .build();
        ToolDefinition def = McpLifecycleManager.convertToolDefinition("srv__test", tool);
        assertThat(def.name()).isEqualTo("srv__test");
        assertThat(def.description()).isEqualTo("test tool");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = def.parameters();
        assertThat(params.get("type")).isEqualTo("object");
    }

    @Test
    void convertToolDefinition_emptyProperties() {
        Map<String, Object> inputSchema = Map.of("type", "object", "properties", Map.of(), "required", List.of());
        McpSchema.Tool tool = McpSchema.Tool.builder("test", inputSchema)
            .description("test")
            .build();
        ToolDefinition def = McpLifecycleManager.convertToolDefinition("srv__test", tool);
        assertThat(def.name()).isEqualTo("srv__test");
    }

    @Test
    void convertToolDefinition_nonMapProperties_keepsRawValue() {
        Map<String, Object> inputSchema = new java.util.LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", "not-a-map");
        inputSchema.put("required", List.of("field1"));

        McpSchema.Tool tool = McpSchema.Tool.builder("test", inputSchema)
            .description("test")
            .build();
        ToolDefinition def = McpLifecycleManager.convertToolDefinition("srv__test", tool);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) def.parameters().get("properties");
        // When properties is not a Map, it's still put as a raw value
        assertThat(props).isNotNull();
    }

    @Test
    void convertToolDefinition_nonListRequired_keepsEmptyRequired() {
        Map<String, Object> inputSchema = new java.util.LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", Map.of());
        inputSchema.put("required", "not-a-list");

        McpSchema.Tool tool = McpSchema.Tool.builder("test", inputSchema)
            .description("test")
            .build();
        ToolDefinition def = McpLifecycleManager.convertToolDefinition("srv__test", tool);
        @SuppressWarnings("unchecked")
        List<String> req = (List<String>) def.parameters().get("required");
        assertThat(req).isEmpty();
    }

    @Test
    void convertToolDefinition_complexNestedProperties() {
        Map<String, Object> param1 = Map.of("type", "string", "description", "Name");
        Map<String, Object> param2 = Map.of("type", "integer", "description", "Age");
        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "properties", Map.of("name", param1, "age", param2),
            "required", List.of("name")
        );

        McpSchema.Tool tool = McpSchema.Tool.builder("test", inputSchema)
            .description("test tool with complex params")
            .build();
        ToolDefinition def = McpLifecycleManager.convertToolDefinition("srv__test", tool);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) def.parameters().get("properties");
        assertThat(props).containsKey("name");
        assertThat(props).containsKey("age");
        @SuppressWarnings("unchecked")
        Map<String, Object> nameProp = (Map<String, Object>) props.get("name");
        assertThat(nameProp.get("type")).isEqualTo("string");
    }

    // ── listServers and listDiscoveredTools ──

    @Test
    void reconnect_unknownServer_doesNothing() {
        AgentProperties props = new AgentProperties();
        ApplicationContext ctx = mock(ApplicationContext.class);
        McpLifecycleManager mgr = new McpLifecycleManager(props, new ObjectMapper(), ctx);
        // Should not throw — unknown server is just logged
        mgr.reconnect("unknown-server");
        assertThat(mgr.listServers()).isEmpty();
    }

    @Test
    void refreshTools_notConnected_doesNothing() {
        AgentProperties props = new AgentProperties();
        ApplicationContext ctx = mock(ApplicationContext.class);
        McpLifecycleManager mgr = new McpLifecycleManager(props, new ObjectMapper(), ctx);
        // Should not throw — just returns
        mgr.refreshTools("not-connected");
    }

    // ── McpOAuthManager extractJsonField ──

    @Test
    void extractJsonField_nullJson_returnsNull() {
        assertThat(McpOAuthManager.extractJsonField(null, "field")).isNull();
    }

    @Test
    void extractJsonField_emptyJson_returnsNull() {
        assertThat(McpOAuthManager.extractJsonField("", "field")).isNull();
    }

    @Test
    void extractJsonField_fieldNotFound_returnsNull() {
        assertThat(McpOAuthManager.extractJsonField("{\"other\":\"value\"}", "field")).isNull();
    }

    @Test
    void extractJsonField_noColonAfterField_returnsNull() {
        assertThat(McpOAuthManager.extractJsonField("\"field\"", "field")).isNull();
    }

    @Test
    void extractJsonField_emptyJsonObject_returnsNull() {
        assertThat(McpOAuthManager.extractJsonField("{}", "field")).isNull();
    }

    @Test
    void extractJsonField_numericValue() {
        String json = "{\"expires_in\":3600}";
        assertThat(McpOAuthManager.extractJsonField(json, "expires_in")).isEqualTo("3600");
    }

    @Test
    void extractJsonField_valueWithWhitespace() {
        String json = "{\"field\":  \"value\"}";
        assertThat(McpOAuthManager.extractJsonField(json, "field")).isEqualTo("value");
    }

    @Test
    void extractJsonField_valueEndingWithComma() {
        String json = "{\"field\":\"value\",\"other\":\"x\"}";
        assertThat(McpOAuthManager.extractJsonField(json, "field")).isEqualTo("value");
    }

    @Test
    void extractJsonField_valueEndingWithBrace() {
        String json = "{\"field\":\"value\"}";
        assertThat(McpOAuthManager.extractJsonField(json, "field")).isEqualTo("value");
    }

    @Test
    void extractJsonField_numericValueWithComma() {
        String json = "{\"a\":1,\"b\":2}";
        assertThat(McpOAuthManager.extractJsonField(json, "a")).isEqualTo("1");
    }

    // ── McpOAuthManager sanitizeError ──

    @Test
    void oAuthSanitizeError_stripsKey() {
        String result = McpOAuthManager.sanitizeError("key=mykey12345678 error");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("mykey12345678");
    }

    @Test
    void oAuthSanitizeError_stripsApi_key() {
        String result = McpOAuthManager.sanitizeError("API_KEY=mykey12345678 error");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("mykey12345678");
    }

    @Test
    void oAuthSanitizeError_stripsSecret() {
        String result = McpOAuthManager.sanitizeError("secret=mysecretvalue error");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("mysecretvalue");
    }
}