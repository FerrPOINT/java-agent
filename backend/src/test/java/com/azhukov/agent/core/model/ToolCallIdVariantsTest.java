package com.azhukov.agent.core.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-01 (Hermes parity audit 2026-08-27): tool-call id alias policy.
 * Mirrors Hermes message_sanitization.py: _expand_tool_id_variants /
 * tool_call_id_variants / tool_result_id_variants / coalesce_tool_call_id.
 */
class ToolCallIdVariantsTest {

    @Test
    void plainIdYieldsSingleVariant() {
        ToolCall tc = new ToolCall("call_1", "read_file", "{}");
        assertThat(tc.idVariants()).containsExactlyInAnyOrder("call_1");
        assertThat(tc.pairingId()).isEqualTo("call_1");
    }

    @Test
    void compositeIdExpandsBothHalves() {
        ToolCall tc = new ToolCall("call_x|fc_y", "search", "{}");
        assertThat(tc.idVariants()).containsExactlyInAnyOrder("call_x|fc_y", "call_x", "fc_y");
        // Pairing key = the call half (what providers enforce per turn)
        assertThat(tc.pairingId()).isEqualTo("call_x");
    }

    @Test
    void separateCallIdAndResponseItemIdBothMatch() {
        ToolCall tc = new ToolCall("call_a", "call_a", "fc_b", "write_file", "{}");
        assertThat(tc.idVariants()).containsExactlyInAnyOrder("call_a", "fc_b");
        assertThat(tc.pairingId()).isEqualTo("call_a");
    }

    @Test
    void resultSideExpandsComposite() {
        Set<String> variants = ToolCall.resultIdVariants("call_x|fc_y");
        assertThat(variants).containsExactlyInAnyOrder("call_x|fc_y", "call_x", "fc_y");
        // A result registered under either half matches the call above
        assertThat(variants).containsAnyOf("call_x");
    }

    @Test
    void aliasGroupsIntersectForMatching() {
        ToolCall call = new ToolCall("call_x|fc_y", "search", "{}");
        // A result stored with only the response-item half still matches
        assertThat(call.idVariants()).contains("fc_y");
        // ...and a result stored with only the call half too
        assertThat(call.idVariants()).contains("call_x");
    }

    @Test
    void nullAndBlankIdsYieldNoUsefulVariants() {
        assertThat(ToolCall.resultIdVariants(null)).isEmpty();
        assertThat(ToolCall.resultIdVariants("  ")).isEmpty();
        // Keep the raw malformed wire value for diagnostics; it has no
        // matching call-side alias and is therefore safely rejected later.
        assertThat(ToolCall.resultIdVariants("||")).containsExactly("||");
    }

    @Test
    void withPairingIdPreservesResponseItemHalf() {
        ToolCall composite = new ToolCall("call_x|fc_y", "search", "{}");
        ToolCall renamed = composite.withPairingId("call_x_d2");
        assertThat(renamed.pairingId()).isEqualTo("call_x_d2");
        // The renamed id keeps the composite form so the item half survives
        assertThat(renamed.idVariants()).contains("call_x_d2", "fc_y");
    }

    @Test
    void withPairingIdOnPlainIdReplacesId() {
        ToolCall plain = new ToolCall("call_1", "search", "{}");
        ToolCall renamed = plain.withPairingId("call_1_d2");
        assertThat(renamed.pairingId()).isEqualTo("call_1_d2");
        assertThat(renamed.idVariants()).containsExactly("call_1_d2");
    }

    @Test
    void withPairingIdPrefersSeparateCallIdField() {
        ToolCall split = new ToolCall("call_a", "call_a", "fc_b", "search", "{}");
        ToolCall renamed = split.withPairingId("call_a_d2");
        assertThat(renamed.pairingId()).isEqualTo("call_a_d2");
        assertThat(renamed.idVariants()).containsExactlyInAnyOrder("call_a_d2", "fc_b");
    }

    @Test
    void legacyThreeArgConstructorStillWorks() {
        ToolCall tc = new ToolCall("call_1", "read_file", "{}");
        assertThat(tc.id()).isEqualTo("call_1");
        assertThat(tc.name()).isEqualTo("read_file");
        assertThat(tc.arguments()).isEqualTo("{}");
        assertThat(tc.callId()).isNull();
        assertThat(tc.responseItemId()).isNull();
    }
}
