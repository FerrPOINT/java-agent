package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClarifyToolTest {

    private final ClarifyTool tool = new ClarifyTool();

    private Session dummySession() {
        return Session.create("test-user", "test-provider", "test-model");
    }

    private Message dummyMessage() {
        return Message.assistant("test", 0);
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
    @DisplayName("Should handle empty question")
    void shouldHandleEmptyQuestion() {
        String args = "{\"question\":\"\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        assertEquals("", result.content());
    }

    @Test
    @DisplayName("Should handle null question gracefully")
    void shouldHandleNullQuestion() {
        String args = "{\"question\":null}";
        assertThrows(NullPointerException.class, () -> {
            tool.execute(args, dummyMessage(), dummySession());
        });
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
    @DisplayName("Should handle missing question field")
    void shouldHandleMissingQuestionField() {
        String args = "{}";
        assertThrows(NullPointerException.class, () -> {
            tool.execute(args, dummyMessage(), dummySession());
        });
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
}