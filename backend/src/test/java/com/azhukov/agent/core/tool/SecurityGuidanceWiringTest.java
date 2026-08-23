package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.GuardrailDecision;
import com.azhukov.agent.core.security.SecretRedactor;
import com.azhukov.agent.core.security.SecurityGuidanceScanner;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SecurityGuidanceScanner wiring (Hermes plugins/security-guidance
 * transform_tool_result hook): write_file/patch results carry a ⚠️ warning
 * block when the written content matches a dangerous pattern; the write
 * itself is NOT blocked; other tools are never scanned.
 */
@ExtendWith(MockitoExtension.class)
class SecurityGuidanceWiringTest {

    private static final Message LAST_MSG = Message.user("q");
    private static final Session SESSION = Session.create("u", "p", "m");

    @Mock private ToolRegistry toolRegistry;
    @Mock private ToolCallGuardrail guardrail;
    @Mock private SecretRedactor redactor;

    private ToolExecutionService service;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getToolOutput().setMaxChars(100_000);
        properties.getToolOutput().setTimeoutSeconds(5);
        service = new ToolExecutionService(toolRegistry, properties, guardrail, redactor, null, null, null);
        service.setSecurityGuidanceScanner(new SecurityGuidanceScanner());
    }

    private void allow(String tool, String args) {
        when(guardrail.beforeCall(tool, args)).thenReturn(GuardrailDecision.allow(tool));
        when(guardrail.afterCall(eq(tool), eq(args), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(tool));
    }

    @Test
    void writeWithEvalGetsWarningBlock() {
        String args = "{\"path\":\"/tmp/x.py\",\"content\":\"result = eval(user_input)\"}";
        allow("write_file", args);
        when(toolRegistry.execute(eq("write_file"), eq("call-1"), eq(args), eq(LAST_MSG), eq(SESSION)))
            .thenReturn(ToolResult.ok("Wrote 27 characters to /tmp/x.py"));
        when(redactor.redact("Wrote 27 characters to /tmp/x.py")).thenReturn("Wrote 27 characters to /tmp/x.py");

        ToolResult result = service.execute("write_file", "call-1", args, LAST_MSG, SESSION);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Wrote 27 characters");
        assertThat(result.content()).contains("⚠️ Security guidance");
        assertThat(result.content()).contains("eval_injection");
        assertThat(result.content()).contains("false positives");
    }

    @Test
    void safeWriteGetsNoWarning() {
        String args = "{\"path\":\"/tmp/ok.py\",\"content\":\"print('hello')\"}";
        allow("write_file", args);
        when(toolRegistry.execute(eq("write_file"), eq("call-1"), eq(args), eq(LAST_MSG), eq(SESSION)))
            .thenReturn(ToolResult.ok("Wrote 15 characters to /tmp/ok.py"));
        when(redactor.redact("Wrote 15 characters to /tmp/ok.py")).thenReturn("Wrote 15 characters to /tmp/ok.py");

        ToolResult result = service.execute("write_file", "call-1", args, LAST_MSG, SESSION);
        assertThat(result.content()).doesNotContain("Security guidance");
    }

    @Test
    void nonWriteToolsNeverScanned() {
        // terminal output containing 'eval(' must NOT trigger the warning
        String args = "{\"command\":\"echo eval(\"}";
        allow("terminal", args);
        when(toolRegistry.execute(eq("terminal"), eq("call-1"), eq(args), eq(LAST_MSG), eq(SESSION)))
            .thenReturn(ToolResult.ok("eval(\n"));
        when(redactor.redact("eval(\n")).thenReturn("eval(\n");

        ToolResult result = service.execute("terminal", "call-1", args, LAST_MSG, SESSION);
        assertThat(result.content()).doesNotContain("Security guidance");
        assertThat(result.content()).contains("eval(");
    }

    @Test
    void failedWriteGetsNoWarning() {
        String args = "{\"path\":\"/denied.py\",\"content\":\"eval(x)\"}";
        allow("write_file", args);
        when(toolRegistry.execute(eq("write_file"), eq("call-1"), eq(args), eq(LAST_MSG), eq(SESSION)))
            .thenReturn(ToolResult.fail("Access denied"));
        when(redactor.redact("Access denied")).thenReturn("Access denied");

        ToolResult result = service.execute("write_file", "call-1", args, LAST_MSG, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).doesNotContain("Security guidance");
    }
}
