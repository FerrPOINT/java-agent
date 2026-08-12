package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void emptyList_returnsOne() {
        assertThat(estimator.estimateTokens(List.of())).isEqualTo(1);
    }

    @Test
    void singleMessage_estimatesCharsDividedBy4() {
        // 8 chars → 8/4 + 1 = 3
        Message msg = Message.user("abcdefgh");
        assertThat(estimator.estimateTokens(List.of(msg))).isEqualTo(3);
    }

    @Test
    void nullContentContributesZero() {
        Message msg = Message.assistantToolCalls(List.of(
            new ToolCall("id-1", "weather", "{}")), 1);
        // content is null, toolCall name="weather" (7) + arguments="{}" (2) = 9 → 9/4+1 = 3
        assertThat(estimator.estimateTokens(List.of(msg))).isEqualTo(3);
    }

    @Test
    void multipleMessages_sumAllContent() {
        Message m1 = Message.user("abcd");      // 4 chars
        Message m2 = Message.assistant("efgh", 1);  // 4 chars
        // total 8 chars → 8/4 + 1 = 3
        assertThat(estimator.estimateTokens(List.of(m1, m2))).isEqualTo(3);
    }

    @Test
    void toolCallArgumentsAndNameAreCounted() {
        ToolCall tc = new ToolCall("call-1", "search", "{\"query\":\"hello world\"}");
        Message msg = Message.assistantToolCalls(List.of(tc), 1);
        // name="search" (6) + arguments={"query":"hello world"} (24) = 30 → 30/4+1 = 8
        assertThat(estimator.estimateTokens(List.of(msg))).isEqualTo(8);
    }

    @Test
    void longContent_estimatesCorrectly() {
        String longText = "a".repeat(400);
        Message msg = Message.user(longText);
        // 400/4 + 1 = 101
        assertThat(estimator.estimateTokens(List.of(msg))).isEqualTo(101);
    }
}