package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessToolExtraTest {

    ProcessTool tool = new ProcessTool();

    @Test
    void unknownActionReturnsFailure() {
        ToolResult r = tool.execute("{\"action\":\"dance\"}", Message.assistant("",0), Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void listEmptyProcesses() {
        ToolResult r = tool.execute("{\"action\":\"list\"}", Message.assistant("",0), Session.create("u","p","m"));
        assertThat(r.success()).isTrue();
    }

    @Test
    void pollUnknownProcess() {
        ToolResult r = tool.execute("{\"action\":\"poll\",\"session_id\":\"bad\"}", Message.assistant("",0), Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void logUnknownProcess() {
        ToolResult r = tool.execute("{\"action\":\"log\",\"session_id\":\"bad\"}", Message.assistant("",0), Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void waitUnknownProcess() {
        ToolResult r = tool.execute("{\"action\":\"wait\",\"session_id\":\"bad\"}", Message.assistant("",0), Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void killUnknownProcess() {
        ToolResult r = tool.execute("{\"action\":\"kill\",\"session_id\":\"bad\"}", Message.assistant("",0), Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void writeUnknownProcess() {
        ToolResult r = tool.execute("{\"action\":\"write\",\"session_id\":\"bad\",\"data\":\"x\"}", Message.assistant("",0), Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void submitUnknownProcess() {
        ToolResult r = tool.execute("{\"action\":\"submit\",\"session_id\":\"bad\",\"data\":\"x\"}", Message.assistant("",0), Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void closeUnknownProcess() {
        ToolResult r = tool.execute("{\"action\":\"close\",\"session_id\":\"bad\"}", Message.assistant("",0), Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }
}
