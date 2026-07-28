package com.azhukov.agent.core.tool;

import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

/**
 * Classifies a {@link ToolResult} into a broad category for logging and
 * future decision-making.
 */
@Component
public class ToolResultClassifier {

    public enum ResultType {
        SUCCESS,
        FAILURE,
        PARTIAL,
        SILENT
    }

    public ResultType classify(ToolResult result) {
        if (!result.success()) {
            return ResultType.FAILURE;
        }
        String content = result.content();
        if (content == null || content.isEmpty() || "***".equals(content)) {
            return ResultType.SILENT;
        }
        String lower = content.toLowerCase();
        if (lower.contains("error") || lower.contains("failed")) {
            return ResultType.PARTIAL;
        }
        return ResultType.SUCCESS;
    }
}