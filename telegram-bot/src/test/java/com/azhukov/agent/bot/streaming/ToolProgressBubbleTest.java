package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.client.TelegramClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hermes parity (gateway/run.py progress drain, display.tool_progress=all +
 * tool_progress_grouping=accumulate): ONE accumulating bubble per turn —
 * sendMessage for the first tool line, editMessageText for the rest
 * (1.5s throttle), consecutive duplicate collapse "(×N)", overflow roll.
 */
class ToolProgressBubbleTest {

    private static final long CHAT = 1L;

    private TelegramClient mockClient(List<Long> sentIds, List<String> editedTexts) {
        TelegramClient client = mock(TelegramClient.class);
        long[] nextId = {100};
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> Optional.of(nextId[0]++));
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                editedTexts.add(inv.getArgument(2));
                return true;
            });
        return client;
    }

    @Test
    void firstToolSendsMessageSecondToolEditsSameBubble() {
        List<Long> sent = new ArrayList<>();
        List<String> edited = new ArrayList<>();
        TelegramClient client = mockClient(sent, edited);
        long[] now = {System.currentTimeMillis()};
        ToolProgressBubble bubble = new ToolProgressBubble(client, true, () -> now[0]);

        now[0] += 2000; // past the 1.5s edit throttle
        bubble.appendLine(CHAT, "🔎 session_search...");
        now[0] += 2000;
        bubble.appendLine(CHAT, "🌐 web_search: parity");

        // Exactly ONE message for the whole turn; second line edited the same bubble
        verify(client, times(1)).sendMessage(eq(CHAT), anyString(), any(), any(), any(), eq(true));
        verify(client, times(1)).editMessageText(eq(CHAT), anyLong(), anyString(), any(), anyBoolean());
        assertThat(edited).hasSize(1);
        assertThat(edited.get(0)).contains("session_search").contains("web_search");
    }

    @Test
    void consecutiveDuplicateLinesCollapseWithCounter() {
        List<String> edited = new ArrayList<>();
        TelegramClient client = mockClient(new ArrayList<>(), edited);
        long[] now = {System.currentTimeMillis()};
        ToolProgressBubble bubble = new ToolProgressBubble(client, true, () -> now[0]);

        now[0] += 2000;
        bubble.appendLine(CHAT, "⏳ terminal: ls");
        now[0] += 2000;
        bubble.appendLine(CHAT, "⏳ terminal: ls");
        now[0] += 2000;
        bubble.appendLine(CHAT, "⏳ terminal: ls");

        // 3 identical lines → one line with (×3), not 3 separate
        assertThat(edited).hasSizeGreaterThanOrEqualTo(1);
        assertThat(edited.get(edited.size() - 1)).contains("(×3)");
        assertThat(edited.get(edited.size() - 1)).doesNotContain("\n");
    }

    @Test
    void closeBubbleMakesNextToolOpenFreshBubble() {
        List<Long> sent = new ArrayList<>();
        List<String> edited = new ArrayList<>();
        TelegramClient client = mockClient(sent, edited);
        long[] now = {System.currentTimeMillis()};
        ToolProgressBubble bubble = new ToolProgressBubble(client, true, () -> now[0]);

        now[0] += 2000;
        bubble.appendLine(CHAT, "🔎 one");
        bubble.closeBubble(); // content segment arrived
        now[0] += 2000;
        bubble.appendLine(CHAT, "🌐 two");

        // Two bubbles = two sendMessages (Hermes __reset__ semantics)
        verify(client, times(2)).sendMessage(eq(CHAT), anyString(), any(), any(), any(), eq(true));
    }

    @Test
    void overflowRollsBubbleAndKeepsTailLines() {
        List<Long> sent = new ArrayList<>();
        List<String> edited = new ArrayList<>();
        TelegramClient client = mockClient(sent, edited);
        long[] now = {System.currentTimeMillis()};
        ToolProgressBubble bubble = new ToolProgressBubble(client, true, () -> now[0]);

        for (int i = 1; i <= 35; i++) {
            now[0] += 2000;
            bubble.appendLine(CHAT, "🔧 tool-" + i);
        }

        // 35 lines > MAX_LINES(30) → at least one roll → second sendMessage
        verify(client, times(2)).sendMessage(eq(CHAT), anyString(), any(), any(), any(), eq(true));
        // The rolled bubble carries only the last 3 lines
        assertThat(edited.get(edited.size() - 1)).contains("tool-35");
    }
}
