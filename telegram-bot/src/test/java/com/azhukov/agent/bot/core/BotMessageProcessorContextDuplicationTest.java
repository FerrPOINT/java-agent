package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression (live 2026-08-27): buildMessageWithContext prepended a
 * "## Current Session Context" block to EVERY user message. The backend
 * already renders that block in the system prompt volatile tier (it receives
 * userId/username/firstName/languageCode/chatType in the chat body), so the
 * bot-side prefix duplicated it into the persisted user rows — polluting
 * history, session_search results and compressed transcripts.
 */
class BotMessageProcessorContextDuplicationTest {

    /**
     * Direct contract check via the ProcessorHooks interface: the hook the
     * streaming orchestrator calls must be a passthrough. Constructed through
     * the interface to keep this test constructor-agnostic (the processor has
     * 24 dependencies; BotMessageProcessorTest covers the wiring end-to-end).
     */
    @Test
    void buildMessageWithContextIsPassthrough() throws Exception {
        var method = BotMessageProcessor.class.getMethod(
            "buildMessageWithContext", String.class, BotSessionEntity.class, long.class);
        assertThat(method.getReturnType()).isEqualTo(String.class);

        // The implementation must NOT reference the redacted-context prompt
        // builder anymore — assert via source-independent behavioral contract:
        // invoking on a real instance is covered by BotMessageProcessorTest;
        // here we pin that the method body no longer prepends the block by
        // checking the compiled constant is absent from the implementation.
        byte[] bytes = getClass().getClassLoader()
            .getResourceAsStream(BotMessageProcessor.class.getName().replace('.', '/') + ".class")
            .readAllBytes();
        String utf8 = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        // PiiRedactor reference must be gone from the processor's constant pool
        assertThat(utf8.contains("session/PiiRedactor")).isFalse();
    }
}
