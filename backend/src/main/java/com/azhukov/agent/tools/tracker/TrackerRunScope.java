package com.azhukov.agent.tools.tracker;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record TrackerRunScope(
    @JsonProperty("task_id") String taskId,
    @JsonProperty("project_id") String projectId,
    @JsonProperty("tracker_instance_id") String trackerInstanceId,
    @JsonProperty("agent_id") String agentId,
    @JsonProperty("assignment_id") String assignmentId,
    @JsonProperty("execution_id") String executionId,
    @JsonProperty("machine_subject") String machineSubject,
    @JsonProperty("assignment_version") long assignmentVersion,
    @JsonProperty("forge_session_id") @JsonInclude(JsonInclude.Include.NON_NULL) String forgeSessionId
) {
    public static final String METADATA = "fleet_tracker_scope";

    public TrackerRunScope {
        if (!canonicalId(taskId) || !canonicalId(projectId) || !canonicalId(agentId)
            || !canonicalId(assignmentId) || !canonicalId(executionId) || !canonicalId(machineSubject)
            || trackerInstanceId == null || trackerInstanceId.isBlank() || trackerInstanceId.length() > 128
            || !trackerInstanceId.equals(trackerInstanceId.strip())
            || trackerInstanceId.chars().anyMatch(Character::isISOControl)
            || (forgeSessionId != null && !canonicalId(forgeSessionId))
            || assignmentVersion < 1 || assignmentVersion > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("Invalid Fleet Tracker assignment");
        }
    }

    public static boolean canonicalId(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            UUID id = UUID.fromString(value);
            return !id.equals(new UUID(0, 0)) && id.toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    boolean matchesContext(JsonNode context, String workflowTask) {
        var assignment = context.path("assignment");
        return taskId.equals(context.path("task_id").asText())
            && projectId.equals(context.path("project_id").asText())
            && trackerInstanceId.equals(context.path("tracker_instance_id").asText())
            && workflowTask.equals(context.path("workflow_task_key").asText())
            && "configured-cli-v1".equals(context.path("interaction_protocol").asText())
            && matchesAssignment(assignment)
            && assignment.path("version").isIntegralNumber()
            && assignment.path("version").longValue() == assignmentVersion;
    }

    boolean matchesAssignment(JsonNode assignment) {
        return assignmentId.equals(assignment.path("assignment_id").asText())
            && executionId.equals(assignment.path("execution_id").asText())
            && agentId.equals(assignment.path("agent_id").asText())
            && machineSubject.equals(assignment.path("machine_subject").asText());
    }
}
