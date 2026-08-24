package com.azhukov.agent.core.tool;

import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultClassifierTest {

    private final ToolResultClassifier classifier = new ToolResultClassifier();

    @Test
    void classify_success() {
        ToolResult result = ToolResult.ok("normal content");
        assertThat(classifier.classify(result)).isEqualTo(ToolResultClassifier.ResultType.SUCCESS);
    }

    @Test
    void classify_failure() {
        ToolResult result = ToolResult.fail("some error");
        assertThat(classifier.classify(result)).isEqualTo(ToolResultClassifier.ResultType.FAILURE);
    }

    @Test
    void classify_silent() {
        assertThat(classifier.classify(ToolResult.ok("***"))).isEqualTo(ToolResultClassifier.ResultType.SILENT);
        assertThat(classifier.classify(ToolResult.ok(""))).isEqualTo(ToolResultClassifier.ResultType.SILENT);
    }

    @Test
    void classify_partial() {
        ToolResult result = ToolResult.ok("partial error occurred");
        assertThat(classifier.classify(result)).isEqualTo(ToolResultClassifier.ResultType.PARTIAL);
    }

    // ── Hermes parity: toolMayHaveSideEffect ────────────────────────────

    @Test
    void toolMayHaveSideEffect_readOnlyToolsReturnFalse() {
        assertThat(ToolResultClassifier.toolMayHaveSideEffect("read_file")).isFalse();
        assertThat(ToolResultClassifier.toolMayHaveSideEffect("search_files")).isFalse();
        assertThat(ToolResultClassifier.toolMayHaveSideEffect("session_search")).isFalse();
        assertThat(ToolResultClassifier.toolMayHaveSideEffect("skill_view")).isFalse();
        assertThat(ToolResultClassifier.toolMayHaveSideEffect("skills_list")).isFalse();
        assertThat(ToolResultClassifier.toolMayHaveSideEffect("web_extract")).isFalse();
        assertThat(ToolResultClassifier.toolMayHaveSideEffect("web_search")).isFalse();
        assertThat(ToolResultClassifier.toolMayHaveSideEffect("vision_analyze")).isFalse();
    }

    @Test
    void toolMayHaveSideEffect_mutatingToolsReturnTrue() {
        assertThat(ToolResultClassifier.toolMayHaveSideEffect("write_file")).isTrue();
        assertThat(ToolResultClassifier.toolMayHaveSideEffect("patch")).isTrue();
        assertThat(ToolResultClassifier.toolMayHaveSideEffect("terminal")).isTrue();
        assertThat(ToolResultClassifier.toolMayHaveSideEffect("skill_manage")).isTrue();
    }

    @Test
    void toolMayHaveSideEffect_unknownToolReturnsTrue() {
        assertThat(ToolResultClassifier.toolMayHaveSideEffect("some_unknown_tool")).isTrue();
        assertThat(ToolResultClassifier.toolMayHaveSideEffect("")).isTrue();
        assertThat(ToolResultClassifier.toolMayHaveSideEffect(null)).isTrue();
    }

    // ── Hermes parity: fileMutationResultLanded ────────────────────────

    @Test
    void fileMutationResultLanded_writeFileSuccess() {
        String result = "{\"bytes_written\": 42, \"path\": \"/tmp/test.txt\"}";
        assertThat(ToolResultClassifier.fileMutationResultLanded("write_file", result)).isTrue();
    }

    @Test
    void fileMutationResultLanded_writeFileError() {
        String result = "{\"error\": \"permission denied\"}";
        assertThat(ToolResultClassifier.fileMutationResultLanded("write_file", result)).isFalse();
    }

    @Test
    void fileMutationResultLanded_patchSuccess() {
        String result = "{\"success\": true, \"lines_changed\": 3}";
        assertThat(ToolResultClassifier.fileMutationResultLanded("patch", result)).isTrue();
    }

    @Test
    void fileMutationResultLanded_patchFailure() {
        String result = "{\"success\": false, \"error\": \"no match\"}";
        assertThat(ToolResultClassifier.fileMutationResultLanded("patch", result)).isFalse();
    }

    @Test
    void fileMutationResultLanded_nonMutatingTool() {
        assertThat(ToolResultClassifier.fileMutationResultLanded("read_file", "file content")).isFalse();
        assertThat(ToolResultClassifier.fileMutationResultLanded(null, "data")).isFalse();
    }

    @Test
    void fileMutationResultLanded_nonJson() {
        assertThat(ToolResultClassifier.fileMutationResultLanded("write_file", "not json")).isFalse();
    }

    @Test
    void fileMutationResultLanded_nullResult() {
        assertThat(ToolResultClassifier.fileMutationResultLanded("write_file", null)).isFalse();
    }
}