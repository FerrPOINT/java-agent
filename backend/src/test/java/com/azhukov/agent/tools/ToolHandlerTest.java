package com.azhukov.agent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolHandlerTest {

    static class SimpleArgs {
        @ToolParam(description = "name")
        private String name;
        @ToolParam(description = "count", required = false)
        private int count;
        @ToolParam(description = "active", required = false)
        private boolean active;

        public String name() { return name; }
        public int count() { return count; }
        public boolean active() { return active; }
    }

    @Test
    void parsesPojoFieldsWithoutGetters() {
        SimpleArgs args = ToolHandler.parseJson("{\"name\":\"test\",\"count\":42,\"active\":true}", SimpleArgs.class);
        assertThat(args.name()).isEqualTo("test");
        assertThat(args.count()).isEqualTo(42);
        assertThat(args.active()).isTrue();
    }

    @Test
    void ignoresUnknownProperties() {
        SimpleArgs args = ToolHandler.parseJson("{\"name\":\"test\",\"extra\":\"ignored\"}", SimpleArgs.class);
        assertThat(args.name()).isEqualTo("test");
        assertThat(args.count()).isZero();
        assertThat(args.active()).isFalse();
    }

    @Test
    void throwsOnInvalidJson() {
        assertThatThrownBy(() -> ToolHandler.parseJson("not-json", SimpleArgs.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid tool arguments");
    }

    static record RecordArgs(@ToolParam(description = "value") String value) {}

    @Test
    void parsesRecords() {
        RecordArgs args = ToolHandler.parseJson("{\"value\":\"abc\"}", RecordArgs.class);
        assertThat(args.value()).isEqualTo("abc");
    }

    static record PathArgs(
        @ToolParam(description = "path") String path,
        @ToolParam(description = "content") String content
    ) {}

    @Test
    void repairsUnescapedWindowsPathBackslashesOnlyForPathFields() {
        String json = "{\"path\":\"C:\\Users\\ferru\\file.txt\",\"content\":\"line1\\nline2\"}";

        PathArgs args = ToolHandler.parseJson(json, PathArgs.class);

        assertThat(args.path()).isEqualTo("C:\\Users\\ferru\\file.txt");
        assertThat(args.content()).isEqualTo("line1\nline2");
    }
}
