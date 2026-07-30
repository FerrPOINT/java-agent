package com.azhukov.agent.core.model;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comprehensive tests for all core model objects in
 * {@code com.azhukov.agent.core.model}.
 * <p>
 * Covers records, enums, factory methods, compact-constructors invariants,
 * equals/hashCode, and utility methods.
 */
class CoreModelObjectsTest {

    // ======================== Role ========================

    @Nested
    class RoleTest {

        @Test
        void containsAllExpectedValues() {
            assertThat(Role.values()).containsExactly(
                Role.SYSTEM,
                Role.USER,
                Role.ASSISTANT,
                Role.TOOL
            );
        }

        @Test
        void valueOfRoundTrip() {
            for (Role role : Role.values()) {
                assertThat(Role.valueOf(role.name())).isSameAs(role);
            }
        }
    }

    // ======================== ReferenceType ========================

    @Nested
    class ReferenceTypeTest {

        @Test
        void containsAllExpectedValues() {
            assertThat(ReferenceType.values()).contains(
                ReferenceType.FILE,
                ReferenceType.URL,
                ReferenceType.SKILL,
                ReferenceType.DIFF,
                ReferenceType.STAGED,
                ReferenceType.GIT,
                ReferenceType.FOLDER,
                ReferenceType.UNKNOWN
            );
        }

        @Test
        void valueOfRoundTrip() {
            for (ReferenceType type : ReferenceType.values()) {
                assertThat(ReferenceType.valueOf(type.name())).isSameAs(type);
            }
        }
    }

    // ======================== ContextReference ========================

    @Nested
    class ContextReferenceTest {

        @Test
        void componentsMatchConstructorArguments() {
            ContextReference ref = new ContextReference(
                ReferenceType.FILE, "/path/to/file", "file.txt", null);

            assertThat(ref.type()).isEqualTo(ReferenceType.FILE);
            assertThat(ref.source()).isEqualTo("/path/to/file");
            assertThat(ref.displayName()).isEqualTo("file.txt");
            assertThat(ref.error()).isNull();
        }

        @Test
        void successReturnsTrueWhenErrorIsNull() {
            ContextReference ref = new ContextReference(
                ReferenceType.URL, "https://example.com", "Example", null);
            assertThat(ref.success()).isTrue();
        }

        @Test
        void successReturnsTrueWhenErrorIsEmpty() {
            ContextReference ref = new ContextReference(
                ReferenceType.URL, "https://example.com", "Example", "");
            assertThat(ref.success()).isTrue();
        }

        @Test
        void successReturnsFalseWhenErrorIsNonEmpty() {
            ContextReference ref = new ContextReference(
                ReferenceType.UNKNOWN, "", "", "Something went wrong");
            assertThat(ref.success()).isFalse();
        }

        @Test
        void equalsAndHashCodeFollowRecordContract() {
            ContextReference a = new ContextReference(ReferenceType.FILE, "src", "name", null);
            ContextReference b = new ContextReference(ReferenceType.FILE, "src", "name", null);
            ContextReference c = new ContextReference(ReferenceType.FILE, "src", "name", "err");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
        }

        @Test
        void toStringContainsTypeAndSource() {
            ContextReference ref = new ContextReference(ReferenceType.SKILL, "skill-1", "My Skill", null);
            String s = ref.toString();
            assertThat(s).contains("SKILL").contains("skill-1");
        }
    }

    // ======================== ToolCall ========================

    @Nested
    class ToolCallTest {

        @Test
        void componentsMatchConstructorArguments() {
            ToolCall call = new ToolCall("call-1", "search", "{\"q\":\"hello\"}");
            assertThat(call.id()).isEqualTo("call-1");
            assertThat(call.name()).isEqualTo("search");
            assertThat(call.arguments()).isEqualTo("{\"q\":\"hello\"}");
        }

        @Test
        void rejectsNullId() {
            assertThatThrownBy(() -> new ToolCall(null, "name", "args"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id must not be null");
        }

        @Test
        void rejectsNullName() {
            assertThatThrownBy(() -> new ToolCall("id", null, "args"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name must not be null");
        }

        @Test
        void rejectsNullArguments() {
            assertThatThrownBy(() -> new ToolCall("id", "name", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("arguments must not be null");
        }

        @Test
        void equalsAndHashCodeFollowRecordContract() {
            ToolCall a = new ToolCall("id1", "name1", "{}");
            ToolCall b = new ToolCall("id1", "name1", "{}");
            ToolCall c = new ToolCall("id2", "name1", "{}");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
        }
    }

    // ======================== ToolResult ========================

    @Nested
    class ToolResultTest {

        @Test
        void okCreatesSuccessfulResult() {
            ToolResult result = ToolResult.ok("Done");
            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("Done");
            assertThat(result.error()).isNull();
        }

        @Test
        void failCreatesFailedResult() {
            ToolResult result = ToolResult.fail("Broken");
            assertThat(result.success()).isFalse();
            assertThat(result.content()).isEmpty();
            assertThat(result.error()).isEqualTo("Broken");
        }

        @Test
        void rejectsNullContent() {
            assertThatThrownBy(() -> new ToolResult(true, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("content must not be null");
        }

        @Test
        void equalsAndHashCodeFollowRecordContract() {
            ToolResult a = ToolResult.ok("result");
            ToolResult b = ToolResult.ok("result");
            ToolResult c = ToolResult.fail("err");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
        }
    }

    // ======================== ToolDefinition ========================

    @Nested
    class ToolDefinitionTest {

        @Test
        void componentsMatchConstructorArguments() {
            Map<String, Object> params = Map.of("type", "object");
            ToolDefinition def = new ToolDefinition("search", "Search tool", params);
            assertThat(def.name()).isEqualTo("search");
            assertThat(def.description()).isEqualTo("Search tool");
            assertThat(def.parameters()).isEqualTo(params);
        }

        @Test
        void rejectsNullName() {
            assertThatThrownBy(() -> new ToolDefinition(null, "desc", Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name must not be null");
        }

        @Test
        void rejectsNullDescription() {
            assertThatThrownBy(() -> new ToolDefinition("name", null, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("description must not be null");
        }

        @Test
        void rejectsNullParameters() {
            assertThatThrownBy(() -> new ToolDefinition("name", "desc", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("parameters must not be null");
        }

        @Test
        void equalsAndHashCodeFollowRecordContract() {
            ToolDefinition a = new ToolDefinition("a", "desc", Map.of());
            ToolDefinition b = new ToolDefinition("a", "desc", Map.of());
            ToolDefinition c = new ToolDefinition("b", "desc", Map.of());

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
        }
    }

    // ======================== Message ========================

    @Nested
    class MessageTest {

        @Test
        void userFactoryCreatesUserMessage() {
            Message msg = Message.user("Hello");
            assertThat(msg.role()).isEqualTo(Role.USER);
            assertThat(msg.content()).isEqualTo("Hello");
            assertThat(msg.toolCall()).isNull();
            assertThat(msg.toolCalls()).isNull();
            assertThat(msg.toolCallId()).isNull();
            assertThat(msg.turnIndex()).isZero();
        }

        @Test
        void systemFactoryCreatesSystemMessage() {
            Message msg = Message.system("You are an agent");
            assertThat(msg.role()).isEqualTo(Role.SYSTEM);
            assertThat(msg.content()).isEqualTo("You are an agent");
            assertThat(msg.turnIndex()).isZero();
        }

        @Test
        void assistantFactoryCreatesAssistantMessage() {
            Message msg = Message.assistant("Response", 3);
            assertThat(msg.role()).isEqualTo(Role.ASSISTANT);
            assertThat(msg.content()).isEqualTo("Response");
            assertThat(msg.toolCall()).isNull();
            assertThat(msg.toolCalls()).isNull();
            assertThat(msg.toolCallId()).isNull();
            assertThat(msg.turnIndex()).isEqualTo(3);
        }

        @Test
        void assistantWithToolCallsFactoryCreatesMessageWithToolCalls() {
            List<ToolCall> calls = List.of(
                new ToolCall("id1", "tool1", "{}"),
                new ToolCall("id2", "tool2", "{\"x\":1}")
            );
            Message msg = Message.assistantWithToolCalls("thinking", calls, 2);

            assertThat(msg.role()).isEqualTo(Role.ASSISTANT);
            assertThat(msg.content()).isEqualTo("thinking");
            assertThat(msg.toolCalls()).hasSize(2).containsExactlyElementsOf(calls);
            assertThat(msg.turnIndex()).isEqualTo(2);
        }

        @Test
        void assistantWithToolCallsFactoryCopiesList() {
            List<ToolCall> mutable = new java.util.ArrayList<>(List.of(
                new ToolCall("id1", "tool1", "{}")
            ));
            Message msg = Message.assistantWithToolCalls(null, mutable, 0);
            // Mutating the original list should not affect the message
            mutable.add(new ToolCall("id2", "tool2", "{}"));
            assertThat(msg.toolCalls()).hasSize(1);
        }

        @Test
        void assistantToolCallsFactoryCreatesMessageWithNullContent() {
            List<ToolCall> calls = List.of(new ToolCall("id1", "tool1", "{}"));
            Message msg = Message.assistantToolCalls(calls, 5);

            assertThat(msg.role()).isEqualTo(Role.ASSISTANT);
            assertThat(msg.content()).isNull();
            assertThat(msg.toolCalls()).hasSize(1);
            assertThat(msg.turnIndex()).isEqualTo(5);
        }

        @Test
        void assistantToolCallsFactoryCopiesList() {
            List<ToolCall> mutable = new java.util.ArrayList<>(List.of(
                new ToolCall("id1", "tool1", "{}")
            ));
            Message msg = Message.assistantToolCalls(mutable, 0);
            mutable.add(new ToolCall("id2", "tool2", "{}"));
            assertThat(msg.toolCalls()).hasSize(1);
        }

        @Test
        void toolResultFactoryCreatesToolMessage() {
            Message msg = Message.toolResult("call-42", "Result content", 4);
            assertThat(msg.role()).isEqualTo(Role.TOOL);
            assertThat(msg.content()).isEqualTo("Result content");
            assertThat(msg.toolCallId()).isEqualTo("call-42");
            assertThat(msg.turnIndex()).isEqualTo(4);
        }

        @Test
        void withContentCreatesNewMessageWithReplacedContent() {
            Message original = Message.user("original text");
            Message replaced = Message.withContent(original, "new text");

            assertThat(replaced.content()).isEqualTo("new text");
            assertThat(replaced.role()).isEqualTo(original.role());
            assertThat(replaced.toolCall()).isEqualTo(original.toolCall());
            assertThat(replaced.toolCalls()).isEqualTo(original.toolCalls());
            assertThat(replaced.toolCallId()).isEqualTo(original.toolCallId());
            assertThat(replaced.turnIndex()).isEqualTo(original.turnIndex());
            // Original message is unchanged
            assertThat(original.content()).isEqualTo("original text");
        }

        @Test
        void rejectsNullRole() {
            assertThatThrownBy(() -> new Message(null, "content", null, null, null, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("role must not be null");
        }

        @Test
        void equalsAndHashCodeFollowRecordContract() {
            Message a = Message.user("Hello");
            Message b = Message.user("Hello");
            Message c = Message.user("World");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
        }
    }

    // ======================== ChatResponse ========================

    @Nested
    class ChatResponseTest {

        @Test
        void textFactoryCreatesTextOnlyResponse() {
            ChatResponse resp = ChatResponse.text("Hello");
            assertThat(resp.content()).isEqualTo("Hello");
            assertThat(resp.toolCalls()).isEmpty();
            assertThat(resp.hasToolCalls()).isFalse();
        }

        @Test
        void textFactoryConvertsNullContentToEmpty() {
            ChatResponse resp = ChatResponse.text(null);
            assertThat(resp.content()).isEmpty();
            assertThat(resp.toolCalls()).isEmpty();
        }

        @Test
        void toolCallsFactoryCreatesToolCallResponse() {
            List<ToolCall> calls = List.of(
                new ToolCall("id1", "tool1", "{}"),
                new ToolCall("id2", "tool2", "{}")
            );
            ChatResponse resp = ChatResponse.toolCalls(calls);

            assertThat(resp.content()).isEmpty();
            assertThat(resp.toolCalls()).hasSize(2);
            assertThat(resp.hasToolCalls()).isTrue();
        }

        @Test
        void toolCallsFactoryConvertsNullListToEmpty() {
            ChatResponse resp = ChatResponse.toolCalls(null);
            assertThat(resp.toolCalls()).isEmpty();
            assertThat(resp.hasToolCalls()).isFalse();
        }

        @Test
        void toolCallsFactoryCopiesList() {
            List<ToolCall> mutable = new java.util.ArrayList<>(List.of(
                new ToolCall("id1", "tool1", "{}")
            ));
            ChatResponse resp = ChatResponse.toolCalls(mutable);
            mutable.add(new ToolCall("id2", "tool2", "{}"));
            assertThat(resp.toolCalls()).hasSize(1);
        }

        @Test
        void rejectsNullContent() {
            assertThatThrownBy(() -> new ChatResponse(null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("content must not be null");
        }

        @Test
        void equalsAndHashCodeFollowRecordContract() {
            ChatResponse a = ChatResponse.text("hi");
            ChatResponse b = ChatResponse.text("hi");
            ChatResponse c = ChatResponse.text("bye");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
        }
    }

    // ======================== Session ========================

    @Nested
    class SessionTest {

        @Test
        void createGeneratesSessionWithRandomIdAndDefaults() {
            Session session = Session.create("user1", "openai", "gpt-4");

            assertThat(session.id()).isNotNull();
            assertThat(session.userId()).isEqualTo("user1");
            assertThat(session.title()).isNull();
            assertThat(session.modelProvider()).isEqualTo("openai");
            assertThat(session.modelName()).isEqualTo("gpt-4");
            assertThat(session.systemPrompt()).isNull();
            assertThat(session.metadata()).isEmpty();
        }

        @Test
        void createGeneratesUniqueIds() {
            Session s1 = Session.create("u", "p", "m");
            Session s2 = Session.create("u", "p", "m");
            assertThat(s1.id()).isNotEqualTo(s2.id());
        }

        @Test
        void constructorRejectsNullUserId() {
            assertThatThrownBy(() ->
                new Session(UUID.randomUUID(), null, "title", "p", "m", "prompt", Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userId must not be null");
        }

        @Test
        void constructorRejectsNullModelProvider() {
            assertThatThrownBy(() ->
                new Session(UUID.randomUUID(), "u", "title", null, "m", "prompt", Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelProvider must not be null");
        }

        @Test
        void constructorRejectsNullModelName() {
            assertThatThrownBy(() ->
                new Session(UUID.randomUUID(), "u", "title", "p", null, "prompt", Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelName must not be null");
        }

        @Test
        void constructorConvertsNullMetadataToEmptyMap() {
            Session session = new Session(
                UUID.randomUUID(), "u", "title", "p", "m", "prompt", null);
            assertThat(session.metadata()).isNotNull().isEmpty();
        }

        @Test
        void withMetadataReturnsNewSessionWithAddedEntry() {
            Session session = Session.create("u", "p", "m");
            Session updated = session.withMetadata("key1", "value1");

            assertThat(updated.metadata()).containsEntry("key1", "value1");
            // Original session unchanged
            assertThat(session.metadata()).doesNotContainKey("key1");
            // Other fields preserved
            assertThat(updated.id()).isEqualTo(session.id());
            assertThat(updated.userId()).isEqualTo(session.userId());
            assertThat(updated.modelProvider()).isEqualTo(session.modelProvider());
            assertThat(updated.modelName()).isEqualTo(session.modelName());
        }

        @Test
        void withMetadataOverwritesExistingKey() {
            Session session = Session.create("u", "p", "m")
                .withMetadata("key1", "old");
            Session updated = session.withMetadata("key1", "new");

            assertThat(updated.metadata()).containsEntry("key1", "new");
            assertThat(session.metadata()).containsEntry("key1", "old");
        }

        @Test
        void metadataMapIsImmutable() {
            Session session = Session.create("u", "p", "m").withMetadata("k", "v");
            assertThatThrownBy(() -> session.metadata().put("hack", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void equalsAndHashCodeFollowRecordContract() {
            UUID id = UUID.randomUUID();
            Session a = new Session(id, "u", "t", "p", "m", "s", Map.of());
            Session b = new Session(id, "u", "t", "p", "m", "s", Map.of());
            Session c = new Session(UUID.randomUUID(), "u", "t", "p", "m", "s", Map.of());

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
        }
    }

    // ======================== TurnResult ========================

    @Nested
    class TurnResultTest {

        @Test
        void errorFactoryCreatesErrorResult() {
            TurnResult result = TurnResult.error("Something failed");
            assertThat(result.messages()).isEmpty();
            assertThat(result.completed()).isFalse();
            assertThat(result.error()).isEqualTo("Something failed");
        }

        @Test
        void rejectsNullMessages() {
            assertThatThrownBy(() -> new TurnResult(null, true, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("messages");
        }

        @Test
        void finalTextReturnsErrorMessageWhenErrorIsSet() {
            TurnResult result = TurnResult.error("boom");
            assertThat(result.finalText()).isEqualTo("Error: boom");
        }

        @Test
        void finalTextReturnsErrorMessageWhenErrorIsNonEmpty() {
            TurnResult result = new TurnResult(List.of(), false, "fail");
            assertThat(result.finalText()).isEqualTo("Error: fail");
        }

        @Test
        void finalTextReturnsEmptyStringForEmptyMessagesAndNoError() {
            TurnResult result = new TurnResult(List.of(), true, null);
            assertThat(result.finalText()).isEmpty();
        }

        @Test
        void finalTextReturnsLastAssistantMessageContent() {
            Message userMsg = Message.user("question");
            Message assistantMsg = Message.assistant("final answer", 1);
            TurnResult result = new TurnResult(List.of(userMsg, assistantMsg), true, null);

            assertThat(result.finalText()).isEqualTo("final answer");
        }

        @Test
        void finalTextReturnsLastToolMessageContent() {
            Message assistantMsg = Message.assistant("thinking", 0);
            Message toolMsg = Message.toolResult("call-1", "tool output", 1);
            TurnResult result = new TurnResult(List.of(assistantMsg, toolMsg), true, null);

            assertThat(result.finalText()).isEqualTo("tool output");
        }

        @Test
        void finalTextSkipsUserMessagesAndFindsEarlierAssistantMessage() {
            Message assistantMsg = Message.assistant("earlier answer", 0);
            Message userMsg = Message.user("follow-up");
            TurnResult result = new TurnResult(List.of(assistantMsg, userMsg), true, null);

            assertThat(result.finalText()).isEqualTo("earlier answer");
        }

        @Test
        void finalTextReturnsEmptyWhenNoAssistantOrToolMessage() {
            Message userMsg = Message.user("just a user");
            TurnResult result = new TurnResult(List.of(userMsg), true, null);
            assertThat(result.finalText()).isEmpty();
        }

        @Test
        void finalTextReturnsEmptyStringForEmptyError() {
            TurnResult result = new TurnResult(List.of(Message.assistant("hi", 0)), true, "");
            assertThat(result.finalText()).isEqualTo("hi");
        }

        @Test
        void equalsAndHashCodeFollowRecordContract() {
            List<Message> msgs = List.of(Message.user("hi"));
            TurnResult a = new TurnResult(msgs, true, null);
            TurnResult b = new TurnResult(msgs, true, null);
            TurnResult c = new TurnResult(msgs, false, null);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
        }
    }

    // ======================== ToolContext ========================

    @Nested
    class ToolContextTest {

        @Test
        void componentsMatchConstructorArguments() {
            Session session = Session.create("u", "p", "m");
            AgentProperties props = new AgentProperties();
            ToolContext ctx = new ToolContext(session, props);

            assertThat(ctx.session()).isSameAs(session);
            assertThat(ctx.properties()).isSameAs(props);
        }

        @Test
        void rejectsNullSession() {
            assertThatThrownBy(() -> new ToolContext(null, new AgentProperties()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("session must not be null");
        }

        @Test
        void rejectsNullProperties() {
            assertThatThrownBy(() -> new ToolContext(Session.create("u", "p", "m"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("properties must not be null");
        }

        @Test
        void equalsAndHashCodeFollowRecordContract() {
            Session session = Session.create("u", "p", "m");
            AgentProperties props = new AgentProperties();
            ToolContext a = new ToolContext(session, props);
            ToolContext b = new ToolContext(session, props);
            // Different session (new UUID) → not equal
            ToolContext c = new ToolContext(Session.create("u", "p", "m"), props);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
        }
    }
}