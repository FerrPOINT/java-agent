package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.persistence.entity.TodoEntity;
import com.azhukov.agent.persistence.repository.TodoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TodoToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER_ID = "user-42";
    private static final Session SESSION = Session.create(USER_ID, "noop", "default");
    private static final Message LAST_MESSAGE = Message.user("test prompt");

    @Mock
    private TodoRepository todoRepository;

    @Test
    @DisplayName("No todos arg reads current session list as Hermes-shaped JSON")
    void noArgs_readsList() throws Exception {
        stubSessionStore(SESSION.id());
        TodoTool tool = new TodoTool(todoRepository);

        ToolResult result = tool.execute("{}", LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        JsonNode json = json(result);
        assertThat(json.path("todos").size()).isZero();
        assertThat(json.path("summary").path("total").asInt()).isZero();
    }

    @Test
    @DisplayName("todos=null stays in read mode")
    void nullTodos_readsList() throws Exception {
        stubSessionStore(SESSION.id());
        TodoTool tool = new TodoTool(todoRepository);

        ToolResult result = tool.execute("{\"todos\":null}", LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(json(result).path("summary").path("total").asInt()).isZero();
    }

    @Test
    @DisplayName("merge=false replaces only the current session list")
    void write_replaceCurrentSessionOnly() throws Exception {
        List<TodoEntity> store = stubSessionStore(SESSION.id());
        UUID otherSessionId = UUID.randomUUID();
        store.add(todo(UUID.randomUUID(), otherSessionId, USER_ID, "Other session", "pending", 0));
        store.add(todo(UUID.randomUUID(), SESSION.id(), "other-user", "Other user", "pending", 1));

        TodoTool tool = new TodoTool(todoRepository);
        String args = "{\"todos\":[{\"id\":\"a\",\"content\":\"Task A\",\"status\":\"pending\"},"
            + "{\"id\":\"b\",\"content\":\"Task B\",\"status\":\"in_progress\"}]}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        verify(todoRepository).deleteBySessionIdAndUserId(SESSION.id(), USER_ID);
        verify(todoRepository, never()).deleteByUserId(any());
        verify(todoRepository, times(2)).save(any(TodoEntity.class));

        JsonNode todos = json(result).path("todos");
        assertThat(todos.size()).isEqualTo(2);
        assertThat(todos.get(0).path("content").asText()).isEqualTo("Task B");
        assertThat(todos.get(0).path("status").asText()).isEqualTo("in_progress");
        assertThat(store).anySatisfy(todo -> assertThat(todo.getSessionId()).isEqualTo(otherSessionId));
        assertThat(store).anySatisfy(todo -> assertThat(todo.getUserId()).isEqualTo("other-user"));
    }

    @Test
    @DisplayName("merge=true updates existing item by numeric display id")
    void merge_updateByNumericId() throws Exception {
        List<TodoEntity> store = stubSessionStore(SESSION.id());
        TodoEntity existing = todo(UUID.randomUUID(), SESSION.id(), USER_ID, "Old title", "pending", 0);
        store.add(existing);
        TodoTool tool = new TodoTool(todoRepository);

        ToolResult result = tool.execute(
            "{\"todos\":[{\"id\":\"1\",\"content\":\"Updated title\",\"status\":\"completed\"}],\"merge\":true}",
            LAST_MESSAGE,
            SESSION
        );

        assertThat(result.success()).isTrue();
        assertThat(existing.getTitle()).isEqualTo("Updated title");
        assertThat(existing.getStatus()).isEqualTo("completed");
        verify(todoRepository, never()).deleteBySessionIdAndUserId(any(), any());
        JsonNode item = json(result).path("todos").get(0);
        assertThat(item.path("id").asText()).isEqualTo("1");
        assertThat(item.path("status").asText()).isEqualTo("completed");
    }

    @Test
    @DisplayName("merge=true does not update a UUID belonging to another session")
    void merge_uuidFromDifferentSessionCreatesCurrentSessionItem() {
        List<TodoEntity> store = stubSessionStore(SESSION.id());
        UUID foreignId = UUID.randomUUID();
        store.add(todo(foreignId, UUID.randomUUID(), USER_ID, "Foreign", "pending", 0));
        TodoTool tool = new TodoTool(todoRepository);

        ToolResult result = tool.execute(
            "{\"todos\":[{\"id\":\"" + foreignId + "\",\"content\":\"Current\",\"status\":\"pending\"}],\"merge\":true}",
            LAST_MESSAGE,
            SESSION
        );

        assertThat(result.success()).isTrue();
        assertThat(store)
            .filteredOn(todo -> SESSION.id().equals(todo.getSessionId()))
            .singleElement()
            .satisfies(todo -> assertThat(todo.getTitle()).isEqualTo("Current"));
        assertThat(store)
            .filteredOn(todo -> foreignId.equals(todo.getId()))
            .singleElement()
            .satisfies(todo -> assertThat(todo.getTitle()).isEqualTo("Foreign"));
    }

    @Test
    @DisplayName("merge=true updates only fields provided by the model")
    void merge_updatesOnlyProvidedFields() {
        List<TodoEntity> store = stubSessionStore(SESSION.id());
        TodoEntity existing = todo(UUID.randomUUID(), SESSION.id(), USER_ID, "Keep title", "pending", 0);
        store.add(existing);
        TodoTool tool = new TodoTool(todoRepository);

        ToolResult result = tool.execute(
            "{\"todos\":[{\"id\":\"1\",\"status\":\"completed\"}],\"merge\":true}",
            LAST_MESSAGE,
            SESSION
        );

        assertThat(result.success()).isTrue();
        assertThat(existing.getTitle()).isEqualTo("Keep title");
        assertThat(existing.getStatus()).isEqualTo("completed");
    }

    @Test
    @DisplayName("Invalid and missing fields are normalized like Hermes")
    void invalidFieldsAreNormalized() throws Exception {
        stubSessionStore(SESSION.id());
        TodoTool tool = new TodoTool(todoRepository);

        ToolResult result = tool.execute(
            "{\"todos\":[{\"id\":\"1\",\"content\":\"Bad\",\"status\":\"invalid\"},{\"id\":\"2\"},42]}",
            LAST_MESSAGE,
            SESSION
        );

        assertThat(result.success()).isTrue();
        JsonNode todos = json(result).path("todos");
        assertThat(todos.size()).isEqualTo(3);
        assertThat(todos.get(0).path("status").asText()).isEqualTo("pending");
        assertThat(todos.get(1).path("content").asText()).isEqualTo("(no description)");
        assertThat(todos.get(2).path("content").asText()).isEqualTo("(invalid item)");
    }

    @Test
    @DisplayName("Oversized item content is capped instead of failing")
    void longContentIsCapped() throws Exception {
        stubSessionStore(SESSION.id());
        TodoTool tool = new TodoTool(todoRepository);
        String longContent = "x".repeat(TodoTool.MAX_CONTENT_CHARS + 1);

        ToolResult result = tool.execute(
            "{\"todos\":[{\"id\":\"1\",\"content\":\"" + longContent + "\",\"status\":\"pending\"}]}",
            LAST_MESSAGE,
            SESSION
        );

        assertThat(result.success()).isTrue();
        String content = json(result).path("todos").get(0).path("content").asText();
        assertThat(content).hasSize(TodoTool.MAX_CONTENT_CHARS);
        assertThat(content).endsWith(TodoTool.TRUNCATION_MARKER);
    }

    @Test
    @DisplayName("Oversized todo lists are capped to the Hermes maximum")
    void tooManyItemsAreCapped() throws Exception {
        stubSessionStore(SESSION.id());
        TodoTool tool = new TodoTool(todoRepository);

        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < TodoTool.MAX_ITEMS + 1; i++) {
            if (i > 0) {
                items.append(",");
            }
            items.append("{\"id\":\"").append(i).append("\",\"content\":\"T").append(i).append("\",\"status\":\"pending\"}");
        }
        items.append("]");

        ToolResult result = tool.execute("{\"todos\":" + items + "}", LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(json(result).path("todos").size()).isEqualTo(TodoTool.MAX_ITEMS);
        verify(todoRepository, times(TodoTool.MAX_ITEMS)).save(any(TodoEntity.class));
    }

    @Test
    @DisplayName("String-encoded todos are accepted")
    void stringEncodedTodosAreAccepted() throws Exception {
        stubSessionStore(SESSION.id());
        TodoTool tool = new TodoTool(todoRepository);

        ToolResult result = tool.execute(
            "{\"todos\":\"[{\\\"id\\\":\\\"1\\\",\\\"content\\\":\\\"From string\\\",\\\"status\\\":\\\"pending\\\"}]\"}",
            LAST_MESSAGE,
            SESSION
        );

        assertThat(result.success()).isTrue();
        assertThat(json(result).path("todos").get(0).path("content").asText()).isEqualTo("From string");
    }

    @Test
    @DisplayName("Unparseable string-encoded todos returns an actionable error")
    void unparseableStringTodosFails() throws Exception {
        TodoTool tool = new TodoTool(todoRepository);

        ToolResult result = tool.execute("{\"todos\":\"not json\"}", LAST_MESSAGE, SESSION);

        assertThat(errorJson(result).path("error").asText()).isEqualTo("todos must be a list of objects, got unparseable string");
    }

    @Test
    @DisplayName("Non-list todos returns an actionable error")
    void nonListTodosFails() throws Exception {
        TodoTool tool = new TodoTool(todoRepository);

        ToolResult result = tool.execute("{\"todos\":{\"id\":\"1\"}}", LAST_MESSAGE, SESSION);

        assertThat(errorJson(result).path("error").asText()).isEqualTo("todos must be a list, got dict");
    }

    @Test
    @DisplayName("Invalid JSON arguments return the same structured error in content and metadata")
    void invalidJsonFailsWithStructuredError() throws Exception {
        TodoTool tool = new TodoTool(todoRepository);

        ToolResult result = tool.execute("{not-json", LAST_MESSAGE, SESSION);

        assertThat(errorJson(result).path("error").asText()).contains("Invalid tool arguments");
    }

    @Test
    @DisplayName("Duplicate ids keep the last occurrence in its position")
    void duplicateIdsKeepLastOccurrence() throws Exception {
        stubSessionStore(SESSION.id());
        TodoTool tool = new TodoTool(todoRepository);

        ToolResult result = tool.execute(
            "{\"todos\":[{\"id\":\"a\",\"content\":\"First\",\"status\":\"pending\"},"
                + "{\"id\":\"b\",\"content\":\"Second\",\"status\":\"pending\"},"
                + "{\"id\":\"a\",\"content\":\"Last\",\"status\":\"completed\"}]}",
            LAST_MESSAGE,
            SESSION
        );

        JsonNode todos = json(result).path("todos");
        assertThat(todos.size()).isEqualTo(2);
        assertThat(todos.get(0).path("content").asText()).isEqualTo("Second");
        assertThat(todos.get(1).path("content").asText()).isEqualTo("Last");
    }

    @Test
    @DisplayName("validateStatus keeps exact allowed set")
    void validateStatus() {
        assertThat(TodoTool.validateStatus(null, "pending")).isEqualTo("pending");
        assertThat(TodoTool.validateStatus("", "pending")).isEqualTo("pending");
        assertThat(TodoTool.validateStatus("  ", "pending")).isEqualTo("pending");
        assertThat(TodoTool.validateStatus("PENDING", null)).isEqualTo("pending");
        assertThat(TodoTool.validateStatus("In_Progress", null)).isEqualTo("in_progress");
        assertThat(TodoTool.validateStatus("COMPLETED", null)).isEqualTo("completed");
        assertThat(TodoTool.validateStatus("cancelled", null)).isEqualTo("cancelled");
        assertThat(TodoTool.validateStatus("done", null)).isNull();
    }

    private List<TodoEntity> stubSessionStore(UUID sessionId) {
        List<TodoEntity> store = new ArrayList<>();
        when(todoRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenAnswer(inv -> store.stream()
            .filter(todo -> sessionId.equals(todo.getSessionId()))
            .sorted(Comparator.comparing(TodoEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList());
        when(todoRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return store.stream()
                .filter(todo -> id.equals(todo.getId()))
                .findFirst();
        });
        when(todoRepository.save(any(TodoEntity.class))).thenAnswer(inv -> {
            TodoEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            store.removeIf(existing -> entity.getId().equals(existing.getId()));
            store.add(entity);
            return entity;
        });
        doAnswer(inv -> {
            UUID id = inv.getArgument(0);
            String userId = inv.getArgument(1, String.class);
            store.removeIf(todo -> id.equals(todo.getSessionId()) && userId.equals(todo.getUserId()));
            return null;
        }).when(todoRepository).deleteBySessionIdAndUserId(any(UUID.class), any(String.class));
        doAnswer(inv -> {
            TodoEntity entity = inv.getArgument(0);
            store.removeIf(todo -> entity.getId().equals(todo.getId()));
            return null;
        }).when(todoRepository).delete(any(TodoEntity.class));
        return store;
    }

    private static TodoEntity todo(UUID id, UUID sessionId, String userId, String title, String status, int secondsOffset) {
        TodoEntity todo = new TodoEntity();
        todo.setId(id);
        todo.setSessionId(sessionId);
        todo.setUserId(userId);
        todo.setTitle(title);
        todo.setStatus(status);
        todo.setPriority("medium");
        todo.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(secondsOffset));
        todo.setUpdatedAt(todo.getCreatedAt());
        return todo;
    }

    private static JsonNode json(ToolResult result) throws Exception {
        return MAPPER.readTree(result.content());
    }

    private static JsonNode errorJson(ToolResult result) throws Exception {
        assertThat(result.success()).isFalse();
        assertThat(result.content()).isNotBlank();
        JsonNode json = json(result);
        assertThat(json.path("error").asText()).isNotBlank();
        assertThat(result.error()).isEqualTo(json.path("error").asText());
        return json;
    }
}
