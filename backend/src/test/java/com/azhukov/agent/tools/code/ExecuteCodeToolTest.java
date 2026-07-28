package com.azhukov.agent.tools.code;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
class ExecuteCodeToolTest {

    @Test
    void runsPythonAndReturnsOutput() {
        ExecuteCodeTool t = new ExecuteCodeTool();
        ToolResult r = t.execute("{\"code\":\"print(1+1)\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("2");
    }

    @Test
    void failsWhenCodeMissing() {
        ExecuteCodeTool t = new ExecuteCodeTool();
        ToolResult r = t.execute("{}", null, Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void respectsTimeout() {
        ExecuteCodeTool t = new ExecuteCodeTool();
        ToolResult r = t.execute("{\"code\":\"import time; time.sleep(5)\",\"timeout\":\"1\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("timed out");
    }
}
