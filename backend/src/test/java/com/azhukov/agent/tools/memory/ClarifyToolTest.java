package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClarifyToolTest {

    private final ClarifyTool tool = new ClarifyTool();
    private final ObjectMapper mapper = new ObjectMapper();

    private Session dummySession() {
        return Session.create("test-user", "test-provider", "test-model");
    }

    private Message dummyMessage() {
        return Message.assistant("test", 0);
    }

    private JsonNode errorJson(ToolResult result) throws Exception {
        assertFalse(result.success());
        assertFalse(result.content().isBlank());
        JsonNode root = mapper.readTree(result.content());
        assertFalse(root.path("success").asBoolean());
        assertFalse(root.path("error").asText().isBlank());
        assertEquals(root.path("error").asText(), result.error());
        return root;
    }

    @Test
    @DisplayName("Should register under the dedicated clarify toolset")
    void shouldUseClarifyToolset() {
        AgentTool annotation = ClarifyTool.class.getAnnotation(AgentTool.class);

        assertEquals("clarify", annotation.toolset());
        assertTrue(annotation.description().contains("does not block waiting for user input"));
        assertFalse(annotation.description().contains("user_response will"));
    }

    @Test
    @DisplayName("Should return question text from valid arguments")
    void shouldReturnQuestionFromValidArgs() {
        String args = "{\"question\":\"What color do you prefer?\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        assertEquals("What color do you prefer?", result.content());
    }

    @Test
    @DisplayName("Should reject empty question")
    void shouldRejectEmptyQuestion() throws Exception {
        String args = "{\"question\":\"\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        JsonNode root = errorJson(result);
        assertTrue(root.path("error").asText().contains("No question provided"));
    }

    @Test
    @DisplayName("Should reject null question")
    void shouldRejectNullQuestion() throws Exception {
        String args = "{\"question\":null}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());
        JsonNode root = errorJson(result);
        assertTrue(root.path("error").asText().contains("No question provided"));
    }

    @Test
    @DisplayName("Should handle complex question with special characters")
    void shouldHandleSpecialCharacters() {
        String args = "{\"question\":\"What's the \\\"value\\\" of x > 5?\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        assertNotNull(result.content());
    }

    @Test
    @DisplayName("Should reject a missing question")
    void shouldRejectMissingQuestionField() throws Exception {
        ToolResult result = tool.execute("{}", dummyMessage(), dummySession());
        JsonNode root = errorJson(result);
        assertTrue(root.path("error").asText().contains("No question provided"));
    }

    @Test
    @DisplayName("Should handle multi-line question")
    void shouldHandleMultiLineQuestion() {
        String args = "{\"question\":\"Line 1\\nLine 2\\nLine 3\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        assertTrue(result.content().contains("Line 1"));
        assertTrue(result.content().contains("Line 3"));
    }

    @Test
    @DisplayName("Should handle question with Unicode characters")
    void shouldHandleUnicodeCharacters() {
        String args = "{\"question\":\"Какой цвет вы предпочитаете? 🎨\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        assertTrue(result.content().contains("Какой цвет"));
    }

    @Test
    @DisplayName("Should handle very long question")
    void shouldHandleLongQuestion() {
        String longQuestion = "a".repeat(10000);
        String args = "{\"question\":\"" + longQuestion + "\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        assertEquals(10000, result.content().length());
    }

    // ── Multi-choice tests ──

    @Test
    @DisplayName("Should format numbered choices with Other option when choices provided")
    void shouldFormatMultiChoiceWithOtherOption() {
        String args = "{\"question\":\"Which framework?\",\"choices\":[\"Spring\",\"Quarkus\",\"Micronaut\"]}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        String content = result.content();
        assertTrue(content.contains("Which framework?"));
        assertTrue(content.contains("1. Spring"));
        assertTrue(content.contains("2. Quarkus"));
        assertTrue(content.contains("3. Micronaut"));
        assertTrue(content.contains("4. Other (type answer)"));
    }

    @Test
    @DisplayName("Should append Other as the last numbered option")
    void shouldAppendOtherAsLastOption() {
        String args = "{\"question\":\"Pick one:\",\"choices\":[\"A\",\"B\"]}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        String content = result.content();
        // Other should be numbered 3 (after A=1, B=2)
        assertTrue(content.contains("3. Other (type answer)"));
        assertFalse(content.contains("4."));
    }

    @Test
    @DisplayName("Should truncate choices to MAX_CHOICES (4) and still add Other")
    void shouldTruncateChoicesToMax() {
        String args = "{\"question\":\"Pick:\",\"choices\":[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\"]}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        String content = result.content();
        assertTrue(content.contains("1. A"));
        assertTrue(content.contains("4. D"));
        assertFalse(content.contains("5. E"));
        assertFalse(content.contains("6. F"));
        // Other should be numbered 5 (after 4 choices)
        assertTrue(content.contains("5. Other (type answer)"));
    }

    @Test
    @DisplayName("Should keep open-ended behavior when choices is null")
    void shouldKeepOpenEndedWhenChoicesNull() {
        String args = "{\"question\":\"What do you think?\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        assertEquals("What do you think?", result.content());
        assertFalse(result.content().contains("Other (type answer)"));
    }

    @Test
    @DisplayName("Should keep open-ended behavior when choices is empty list")
    void shouldKeepOpenEndedWhenChoicesEmpty() {
        String args = "{\"question\":\"What do you think?\",\"choices\":[]}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        assertEquals("What do you think?", result.content());
        assertFalse(result.content().contains("Other (type answer)"));
    }

    @Test
    @DisplayName("Should handle 4 choices (exactly at MAX_CHOICES)")
    void shouldHandleExactlyMaxChoices() {
        String args = "{\"question\":\"Pick:\",\"choices\":[\"A\",\"B\",\"C\",\"D\"]}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        String content = result.content();
        assertTrue(content.contains("1. A"));
        assertTrue(content.contains("4. D"));
        assertTrue(content.contains("5. Other (type answer)"));
    }

    @Test
    @DisplayName("Should handle single choice + Other")
    void shouldHandleSingleChoicePlusOther() {
        String args = "{\"question\":\"Proceed?\",\"choices\":[\"Yes\"]}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        String content = result.content();
        assertTrue(content.contains("1. Yes"));
        assertTrue(content.contains("2. Other (type answer)"));
    }

    @Test
    @DisplayName("Should flatten dict-shaped choices like Hermes")
    void shouldFlattenDictShapedChoices() {
        String args = """
            {
              "question":"Which target?",
              "choices":[
                {"description":"Staging"},
                {"label":"Production"},
                {"name":"raw-id"}
              ]
            }
            """;

        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        String content = result.content();
        assertTrue(content.contains("1. Staging (Recommended)"));
        assertTrue(content.contains("2. Production"));
        assertTrue(content.contains("3. Other (type answer)"));
        assertFalse(content.contains("raw-id"));
        assertFalse(content.contains("{description"));
    }

    @Test
    @DisplayName("Should include question text before numbered choices")
    void shouldIncludeQuestionBeforeChoices() {
        String args = "{\"question\":\"Which language?\",\"choices\":[\"Java\",\"Kotlin\"]}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        String content = result.content();
        int questionEnd = content.indexOf("Which language?");
        int firstChoice = content.indexOf("1. Java");
        assertTrue(questionEnd < firstChoice, "Question should appear before choices");
    }

    @Test
    @DisplayName("Should format batch questions with independent choices")
    void shouldFormatBatchQuestions() {
        String args = """
            {"questions":[
              {"id":"target","question":"Which target?","choices":["staging","production"]},
              {"id":"regions","question":"Which regions?","choices":["EU","US"],"multi_select":true}
            ]}
            """;

        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        assertTrue(result.content().contains("Question 1:"));
        assertTrue(result.content().contains("Which target?"));
        assertTrue(result.content().contains("Question 2:"));
        assertTrue(result.content().contains("Select all that apply."));
    }

    @Test
    @DisplayName("Should accept bare-string batch questions and flatten nested choices")
    void shouldAcceptBareStringBatchQuestionsAndFlattenChoices() {
        String args = """
            {"questions":[
              "First question?",
              {"question":"Second question?","choices":[{"text":"Alpha"},{"title":"Beta"}],"multi_select":true}
            ]}
            """;

        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        String content = result.content();
        assertTrue(content.contains("Question 1:"));
        assertTrue(content.contains("First question?"));
        assertTrue(content.contains("Question 2:"));
        assertTrue(content.contains("1. Alpha (Recommended)"));
        assertTrue(content.contains("2. Beta"));
        assertTrue(content.contains("Select all that apply."));
    }

    @Test
    @DisplayName("Should reject more than five batch questions")
    void shouldRejectOversizedBatch() throws Exception {
        String args = """
            {"questions":[
              {"question":"1"},{"question":"2"},{"question":"3"},
              {"question":"4"},{"question":"5"},{"question":"6"}
            ]}
            """;

        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        JsonNode root = errorJson(result);
        assertTrue(root.path("error").asText().contains("at most 5"));
    }

    @Test
    @DisplayName("Should reject invalid JSON with structured error")
    void shouldRejectInvalidJsonWithStructuredError() throws Exception {
        ToolResult result = tool.execute("not-json", dummyMessage(), dummySession());

        JsonNode root = errorJson(result);
        assertTrue(root.path("error").asText().contains("Invalid tool arguments"));
    }

    @Test
    @DisplayName("Should reject non-array top-level choices like Hermes")
    void shouldRejectNonArrayTopLevelChoices() throws Exception {
        ToolResult result = tool.execute(
            "{\"question\":\"Pick one\",\"choices\":\"yes\"}",
            dummyMessage(), dummySession());

        JsonNode root = errorJson(result);
        assertTrue(root.path("error").asText().contains("choices must be a list"));
    }

    @Test
    @DisplayName("Should reject non-array questions like Hermes")
    void shouldRejectNonArrayQuestions() throws Exception {
        ToolResult result = tool.execute(
            "{\"questions\":{\"question\":\"Pick one\"}}",
            dummyMessage(), dummySession());

        JsonNode root = errorJson(result);
        assertTrue(root.path("error").asText().contains("questions must be an array"));
    }

    @Test
    @DisplayName("Should render multi-select instruction for a single question")
    void shouldRenderMultiSelectInstruction() {
        ToolResult result = tool.execute(
            "{\"question\":\"Pick tools\",\"choices\":[\"web\",\"terminal\"],\"multi_select\":true}",
            dummyMessage(), dummySession());

        assertTrue(result.success());
        assertTrue(result.content().contains("Select all that apply."));
    }

    @Test
    @DisplayName("formatQuestion should produce correct output for 3 choices")
    void formatQuestionShouldProduceCorrectOutput() {
        ClarifyTool.ClarifyArgs args = new ClarifyTool.ClarifyArgs();
        // Use reflection to set private fields since we don't have setters
        setField(args, "question", "Which option?");
        setField(args, "choices", List.of("Red", "Green", "Blue"));

        String formatted = ClarifyTool.formatQuestion(args);

        assertTrue(formatted.startsWith("Which option?"));
        assertTrue(formatted.contains("1. Red"));
        assertTrue(formatted.contains("2. Green"));
        assertTrue(formatted.contains("3. Blue"));
        assertTrue(formatted.contains("4. Other (type answer)"));
        // No trailing newline at the end
        assertTrue(formatted.endsWith("Other (type answer)"));
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
