package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThinkBlockProcessorTest {

    private static final String TC_OPEN = "\u003Ctool_call\u003E";
    private static final String TC_CLOSE = "\u003C/tool_call\u003E";
    private static final String TCS_OPEN = "\u003Ctool_calls\u003E";
    private static final String TCS_CLOSE = "\u003C/tool_calls\u003E";
    private static final String FC_OPEN = "\u003Cfunction_call\u003E";
    private static final String FC_CLOSE = "\u003C/function_call\u003E";
    private static final String FCS_OPEN = "\u003Cfunction_calls\u003E";
    private static final String FCS_CLOSE = "\u003C/function_calls\u003E";
    private static final String TR_OPEN = "\u003Ctool_result\u003E";
    private static final String TR_CLOSE = "\u003C/tool_result\u003E";
    private static final String FN_OPEN = "\u003Cfunction name=\"get_weather\"\u003E";
    private static final String FN_CLOSE = "\u003C/function\u003E";
    private static final String THINK = "\u003Cthinking\u003Ereasoning\u003C/thinking\u003E";

    @Test
    void stripsToolCallXmlBlock() {
        String input = "Here is my answer.\n" + TC_OPEN + "\nstuff\n" + TC_CLOSE + "\nDone.";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).doesNotContain("tool_call");
        assertThat(result).contains("Here is my answer.").contains("Done.");
    }

    @Test
    void stripsFunctionCallsXmlBlock() {
        String inner = FC_OPEN + "do stuff" + FC_CLOSE;
        String input = "Result.\n" + FCS_OPEN + "\n" + inner + "\n" + FCS_CLOSE + "\nEnd.";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).doesNotContain("function_call");
        assertThat(result).contains("Result.").contains("End.");
    }

    @Test
    void stripsNamedFunctionBlock() {
        String input = "Let me check.\n" + FN_OPEN + "\nstuff\n" + FN_CLOSE + "\nDone.";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).doesNotContain("get_weather");
        assertThat(result).contains("Let me check.").contains("Done.");
    }

    @Test
    void preservesProseFunctionMention() {
        String tag = "\u003Cfunction\u003E";
        String input = "In JavaScript, use " + tag + " declarations. That is it.";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        // Should NOT strip - no name= attribute, not at boundary
        assertThat(result).contains(tag);
    }

    @Test
    void stripsToolResultXmlBlock() {
        String input = "Thinking...\n" + TR_OPEN + "\n42\n" + TR_CLOSE + "\nFinal.";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).doesNotContain("tool_result");
        assertThat(result).contains("Final.");
    }

    @Test
    void stripsStrayToolCallClosers() {
        String input = "Some text" + TC_CLOSE + "more text";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).doesNotContain(TC_CLOSE);
        assertThat(result).contains("Some text").contains("more text");
    }

    @Test
    void stripsThinkBlockAndToolCallTogether() {
        String input = THINK + TC_OPEN + "stuff" + TC_CLOSE + "Answer: 42";
        String result = ThinkBlockProcessor.stripThinkBlocksFromString(input);
        assertThat(result).doesNotContain("thinking").doesNotContain("tool_call");
        assertThat(result).contains("Answer: 42");
    }
}
