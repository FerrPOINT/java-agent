package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KanbanCommandTest {

    private AgentBackendClient backendClient;
    private KanbanCommand cmd;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        backendClient = mock(AgentBackendClient.class);
        cmd = new KanbanCommand(backendClient);
        mapper = new ObjectMapper();
    }

    @Test
    void nameAndDescription() {
        assertThat(cmd.name()).isEqualTo("kanban");
        assertThat(cmd.description()).isEqualTo("Kanban board: list, add, done, clear");
    }

    @Test
    void emptyBoardShowsMessage() {
        when(backendClient.getKanban()).thenReturn(mapper.createArrayNode());

        String result = cmd.handle(textEvent("/kanban", null), null);

        assertThat(result).contains("empty");
    }

    @Test
    void showsPendingAndDoneTasks() {
        ArrayNode board = mapper.createArrayNode();

        ObjectNode pending = mapper.createObjectNode();
        pending.put("id", "abc-123-def-456");
        pending.put("title", "Fix tests and run them");
        pending.put("status", "pending");
        board.add(pending);

        ObjectNode done = mapper.createObjectNode();
        done.put("id", "xyz-789-aaa-000");
        done.put("title", "Write docs");
        done.put("status", "done");
        board.add(done);

        when(backendClient.getKanban()).thenReturn(board);

        String result = cmd.handle(textEvent("/kanban", null), null);

        assertThat(result).contains("Fix tests");
        assertThat(result).contains("Write docs");
        assertThat(result).contains("Pending");
        assertThat(result).contains("Done");
    }

    @Test
    void addTaskCallsBackend() {
        ObjectNode created = mapper.createObjectNode();
        created.put("id", "new-task-id");
        created.put("title", "My new task");
        created.put("status", "pending");
        when(backendClient.addKanbanTask("My new task")).thenReturn(created);

        String result = cmd.handle(textEvent("/kanban", "add My new task"), null);

        assertThat(result).contains("Added task");
        assertThat(result).contains("My new task");
    }

    @Test
    void doneTaskCallsBackend() {
        when(backendClient.doneKanbanTask("some-id")).thenReturn(true);

        String result = cmd.handle(textEvent("/kanban", "done some-id"), null);

        assertThat(result).contains("marked as done");
    }

    @Test
    void clearBoardCallsBackend() {
        when(backendClient.clearKanban()).thenReturn(true);

        String result = cmd.handle(textEvent("/kanban", "clear"), null);

        assertThat(result).contains("cleared");
    }

    private UpdateEvent textEvent(String text, String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", text, null, null, null, null, null, null, true, "kanban", args != null ? args : "");
    }
}