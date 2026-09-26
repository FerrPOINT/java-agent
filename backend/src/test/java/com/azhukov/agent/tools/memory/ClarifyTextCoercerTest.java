package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.tool.ClarifyGatewayStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClarifyTextCoercer contract (clarify_gateway.py _coerce_text_response_detailed):
 * numbers resolve to choices; labels match case-insensitively ignoring the
 * (Recommended) suffix; multi-select takes "1,3" and label lists returning a
 * JSON array; out-of-range numbers are failed selections (null), free prose
 * on choice prompts is null (route normally); open-ended accepts any text.
 */
class ClarifyTextCoercerTest {

    private static ClarifyGatewayStore.PendingClarify entry(List<String> choices, boolean multi) {
        ClarifyGatewayStore store = new ClarifyGatewayStore();
        return store.register("s", "Q", choices, multi);
    }

    @Test
    void numericPickResolvesToChoice() {
        var e = entry(List.of("staging (Recommended)", "prod"), false);
        assertThat(ClarifyTextCoercer.coerce(e, "1")).isEqualTo("staging (Recommended)");
        assertThat(ClarifyTextCoercer.coerce(e, "2")).isEqualTo("prod");
    }

    @Test
    void outOfRangeNumberIsFailedSelection() {
        var e = entry(List.of("a", "b"), false);
        assertThat(ClarifyTextCoercer.coerce(e, "5")).isNull();
        assertThat(ClarifyTextCoercer.looksLikeSelection("5", List.of("a", "b"))).isTrue();
    }

    @Test
    void labelMatchesCaseInsensitivelyIgnoringRecommended() {
        var e = entry(List.of("staging (Recommended)", "prod"), false);
        assertThat(ClarifyTextCoercer.coerce(e, "Staging")).isEqualTo("staging (Recommended)");
        assertThat(ClarifyTextCoercer.coerce(e, "PROD")).isEqualTo("prod");
    }

    @Test
    void freeProseOnChoicePromptIsRejected() {
        var e = entry(List.of("a", "b"), false);
        assertThat(ClarifyTextCoercer.coerce(e, "I think we should use staging first")).isNull();
    }

    @Test
    void openEndedAcceptsAnyText() {
        var e = entry(List.of(), false);
        assertThat(ClarifyTextCoercer.coerce(e, "anything goes")).isEqualTo("anything goes");
    }

    @Test
    void explicitOtherArmsCustomTextForChoicePrompt() {
        ClarifyGatewayStore store = new ClarifyGatewayStore();
        var entry = store.register("s", "Q", List.of("dev", "prod"), false);

        assertThat(ClarifyTextCoercer.coerce(entry, "a bespoke environment")).isNull();
        assertThat(store.armCustomResponse("s", entry.clarifyId())).isTrue();

        var armed = store.pendingForSession("s");
        assertThat(armed.acceptsCustomResponse()).isTrue();
        assertThat(ClarifyTextCoercer.coerce(armed, "a bespoke environment"))
            .isEqualTo("a bespoke environment");
    }

    @Test
    void customResponseCannotBeArmedByTheWrongSession() {
        ClarifyGatewayStore store = new ClarifyGatewayStore();
        var entry = store.register("s", "Q", List.of("dev", "prod"), false);

        assertThat(store.armCustomResponse("other-session", entry.clarifyId())).isFalse();
        assertThat(store.pendingForSession("s").acceptsCustomResponse()).isFalse();
    }

    @Test
    void multiSelectNumericListReturnsJsonArray() {
        var e = entry(List.of("a", "b", "c"), true);
        String coerced = ClarifyTextCoercer.coerce(e, "1,3");
        assertThat(coerced).isEqualTo("[\"a\",\"c\"]");
    }

    @Test
    void multiSelectSpaceSeparatedNumbers() {
        var e = entry(List.of("a", "b", "c"), true);
        assertThat(ClarifyTextCoercer.coerce(e, "1 3")).isEqualTo("[\"a\",\"c\"]");
    }

    @Test
    void multiSelectLabelList() {
        var e = entry(List.of("staging (Recommended)", "prod"), true);
        String coerced = ClarifyTextCoercer.coerce(e, "staging, prod");
        assertThat(coerced).isEqualTo("[\"staging (Recommended)\",\"prod\"]");
    }

    @Test
    void multiSelectOneBadTokenRejectsWholeReply() {
        var e = entry(List.of("a", "b"), true);
        assertThat(ClarifyTextCoercer.coerce(e, "1, banana split with extra")).isNull();
    }

    @Test
    void cleanAnswerStripsRecommendedLabel() {
        assertThat(ClarifyTool.cleanAnswer("staging (Recommended)", false)).isEqualTo("staging");
        assertThat(ClarifyTool.cleanAnswer("[\"staging (Recommended)\",\"prod\"]", true))
            .isEqualTo("[\"staging\",\"prod\"]");
    }
}
