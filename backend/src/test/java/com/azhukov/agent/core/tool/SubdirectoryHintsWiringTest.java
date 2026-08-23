package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.context.SubdirectoryHintsService;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.GuardrailDecision;
import com.azhukov.agent.core.security.SecretRedactor;
import com.azhukov.agent.core.security.ToolCallGuardrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubdirectoryHints wiring (Hermes tool_executor.py:1768): hints append to
 * SUCCESSFUL tool results only; failures and hint-less calls pass through.
 */
@ExtendWith(MockitoExtension.class)
class SubdirectoryHintsWiringTest {

    private static final Message LAST_MSG = Message.user("q");
    private static final Session SESSION = Session.create("u", "p", "m");

    @Mock private ToolRegistry toolRegistry;
    @Mock private ToolCallGuardrail guardrail;
    @Mock private SecretRedactor redactor;

    private ToolExecutionService service;
    private SubdirectoryHintsService hints;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getToolOutput().setMaxChars(100_000);
        properties.getToolOutput().setTimeoutSeconds(5);
        service = new ToolExecutionService(
            toolRegistry, properties, guardrail, redactor,
            null, null, null);
        hints = mock(SubdirectoryHintsService.class);
        service.setSubdirectoryHints(hints);
    }

    @Test
    void hintsAppendToSuccessfulResult() {
        String args = "{\"path\":\"backend/src/Foo.java\"}";
        when(guardrail.beforeCall("read_file", args)).thenReturn(GuardrailDecision.allow("read_file"));
        when(toolRegistry.execute(eq("read_file"), eq("call-1"), eq(args), eq(LAST_MSG), eq(SESSION)))
            .thenReturn(ToolResult.ok("file content"));
        when(redactor.redact("file content")).thenReturn("file content");
        when(guardrail.afterCall(eq("read_file"), eq(args), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow("read_file"));
        when(hints.checkToolCall(eq("read_file"), any())).thenReturn("\n\n[AGENTS.md] lint before commit");

        ToolResult result = service.execute("read_file", "call-1", args, LAST_MSG, SESSION);
        assertThat(result.content()).contains("file content");
        assertThat(result.content()).contains("[AGENTS.md] lint before commit");
    }

    @Test
    void noHintsOnFailedResult() {
        String args = "{\"path\":\"nope\"}";
        when(guardrail.beforeCall("read_file", args)).thenReturn(GuardrailDecision.allow("read_file"));
        when(toolRegistry.execute(eq("read_file"), eq("call-1"), eq(args), eq(LAST_MSG), eq(SESSION)))
            .thenReturn(ToolResult.fail("ENOENT"));
        
        when(guardrail.afterCall(eq("read_file"), eq(args), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow("read_file"));
        // redactor mock (lenient default null) — code path: redact(error())
        org.mockito.Mockito.lenient().when(redactor.redact("ENOENT")).thenReturn("ENOENT");

        // fail() stores the message in error(); content() is empty
        ToolResult result = service.execute("read_file", "call-1", args, LAST_MSG, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("ENOENT");
        verify(hints, never()).checkToolCall(any(), any());
    }
}
