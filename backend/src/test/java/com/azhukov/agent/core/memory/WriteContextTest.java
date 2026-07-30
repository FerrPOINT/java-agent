package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.skill.WriteOrigin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WriteContextTest {

    @AfterEach
    void tearDown() {
        WriteContext.clear();
    }

    @Test
    void noContext_defaultsToForeground() {
        assertThat(WriteContext.effectiveOrigin()).isEqualTo(WriteOrigin.FOREGROUND);
        assertThat(WriteContext.effectiveExecutionContext()).isEqualTo("foreground");
    }

    @Test
    void noContext_provenanceIsEmpty() {
        Map<String, String> provenance = WriteContext.buildProvenance();
        assertThat(provenance).isEmpty();
    }

    @Test
    void setReviewContext_setsBackgroundReviewOrigin() {
        WriteContext.setReviewContext("session-123", "parent-456", "background-review");

        assertThat(WriteContext.effectiveOrigin()).isEqualTo(WriteOrigin.BACKGROUND_REVIEW);
        assertThat(WriteContext.effectiveExecutionContext()).isEqualTo("background_review");
    }

    @Test
    void setReviewContext_provenanceContainsAllFields() {
        WriteContext.setReviewContext("session-123", "parent-456", "cli");

        Map<String, String> provenance = WriteContext.buildProvenance();
        assertThat(provenance)
            .containsEntry("write_origin", "BACKGROUND_REVIEW")
            .containsEntry("execution_context", "background_review")
            .containsEntry("session_id", "session-123")
            .containsEntry("parent_session_id", "parent-456")
            .containsEntry("platform", "cli")
            .containsEntry("tool_name", "memory");
    }

    @Test
    void setReviewContext_nullParentSessionId_omitsParentFromProvenance() {
        WriteContext.setReviewContext("session-123", null, "cli");

        Map<String, String> provenance = WriteContext.buildProvenance();
        assertThat(provenance)
            .containsKey("write_origin")
            .containsKey("session_id")
            .doesNotContainKey("parent_session_id");
    }

    @Test
    void setCustomContext_setsAllFields() {
        WriteContext.set(WriteOrigin.CURATOR, "curator", "sess-1", "parent-1", "telegram", "skill_manage");

        WriteContext ctx = WriteContext.current();
        assertThat(ctx).isNotNull();
        assertThat(ctx.writeOrigin()).isEqualTo(WriteOrigin.CURATOR);
        assertThat(ctx.executionContext()).isEqualTo("curator");
        assertThat(ctx.sessionId()).isEqualTo("sess-1");
        assertThat(ctx.parentSessionId()).isEqualTo("parent-1");
        assertThat(ctx.platform()).isEqualTo("telegram");
        assertThat(ctx.toolName()).isEqualTo("skill_manage");
    }

    @Test
    void clear_removesContext() {
        WriteContext.setReviewContext("session-123", "parent-456", "cli");
        WriteContext.clear();

        assertThat(WriteContext.current()).isNull();
        assertThat(WriteContext.effectiveOrigin()).isEqualTo(WriteOrigin.FOREGROUND);
    }

    @Test
    void buildProvenance_blankFieldsAreOmitted() {
        WriteContext.set(WriteOrigin.BACKGROUND_REVIEW, "background_review", "", "", "", "");

        Map<String, String> provenance = WriteContext.buildProvenance();
        assertThat(provenance)
            .containsEntry("write_origin", "BACKGROUND_REVIEW")
            .containsEntry("execution_context", "background_review");
        assertThat(provenance).hasSize(2);
    }
}