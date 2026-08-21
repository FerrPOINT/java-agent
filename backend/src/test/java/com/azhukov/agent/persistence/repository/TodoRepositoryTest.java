package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.entity.TodoEntity;
import com.azhukov.agent.persistence.PostgresTestContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.flyway.enabled=true",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=none",
    "agent.model.provider=noop",
    "agent.memory.enabled=false",
    "agent.skills.enabled=false",
    "agent.mcp.enabled=false",
    "agent.mcp.servers=",
    "agent.chromium.auto-start=false",
    "agent.chromium.auto-install=false"
})
@Transactional
class TodoRepositoryTest extends PostgresTestContainer {

    private static final String USER_ID_1 = "11111111-1111-1111-1111-111111111111";
    private static final String USER_ID_2 = "22222222-2222-2222-2222-222222222222";
    private static final String UNKNOWN_USER_ID = "99999999-9999-9999-9999-999999999999";

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_COMPLETED = "completed";

    private static final String PRIORITY_HIGH = "high";
    private static final String PRIORITY_LOW = "low";

    private static final String TITLE_BUY_MILK = "Buy milk";
    private static final String TITLE_WALK_DOG = "Walk the dog";
    private static final String TITLE_READ_BOOK = "Read a book";
    private static final String TITLE_PAY_BILLS = "Pay bills";

    private static final Instant T1 = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-01T12:00:01Z");
    private static final Instant T3 = Instant.parse("2026-01-01T12:00:02Z");
    private static final Instant T4 = Instant.parse("2026-01-01T12:00:03Z");

    private static final String MODEL_PROVIDER = "openai-compatible";
    private static final String MODEL_NAME = "kimi-k2.6";

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Test
    void saveTodoForUser() {
        SessionEntity session = sessionRepository.save(newSession(USER_ID_1, "Todo Session"));
        TodoEntity todo = newTodo(session.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1);

        TodoEntity saved = todoRepository.save(todo);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getSessionId()).isEqualTo(session.getId());
        assertThat(saved.getUserId()).isEqualTo(USER_ID_1);
        assertThat(saved.getTitle()).isEqualTo(TITLE_BUY_MILK);
        assertThat(saved.getStatus()).isEqualTo(STATUS_PENDING);
        assertThat(saved.getPriority()).isEqualTo(PRIORITY_HIGH);
        assertThat(saved.getCreatedAt()).isEqualTo(T1);
    }

    @Test
    void findByUserIdReturnsOnlyUsersTodos() {
        SessionEntity session1 = sessionRepository.save(newSession(USER_ID_1, "User 1 Session"));
        SessionEntity session2 = sessionRepository.save(newSession(USER_ID_2, "User 2 Session"));

        TodoEntity todo1 = todoRepository.save(newTodo(session1.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1));
        TodoEntity todo2 = todoRepository.save(newTodo(session1.getId(), USER_ID_1, TITLE_WALK_DOG, STATUS_COMPLETED, PRIORITY_LOW, T2));
        todoRepository.save(newTodo(session2.getId(), USER_ID_2, TITLE_READ_BOOK, STATUS_PENDING, PRIORITY_HIGH, T3));

        List<TodoEntity> user1Todos = todoRepository.findByUserId(USER_ID_1);

        assertThat(user1Todos).hasSize(2);
        assertThat(user1Todos)
            .extracting(TodoEntity::getId)
            .containsExactlyInAnyOrder(todo1.getId(), todo2.getId());
        assertThat(user1Todos)
            .extracting(TodoEntity::getUserId)
            .containsOnly(USER_ID_1);
    }

    @Test
    void findByUserIdReturnsEmptyForUnknownUser() {
        SessionEntity session = sessionRepository.save(newSession(USER_ID_1, "Known User Session"));
        todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1));

        List<TodoEntity> found = todoRepository.findByUserId(UNKNOWN_USER_ID);

        assertThat(found).isEmpty();
    }

    @Test
    void findByUserIdAndStatusFiltersByStatus() {
        SessionEntity session = sessionRepository.save(newSession(USER_ID_1, "Status Filter Session"));

        TodoEntity pendingTodo = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1));
        todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_WALK_DOG, STATUS_COMPLETED, PRIORITY_LOW, T2));
        TodoEntity inProgressTodo = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_READ_BOOK, STATUS_IN_PROGRESS, PRIORITY_HIGH, T3));
        todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_PAY_BILLS, STATUS_COMPLETED, PRIORITY_LOW, T4));

        List<TodoEntity> pending = todoRepository.findByUserIdAndStatus(USER_ID_1, STATUS_PENDING);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getId()).isEqualTo(pendingTodo.getId());
        assertThat(pending.get(0).getStatus()).isEqualTo(STATUS_PENDING);

        List<TodoEntity> inProgress = todoRepository.findByUserIdAndStatus(USER_ID_1, STATUS_IN_PROGRESS);
        assertThat(inProgress).hasSize(1);
        assertThat(inProgress.get(0).getId()).isEqualTo(inProgressTodo.getId());
        assertThat(inProgress.get(0).getStatus()).isEqualTo(STATUS_IN_PROGRESS);

        List<TodoEntity> completed = todoRepository.findByUserIdAndStatus(USER_ID_1, STATUS_COMPLETED);
        assertThat(completed).hasSize(2);
        assertThat(completed)
            .extracting(TodoEntity::getTitle)
            .containsExactlyInAnyOrder(TITLE_WALK_DOG, TITLE_PAY_BILLS);
    }

    @Test
    void updateStatus() {
        SessionEntity session = sessionRepository.save(newSession(USER_ID_1, "Update Status Session"));
        TodoEntity todo = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1));
        UUID todoId = todo.getId();

        todo.setStatus(STATUS_COMPLETED);
        todoRepository.save(todo);

        TodoEntity found = todoRepository.findById(todoId).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(STATUS_COMPLETED);
        assertThat(found.getTitle()).isEqualTo(TITLE_BUY_MILK);
    }

    @Test
    void deleteCompletedTodos() {
        SessionEntity session = sessionRepository.save(newSession(USER_ID_1, "Delete Completed Session"));
        SessionEntity session2 = sessionRepository.save(newSession(USER_ID_2, "User 2 Session"));

        TodoEntity pendingTodo = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1));
        TodoEntity completedTodo1 = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_WALK_DOG, STATUS_COMPLETED, PRIORITY_LOW, T2));
        TodoEntity inProgressTodo = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_READ_BOOK, STATUS_IN_PROGRESS, PRIORITY_HIGH, T3));
        TodoEntity completedTodo2 = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_PAY_BILLS, STATUS_COMPLETED, PRIORITY_LOW, T4));
        todoRepository.save(newTodo(session2.getId(), USER_ID_2, TITLE_READ_BOOK, STATUS_COMPLETED, PRIORITY_HIGH, T1));

        todoRepository.deleteByUserIdAndStatus(USER_ID_1, STATUS_COMPLETED);

        List<TodoEntity> remaining = todoRepository.findByUserId(USER_ID_1);
        assertThat(remaining).hasSize(2);
        assertThat(remaining)
            .extracting(TodoEntity::getId)
            .containsExactlyInAnyOrder(pendingTodo.getId(), inProgressTodo.getId());
        assertThat(todoRepository.findById(completedTodo1.getId())).isEmpty();
        assertThat(todoRepository.findById(completedTodo2.getId())).isEmpty();

        List<TodoEntity> user2Todos = todoRepository.findByUserId(USER_ID_2);
        assertThat(user2Todos).hasSize(1);
    }

    private SessionEntity newSession(String userId, String title) {
        SessionEntity session = new SessionEntity();
        session.setUserId(userId);
        session.setTitle(title);
        session.setModelProvider(MODEL_PROVIDER);
        session.setModelName(MODEL_NAME);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return session;
    }

    private TodoEntity newTodo(UUID sessionId, String userId, String title, String status, String priority, Instant createdAt) {
        TodoEntity todo = new TodoEntity();
        todo.setSessionId(sessionId);
        todo.setUserId(userId);
        todo.setTitle(title);
        todo.setStatus(status);
        todo.setPriority(priority);
        todo.setCreatedAt(createdAt);
        return todo;
    }
}
