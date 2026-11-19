package com.azhukov.agent.shared.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.azhukov.agent.shared.dto.ToolApprovalDecision.Action.APPROVE;
import static com.azhukov.agent.shared.dto.ToolApprovalDecision.Action.DENY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolApprovalDecisionContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void bothActionsUseTheSameRequestIdContract() throws Exception {
        UUID id = UUID.randomUUID();
        for (var action : ToolApprovalDecision.Action.values()) {
            var decision = new ToolApprovalDecision(action, false, id, "reviewed");
            var wire = json.readTree(json.writeValueAsString(decision));
            assertThat(wire.path("action").asText()).isEqualTo(action.name());
            assertThat(wire.path("requestId").asText()).isEqualTo(id.toString());
            assertThat(wire.path("note").asText()).isEqualTo("reviewed");
            assertThat(json.treeToValue(wire, ToolApprovalDecision.class)).isEqualTo(decision);
        }
    }

    @Test
    void commandSelectionUsesAnExactIdOrExplicitAll() {
        UUID id = UUID.randomUUID();
        assertThat(ToolApprovalDecision.fromCommand(DENY, id.toString()))
            .isEqualTo(new ToolApprovalDecision(DENY, false, id, null));
        assertThat(ToolApprovalDecision.fromCommand(APPROVE, " All "))
            .isEqualTo(new ToolApprovalDecision(APPROVE, true, null, null));
        assertThat(ToolApprovalDecision.fromCommand(DENY, ""))
            .isEqualTo(new ToolApprovalDecision(DENY, false, null, null));
        assertThatThrownBy(() -> ToolApprovalDecision.fromCommand(DENY, "1-1-1-1-1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"action\":\"ALLOW\"}",
        "{\"action\":\"APPROVE\",\"scope\":\"session\"}",
        "{\"action\":\"DENY\",\"decision\":\"APPROVE\"}",
        "{\"action\":\"DENY\",\"all\":true,\"requestId\":\"550e8400-e29b-41d4-a716-446655440000\"}"
    })
    void rejectsUnknownOrConflictingInstructions(String body) {
        assertThatThrownBy(() -> json.readValue(body, ToolApprovalDecision.class))
            .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
