package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
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

    private final AgentProperties properties;

    /**
     * Truncates {@code content} to at most {@code maxChars} characters.
     * If the content fits, it is returned unchanged.
     * If truncation occurs, a warning suffix is appended.
     */
    public String truncate(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) {
            return content;
        }
        String warning = "\n[output truncated at " + maxChars + " chars]";
        log.warn("Tool output truncated from {} to {} chars", content.length(), maxChars);
        return content.substring(0, maxChars) + warning;
    }

    /**
     * Convenience overload that applies {@link #truncate(String, int)} to a
     * {@link ToolResult} using the configured {@code maxChars} from
     * {@link AgentProperties}.
     */
    public ToolResult truncate(ToolResult result) {
        int maxChars = properties.getToolOutput().getMaxChars();
        if (result.success()) {
            String truncated = truncate(result.content(), maxChars);
            if (truncated == result.content()) {
                return result;
            }
            return ToolResult.ok(truncated);
        } else {
            String truncated = truncate(result.error(), maxChars);
            if (truncated == result.error()) {
                return result;
            }
            return ToolResult.fail(truncated);
        }
    }
}