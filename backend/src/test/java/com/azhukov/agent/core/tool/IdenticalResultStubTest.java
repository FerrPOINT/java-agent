package com.azhukov.agent.core.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** P-07: byte-identical large results become reference stubs from the 2nd repeat. */
class IdenticalResultStubTest {

    private final ToolLoopGuardrail guardrail = new ToolLoopGuardrail();
    private final String bigResult = "x".repeat(600);
    private final String args = "{\"path\":\"/tmp/same\"}";

    @Test
    void firstOccurrenceKeepsFullPayload() {
        String stub = guardrail.resultReferenceStub("read_file", args, bigResult, false);
        assertThat(stub).isNull();
    }

    @Test
    void secondIdenticalOccurrenceIsStubbed() {
        guardrail.resultReferenceStub("read_file", args, bigResult, false);
        String stub = guardrail.resultReferenceStub("read_file", args, bigResult, false);
        assertThat(stub).isNotNull();
        assertThat(stub).contains("[Duplicate result");
        assertThat(stub).contains("read_file");
        assertThat(stub.length()).isLessThan(300);
    }

    @Test
    void thirdOccurrenceAlsoStubbed() {
        guardrail.resultReferenceStub("read_file", args, bigResult, false);
        guardrail.resultReferenceStub("read_file", args, bigResult, false);
        String stub = guardrail.resultReferenceStub("read_file", args, bigResult, false);
        assertThat(stub).isNotNull();
    }

    @Test
    void changedResultResetsStreak() {
        guardrail.resultReferenceStub("read_file", args, bigResult, false);
        String changed = "y".repeat(600);
        assertThat(guardrail.resultReferenceStub("read_file", args, changed, false)).isNull();
        // changed result is now the baseline — identical to IT gets stubbed next
        assertThat(guardrail.resultReferenceStub("read_file", args, changed, false)).isNotNull();
    }

    @Test
    void failedResultsAreNeverStubbed() {
        guardrail.resultReferenceStub("terminal", args, bigResult, false);
        assertThat(guardrail.resultReferenceStub("terminal", args, bigResult, true)).isNull();
    }

    @Test
    void shortResultsAreNeverStubbed() {
        String small = "x".repeat(100);
        guardrail.resultReferenceStub("read_file", args, small, false);
        assertThat(guardrail.resultReferenceStub("read_file", args, small, false)).isNull();
    }

    @Test
    void differentArgsAreIndependentStreaks() {
        guardrail.resultReferenceStub("read_file", args, bigResult, false);
        String otherArgs = "{\"path\":\"/tmp/other\"}";
        assertThat(guardrail.resultReferenceStub("read_file", otherArgs, bigResult, false)).isNull();
    }

    @Test
    void resetForTurnClearsStreaks() {
        guardrail.resultReferenceStub("read_file", args, bigResult, false);
        guardrail.resetForTurn();
        assertThat(guardrail.resultReferenceStub("read_file", args, bigResult, false)).isNull();
    }

    @Test
    void longArgsArePreviewedInStub() {
        String longArgs = "{\"query\":\"" + "q".repeat(300) + "\"}";
        guardrail.resultReferenceStub("web_search", longArgs, bigResult, false);
        String stub = guardrail.resultReferenceStub("web_search", longArgs, bigResult, false);
        assertThat(stub).isNotNull();
        // preview capped at 120 chars + ellipsis
        int argsLen = stub.indexOf("Args: ");
        assertThat(stub.length() - argsLen - 6).isLessThanOrEqualTo(122);
    }
}
