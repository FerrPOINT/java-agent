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
}