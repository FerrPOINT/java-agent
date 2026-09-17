package com.azhukov.agent.bot.streaming;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolEmojiMapTest {

    @Test
    void formatsTodoArrayAsPlanCountWithoutSerializedPayload() {
        String line = ToolEmojiMap.formatToolCall("todo", """
            {"todos":[{"id":"inventory","content":"Inventory tools","status":"in_progress"},
            {"id":"verify","content":"Verify output","status":"pending"}]}
            """);

        assertThat(line).isEqualTo("📋 Planning 2 task(s)");
        assertThat(line).doesNotContain("inventory").doesNotContain("\\\"");
    }

    @Test
    void formatsStringifiedTodoItemsAsPlanCountWithoutSerializedPayload() {
        String line = ToolEmojiMap.formatToolCall("todo", """
            {"todos":["{\\"id\\":\\"inventory\\",\\"content\\":\\"Inventory tools\\",\\"status\\":\\"in_progress\\"}"]}
            """);

        assertThat(line).isEqualTo("📋 Planning 1 task(s)");
        assertThat(line).doesNotContain("inventory").doesNotContain("\\\"");
    }

    @Test
    void formatsTodoMergeAsUpdateCount() {
        String line = ToolEmojiMap.formatToolCall("todo", """
            {"merge":true,"todos":[{"id":"inventory","status":"completed"}]}
            """);

        assertThat(line).isEqualTo("📋 Updating 1 task(s)");
    }

    @Test
    void formatsTodoReadAsReadableAction() {
        assertThat(ToolEmojiMap.formatToolCall("todo", "{}"))
            .isEqualTo("📋 Updating tasks");
        assertThat(ToolEmojiMap.formatToolCall("todo", "{\"merge\":true}"))
            .isEqualTo("📋 Reading task list");
    }

    @Test
    void retainsGenericPreviewForNonTodoTools() {
        assertThat(ToolEmojiMap.formatToolCall("web_search", "{\"query\":\"news\"}"))
            .isEqualTo("🔍 web_search: \"news\"");
    }
}
