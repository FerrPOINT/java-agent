package com.azhukov.agent.core.sanitizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallArgumentRepairTest {

    private final ToolCallArgumentRepair repair = new ToolCallArgumentRepair();

    @Test
    void repair_validJson_returnsAsIs() {
        String result = repair.repair("{\"key\":\"value\"}");
        assertThat(result).contains("\"key\"");
        assertThat(result).contains("\"value\"");
    }

    @Test
    void repair_emptyString_returnsEmptyObject() {
        assertThat(repair.repair("")).isEqualTo("{}");
        assertThat(repair.repair("   ")).isEqualTo("{}");
    }

    @Test
    void repair_none_returnsEmptyObject() {
        assertThat(repair.repair("None")).isEqualTo("{}");
    }

    @Test
    void repair_trailingComma_fixed() {
        String result = repair.repair("{\"key\":\"value\",}");
        assertThat(result).contains("\"key\"");
        assertThat(result).doesNotEndWith(",}");
    }

    @Test
    void repair_trailingCommaInArray_fixed() {
        String result = repair.repair("[1,2,3,]");
        assertThat(result).contains("1");
        assertThat(result).doesNotEndWith(",]");
    }

    @Test
    void repair_truncatedJson_closesStructures() {
        String result = repair.repair("{\"key\":\"value\"");
        // Should have closed the brace
        assertThat(result).contains("}");
    }

    @Test
    void repair_excessClosingBraces_removed() {
        String result = repair.repair("{\"key\":\"value\"}}");
        // Should be valid JSON after removing excess
        assertThat(result).contains("\"key\"");
    }

    @Test
    void repair_unrepairable_returnsEmptyObject() {
        String result = repair.repair("}}}{{{");
        assertThat(result).isEqualTo("{}");
    }

    @Test
    void repair_controlCharsInJsonStrings_escaped() {
        String result = repair.repair("{\"key\":\"value\\twith\ttab\"}");
        assertThat(result).contains("key");
    }

    @Test
    void repair_null_returnsEmptyObject() {
        assertThat(repair.repair(null)).isEqualTo("{}");
    }

    @Test
    void repair_withToolName_logsAndRepairs() {
        String result = repair.repair("{\"path\":\"/tmp/file\"}", "write_file");
        assertThat(result).contains("/tmp/file");
    }

    @Test
    void escapeInvalidChars_replacesControlChars() {
        String input = "{\"key\":\"val\tue\"}";
        String result = ToolCallArgumentRepair.escapeInvalidCharsInJsonStrings(input);
        // The tab inside the string should be escaped as \u0009
        assertThat(result).contains("\\u0009");
        assertThat(result).doesNotContain("\t");
    }

    @Test
    void repair_nestedArrayWithTrailingComma_fixed() {
        String result = repair.repair("{\"items\":[1,2,3,],\"name\":\"test\"}");
        assertThat(result).contains("test");
    }
}