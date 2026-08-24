package com.azhukov.agent.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallDeterministicIdTest {

    @Test
    void deterministicIdStable() {
        String id1 = ToolCall.deterministicCallId("write_file", "{\"path\":\"/tmp/a.txt\"}", 0);
        String id2 = ToolCall.deterministicCallId("write_file", "{\"path\":\"/tmp/a.txt\"}", 0);
        assertThat(id1).isEqualTo(id2);
        assertThat(id1).startsWith("call_");
        assertThat(id1).hasSize(17); // "call_" + 12 hex chars
    }

    @Test
    void differentArgsProduceDifferentIds() {
        String id1 = ToolCall.deterministicCallId("write_file", "{\"path\":\"/tmp/a.txt\"}", 0);
        String id2 = ToolCall.deterministicCallId("write_file", "{\"path\":\"/tmp/b.txt\"}", 0);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void differentIndexProducesDifferentIds() {
        String id0 = ToolCall.deterministicCallId("read_file", "{\"path\":\"a\"}", 0);
        String id1 = ToolCall.deterministicCallId("read_file", "{\"path\":\"a\"}", 1);
        assertThat(id0).isNotEqualTo(id1);
    }

    @Test
    void differentNameProducesDifferentIds() {
        String id1 = ToolCall.deterministicCallId("write_file", "{}", 0);
        String id2 = ToolCall.deterministicCallId("read_file", "{}", 0);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void matchesHermesFormat() {
        // Hermes: sha256(f"{fn_name}:{arguments}:{index}").hexdigest()[:12]
        // Java: SHA-256 first 6 bytes → 12 hex chars, prefixed "call_"
        String id = ToolCall.deterministicCallId("web_search", "{\"query\":\"test\"}", 0);
        assertThat(id).startsWith("call_");
        assertThat(id.substring(5)).hasSize(12);
        // Verify it's all hex
        assertThat(id.substring(5)).matches("[0-9a-f]{12}");
    }

    @Test
    void emptyArgumentsWork() {
        String id = ToolCall.deterministicCallId("ping", "", 0);
        assertThat(id).startsWith("call_");
    }

    @Test
    void nullArgumentsHandled() {
        // Should not throw — SHA-256 handles null via UTF-8 conversion
        String id = ToolCall.deterministicCallId("ping", null, 0);
        assertThat(id).startsWith("call_");
    }
}