package com.azhukov.agent.bot.keyboard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelKeyboardBuilderTest {

    private final ModelKeyboardBuilder builder = new ModelKeyboardBuilder();

    private List<String> makeModels(int count) {
        List<String> models = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            models.add("model-" + i);
        }
        return models;
    }

    @Test
    void build_firstPage_showsFirstModels() {
        List<String> models = makeModels(10);
        var rows = builder.build(models, 0);

        // 8 model buttons + 1 nav row (Next only) = 9 rows
        assertThat(rows).hasSize(9);

        // First 8 rows are model buttons
        for (int i = 0; i < 8; i++) {
            assertThat(rows.get(i)).hasSize(1);
            assertThat(rows.get(i).get(0).text()).isEqualTo("model-" + i);
            assertThat(rows.get(i).get(0).callbackData()).isEqualTo("mp:model-" + i);
        }

        // Last row is navigation with "Next ▶" button
        List<KeyboardButton> navRow = rows.get(8);
        assertThat(navRow).hasSize(1);
        assertThat(navRow.get(0).text()).contains("Next");
        assertThat(navRow.get(0).callbackData()).isEqualTo("mpp:1");
    }

    @Test
    void build_secondPage_showsRemainingAndPrev() {
        List<String> models = makeModels(10);
        var rows = builder.build(models, 1);

        // 2 model buttons + 1 nav row (Prev only) = 3 rows
        assertThat(rows).hasSize(3);

        // First 2 rows are model buttons (model-8, model-9)
        assertThat(rows.get(0).get(0).text()).isEqualTo("model-8");
        assertThat(rows.get(1).get(0).text()).isEqualTo("model-9");

        // Last row is navigation with "◀ Prev" button
        List<KeyboardButton> navRow = rows.get(2);
        assertThat(navRow).hasSize(1);
        assertThat(navRow.get(0).text()).contains("Prev");
        assertThat(navRow.get(0).callbackData()).isEqualTo("mpp:0");
    }

    @Test
    void build_emptyModels_returnsEmpty() {
        var rows = builder.build(List.of(), 0);
        assertThat(rows).isEmpty();
    }
}