package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.SharedObjectMapper;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Truncates tool output to a configurable maximum character count, appending
 * a warning when truncation occurs.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ToolOutputLimiter {

    private static final ObjectMapper JSON = SharedObjectMapper.get();

    private final AgentProperties properties;

    /**
     * Truncates {@code content} to at most {@code maxChars} characters,
     * Hermes-style (tools/terminal_tool.py:3451-3462): keep a 40% HEAD and a
     * 60% TAIL with an omission notice between them. Error messages tend to
     * appear early, while the most recent/relevant output (exit codes, test
     * summaries) rides the tail — a head-only cut hides both.
     */
    public String truncate(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) {
            return content;
        }
        int headChars = (int) (maxChars * 0.4);
        int tailChars = maxChars - headChars;
        int omitted = content.length() - headChars - tailChars;
        String notice = "\n\n... [OUTPUT TRUNCATED - " + omitted
            + " chars omitted out of " + content.length() + " total] ...\n\n";
        log.warn("Tool output truncated from {} to {} chars (head+tail)", content.length(), maxChars);
        return content.substring(0, headChars) + notice + content.substring(content.length() - tailChars);
    }

    /**
     * Convenience overload that applies {@link #truncate(String, int)} to a
     * {@link ToolResult} using the configured {@code maxChars} from
     * {@link AgentProperties}.
     */
    public ToolResult truncate(ToolResult result) {
        return truncate(result, null);
    }

    /**
     * Per-tool truncation (Feature 7): terminal and web_extract have dedicated
     * max-chars overrides; everything else uses the generic max-chars. A value
     * of 0 means "not configured" and falls back to the generic limit.
     */
    public ToolResult truncate(ToolResult result, String toolName) {
        var out = properties.getToolOutput();
        int maxChars = out.getMaxChars();
        if (toolName != null) {
            if ("terminal".equals(toolName) && out.getTerminalMaxChars() > 0) {
                maxChars = out.getTerminalMaxChars();
            } else if ("web_extract".equals(toolName) && out.getWebExtractMaxChars() > 0) {
                maxChars = out.getWebExtractMaxChars();
            }
        }
        if (result.success()) {
            String truncated = truncate(result.content(), maxChars);
            if (truncated == result.content()) {
                return result;
            }
            return ToolResult.ok(truncated);
        }
        if (result.content() != null && !result.content().isEmpty()) {
            String truncatedContent = truncate(result.content(), maxChars);
            String truncatedError = truncate(result.error(), maxChars);
            if (truncatedContent == result.content() && truncatedError == result.error()) {
                return result;
            }
            return new ToolResult(false, truncatedContent, truncatedError);
        }
        String truncated = truncate(result.error(), maxChars);
        return failureResult(truncated);
    }

    private static ToolResult failureResult(String error) {
        String message = error == null || error.isBlank() ? "Tool failed" : error;
        ObjectNode payload = JSON.createObjectNode();
        payload.put("success", false);
        payload.put("error", message);
        return new ToolResult(false, payload.toString(), message);
    }
}
