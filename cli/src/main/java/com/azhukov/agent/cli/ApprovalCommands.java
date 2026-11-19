package com.azhukov.agent.cli;

import com.azhukov.agent.shared.dto.ToolApprovalDecision;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * c8: Approval, steer, and stop slash commands.
 * <p>
 * Includes: approve, deny, approvals, stop, steer.
 */
@Component
public class ApprovalCommands implements CommandGroup {

    @Override
    public void registerAll(SlashCommandRegistry registry) {
        registry.register("approve", "Approve one request, a request ID, or all", (args, client, sessionId) ->
            decide(client, ToolApprovalDecision.Action.APPROVE, args, "approve"));

        registry.register("deny", "Deny one request, a request ID, or all", (args, client, sessionId) ->
            decide(client, ToolApprovalDecision.Action.DENY, args, "deny"));

        registry.register("approvals", "List pending tool approvals", (args, client, sessionId) -> {
            JsonNode approvals = client.listPendingApprovals();
            return client.prettyPrint(approvals);
        });

        registry.register("stop", "Stop the agent's current turn", (args, client, sessionId) ->
            client.stopAgent(sessionId));

        registry.register("steer", "Inject a steer note into the active turn", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Usage: /steer <message>";
            }
            return client.steer(args, sessionId);
        });
    }

    private static String decide(BackendClient client, ToolApprovalDecision.Action action,
                                 String arguments, String command) {
        try {
            return client.decideApproval(ToolApprovalDecision.fromCommand(action, arguments));
        } catch (IllegalArgumentException e) {
            return "Usage: /" + command + " [requestId|all]";
        }
    }
}
