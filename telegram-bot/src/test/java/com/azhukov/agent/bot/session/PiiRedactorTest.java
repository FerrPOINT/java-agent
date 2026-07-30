package com.azhukov.agent.bot.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    @Test
    void hashIdReturns12CharHex() {
        String hash = PiiRedactor.hashId("12345");
        assertThat(hash).hasSize(12);
        assertThat(hash).matches("[0-9a-f]{12}");
    }

    @Test
    void hashIdIsDeterministic() {
        String hash1 = PiiRedactor.hashId("12345");
        String hash2 = PiiRedactor.hashId("12345");
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void hashIdDiffersForDifferentInputs() {
        String hash1 = PiiRedactor.hashId("12345");
        String hash2 = PiiRedactor.hashId("67890");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void hashIdEmptyStringReturnsEmpty() {
        assertThat(PiiRedactor.hashId("")).isEmpty();
    }

    @Test
    void hashIdNullReturnsEmpty() {
        assertThat(PiiRedactor.hashId(null)).isEmpty();
    }

    @Test
    void hashUserIdReturnsPrefixedHash() {
        String result = PiiRedactor.hashUserId("12345");
        assertThat(result).startsWith("user_");
        assertThat(result).hasSize(5 + 12); // "user_" + 12 chars
    }

    @Test
    void hashUserIdNullReturnsNull() {
        assertThat(PiiRedactor.hashUserId(null)).isNull();
        assertThat(PiiRedactor.hashUserId("")).isNull();
    }

    @Test
    void hashChatIdPreservesPlatformPrefix() {
        String result = PiiRedactor.hashChatId("telegram:12345");
        assertThat(result).startsWith("telegram:");
        assertThat(result).isNotEqualTo("telegram:12345"); // ID part should be hashed
        assertThat(result.substring("telegram:".length())).hasSize(12);
    }

    @Test
    void hashChatIdWithoutPrefix() {
        String result = PiiRedactor.hashChatId("12345");
        assertThat(result).hasSize(12);
        assertThat(result).matches("[0-9a-f]{12}");
    }

    @Test
    void hashChatIdNullReturnsNull() {
        assertThat(PiiRedactor.hashChatId(null)).isNull();
        assertThat(PiiRedactor.hashChatId("")).isNull();
    }

    @Test
    void buildRedactedContextPromptContainsNoRawIds() {
        String prompt = PiiRedactor.buildRedactedContextPrompt(
            "telegram", "123456789", "-1009876543", "testuser", "dm", null);

        assertThat(prompt).contains("## Current Session Context");
        assertThat(prompt).contains("Telegram");
        assertThat(prompt).contains("DM with testuser");
        // Should NOT contain raw IDs
        assertThat(prompt).doesNotContain("123456789");
        assertThat(prompt).doesNotContain("-1009876543");
    }

    @Test
    void buildRedactedContextPromptWithUsernameShowsUsername() {
        String prompt = PiiRedactor.buildRedactedContextPrompt(
            "telegram", "123456789", "-1009876543", "alice", "dm", null);

        // When username is available, it's used instead of hashed ID
        assertThat(prompt).contains("**User:** alice");
        assertThat(prompt).doesNotContain("123456789");
    }

    @Test
    void buildRedactedContextPromptWithoutUsernameShowsHashedId() {
        String prompt = PiiRedactor.buildRedactedContextPrompt(
            "telegram", "123456789", "-1009876543", null, "dm", null);

        // When no username, hashed user ID is shown
        assertThat(prompt).contains("**User ID:** user_");
        assertThat(prompt).doesNotContain("123456789");
    }

    @Test
    void buildRedactedContextPromptGroupChatShowsChatName() {
        String prompt = PiiRedactor.buildRedactedContextPrompt(
            "telegram", "123456789", "-1009876543", "testuser", "group", "My Group Chat");

        assertThat(prompt).contains("group: My Group Chat");
        assertThat(prompt).doesNotContain("-1009876543");
    }

    @Test
    void buildRedactedContextPromptGroupChatWithoutNameShowsHashedChatId() {
        String prompt = PiiRedactor.buildRedactedContextPrompt(
            "telegram", "123456789", "-1009876543", "testuser", "group", null);

        assertThat(prompt).contains("group:");
        assertThat(prompt).doesNotContain("-1009876543");
    }
}