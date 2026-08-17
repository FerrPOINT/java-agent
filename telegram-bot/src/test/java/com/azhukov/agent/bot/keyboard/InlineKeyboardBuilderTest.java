package com.azhukov.agent.bot.keyboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InlineKeyboardBuilderTest {

    private InlineKeyboardBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new InlineKeyboardBuilder(new ObjectMapper());
    }

    @Test
    void build_emptyList_returnsEmptyKeyboard() {
        String json = builder.build(List.of());

        assertThat(json).contains("\"inline_keyboard\":[]");
    }

    @Test
    void build_nullList_returnsEmptyKeyboard() {
        String json = builder.build(null);

        assertThat(json).contains("\"inline_keyboard\":[]");
    }

    @Test
    void build_singleButton_returnsValidJson() {
        String json = builder.build(List.of(
            List.of(new KeyboardButton("Click me", "action:go"))
        ));

        assertThat(json).contains("\"inline_keyboard\":");
        assertThat(json).contains("\"text\":\"Click me\"");
        assertThat(json).contains("\"callback_data\":\"action:go\"");
    }

    @Test
    void build_multipleRows_returnsValidJson() {
        String json = builder.build(List.of(
            List.of(new KeyboardButton("Row1Btn1", "cmd:1"), new KeyboardButton("Row1Btn2", "cmd:2")),
            List.of(new KeyboardButton("Row2Btn1", "cmd:3"))
        ));

        assertThat(json).contains("\"text\":\"Row1Btn1\"");
        assertThat(json).contains("\"text\":\"Row1Btn2\"");
        assertThat(json).contains("\"text\":\"Row2Btn1\"");
        // Verify there are two rows
        assertThat(json.split("\\],\\[").length).isEqualTo(2);
    }

    @Test
    void build_specialCharactersInText_escapedProperly() {
        String json = builder.build(List.of(
            List.of(new KeyboardButton("Button \"quoted\"", "cmd:\"val\""))
        ));

        assertThat(json).contains("\\\"quoted\\\"");
        assertThat(json).contains("cmd:\\\"val\\\"");
    }

    @Test
    void build_emptyRow_returnsValidJson() {
        String json = builder.build(List.of(
            List.of()
        ));

        assertThat(json).contains("\"inline_keyboard\":[[]]");
    }

    @Test
    void build_unicodeText_handledCorrectly() {
        String json = builder.build(List.of(
            List.of(new KeyboardButton("Выбрать модель", "model:gpt-4"))
        ));

        assertThat(json).contains("Выбрать модель");
        assertThat(json).contains("model:gpt-4");
    }

    @Test
    void build_producesParseableJson() throws Exception {
        String json = builder.build(List.of(
            List.of(new KeyboardButton("Btn1", "cmd:1"), new KeyboardButton("Btn2", "cmd:2")),
            List.of(new KeyboardButton("Btn3", "cmd:3"))
        ));

        // Verify it's valid JSON by parsing
        ObjectMapper mapper = new ObjectMapper();
        var node = mapper.readTree(json);

        assertThat(node.has("inline_keyboard")).isTrue();
        assertThat(node.get("inline_keyboard").isArray()).isTrue();
        assertThat(node.get("inline_keyboard").size()).isEqualTo(2);
        assertThat(node.get("inline_keyboard").get(0).size()).isEqualTo(2);
        assertThat(node.get("inline_keyboard").get(0).get(0).get("text").asText()).isEqualTo("Btn1");
        assertThat(node.get("inline_keyboard").get(0).get(0).get("callback_data").asText()).isEqualTo("cmd:1");
    }

    @Test
    void build_newlineInText_escapedProperly() {
        String json = builder.build(List.of(
            List.of(new KeyboardButton("Line1\nLine2", "cmd:1"))
        ));

        assertThat(json).contains("\\n");
    }

    // ─── P37: Permission-Aware Keyboard tests ─────────────────────

    @Test
    void buildApprovalKeyboard_canExecuteTrue_includesAllButtons() throws Exception {
        String json = builder.buildApprovalKeyboard(42, true);

        var node = new ObjectMapper().readTree(json);
        assertThat(node.has("inline_keyboard")).isTrue();
        var keyboard = node.get("inline_keyboard");
        assertThat(keyboard.size()).isEqualTo(2); // two rows

        // Row 1: Execute once + Execute (session)
        assertThat(keyboard.get(0).size()).isEqualTo(2);
        assertThat(keyboard.get(0).get(0).get("text").asText()).contains("Execute once");
        assertThat(keyboard.get(0).get(0).get("callback_data").asText()).isEqualTo("ea:once:42");
        assertThat(keyboard.get(0).get(1).get("callback_data").asText()).isEqualTo("ea:session:42");

        // Row 2: Execute (always) + Deny
        assertThat(keyboard.get(1).size()).isEqualTo(2);
        assertThat(keyboard.get(1).get(0).get("callback_data").asText()).isEqualTo("ea:always:42");
        assertThat(keyboard.get(1).get(1).get("text").asText()).contains("Deny");
        assertThat(keyboard.get(1).get(1).get("callback_data").asText()).isEqualTo("ea:deny:42");
    }

    @Test
    void buildApprovalKeyboard_canExecuteFalse_onlyDenyButton() throws Exception {
        String json = builder.buildApprovalKeyboard(99, false);

        var node = new ObjectMapper().readTree(json);
        assertThat(node.has("inline_keyboard")).isTrue();
        var keyboard = node.get("inline_keyboard");
        assertThat(keyboard.size()).isEqualTo(1); // one row

        // Only one button: Deny
        assertThat(keyboard.get(0).size()).isEqualTo(1);
        assertThat(keyboard.get(0).get(0).get("text").asText()).contains("Deny");
        assertThat(keyboard.get(0).get(0).get("callback_data").asText()).isEqualTo("ea:deny:99");
    }

    @Test
    void buildApprovalKeyboard_canExecuteFalse_doesNotContainExecute() {
        String json = builder.buildApprovalKeyboard(77, false);

        assertThat(json).doesNotContain("Execute");
        assertThat(json).doesNotContain("ea:once:77");
        assertThat(json).doesNotContain("ea:session:77");
        assertThat(json).doesNotContain("ea:always:77");
        assertThat(json).contains("ea:deny:77");
    }
}