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
}