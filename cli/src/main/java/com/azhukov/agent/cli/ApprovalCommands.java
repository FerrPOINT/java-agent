package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * c8: Approval, steer, and stop slash commands.
 * <p>
 * Includes: approve, deny, approvals, approve-tool, deny-tool, stop, steer.
 */
@Component
public class ApprovalCommands implements CommandGroup {

    @Override
    public void registerAll(SlashCommandRegistry registry) {
        registry.register("approve", "Approve pending action (use 'all' to approve all)", (args, client, sessionId) -> {
            boolean all = "all".equalsIgnoreCase(args.strip());
            String requestId = all ? null : (args.isBlank() ? null : args.strip());
            return client.approve(all, requestId);
        });

        registry.register("deny", "Deny pending action (use 'all' to deny all)", (args, client, sessionId) ->
            client.deny("all".equalsIgnoreCase(args.strip())));

        registry.register("approvals", "List pending tool approvals", (args, client, sessionId) -> {
            JsonNode approvals = client.listPendingApprovals();
            return client.prettyPrint(approvals);
        });

        registry.register("approve-tool", "Approve an exact pending tool request", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /approve-tool <requestId>";
            return client.approveTool(args.strip());
        });

        registry.register("deny-tool", "Deny an exact pending tool request", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /deny-tool <requestId>";
            return client.denyTool(args.strip());
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
}
