package com.azhukov.agent.bot.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryRouterTest {

    @TempDir
    Path tempDir;
    private DeliveryRouter router;

    @BeforeEach
    void setUp() {
        router = new DeliveryRouter(tempDir);
    }

    @Test
    void parseTarget_origin_withOrigin() {
        DeliveryRouter.DeliveryTarget origin = new DeliveryRouter.DeliveryTarget(
            DeliveryRouter.DeliveryTarget.Platform.TELEGRAM, "123456", "789", false, false);
        DeliveryRouter.DeliveryTarget result = DeliveryRouter.DeliveryTarget.parse("origin", origin);
        assertThat(result.isOrigin).isTrue();
        assertThat(result.platform).isEqualTo(DeliveryRouter.DeliveryTarget.Platform.TELEGRAM);
        assertThat(result.chatId).isEqualTo("123456");
        assertThat(result.threadId).isEqualTo("789");
    }

    @Test
    void parseTarget_origin_withoutOrigin_defaultsToLocal() {
        DeliveryRouter.DeliveryTarget result = DeliveryRouter.DeliveryTarget.parse("origin", null);
        assertThat(result.isOrigin).isTrue();
        assertThat(result.platform).isEqualTo(DeliveryRouter.DeliveryTarget.Platform.LOCAL);
    }

    @Test
    void parseTarget_local() {
        DeliveryRouter.DeliveryTarget result = DeliveryRouter.DeliveryTarget.parse("local", null);
        assertThat(result.platform).isEqualTo(DeliveryRouter.DeliveryTarget.Platform.LOCAL);
    }

    @Test
    void parseTarget_telegramWithChatId() {
        DeliveryRouter.DeliveryTarget result = DeliveryRouter.DeliveryTarget.parse("telegram:123456", null);
        assertThat(result.platform).isEqualTo(DeliveryRouter.DeliveryTarget.Platform.TELEGRAM);
        assertThat(result.chatId).isEqualTo("123456");
        assertThat(result.threadId).isNull();
        assertThat(result.isExplicit).isTrue();
    }

    @Test
    void parseTarget_telegramWithChatIdAndThread() {
        DeliveryRouter.DeliveryTarget result = DeliveryRouter.DeliveryTarget.parse("telegram:123456:789", null);
        assertThat(result.platform).isEqualTo(DeliveryRouter.DeliveryTarget.Platform.TELEGRAM);
        assertThat(result.chatId).isEqualTo("123456");
        assertThat(result.threadId).isEqualTo("789");
    }

    @Test
    void parseTarget_telegramHomeChannel() {
        DeliveryRouter.DeliveryTarget result = DeliveryRouter.DeliveryTarget.parse("telegram", null);
        assertThat(result.platform).isEqualTo(DeliveryRouter.DeliveryTarget.Platform.TELEGRAM);
        assertThat(result.chatId).isNull();
    }

    @Test
    void parseTarget_unknownPlatform_defaultsToLocal() {
        DeliveryRouter.DeliveryTarget result = DeliveryRouter.DeliveryTarget.parse("unknown:123", null);
        assertThat(result.platform).isEqualTo(DeliveryRouter.DeliveryTarget.Platform.LOCAL);
    }

    @Test
    void to_string_origin() {
        DeliveryRouter.DeliveryTarget target = new DeliveryRouter.DeliveryTarget(
            DeliveryRouter.DeliveryTarget.Platform.TELEGRAM, "123", "456", true, false);
        assertThat(target.to_string()).isEqualTo("origin");
    }

    @Test
    void to_string_local() {
        DeliveryRouter.DeliveryTarget target = new DeliveryRouter.DeliveryTarget(
            DeliveryRouter.DeliveryTarget.Platform.LOCAL, null, null, false, false);
        assertThat(target.to_string()).isEqualTo("local");
    }

    @Test
    void to_string_telegramWithChatIdAndThread() {
        DeliveryRouter.DeliveryTarget target = new DeliveryRouter.DeliveryTarget(
            DeliveryRouter.DeliveryTarget.Platform.TELEGRAM, "123", "456", false, true);
        assertThat(target.to_string()).isEqualTo("telegram:123:456");
    }

    @Test
    void deliverLocal_savesFile() {
        DeliveryRouter.DeliveryResult result = router.deliverLocal("test content", "my-job", Map.of("key", "value"));
        assertThat(result.success()).isTrue();
        assertThat(result.result()).contains(tempDir.toString());
    }

    @Test
    void deliverToPlatform_success() {
        DeliveryRouter.DeliveryTarget target = new DeliveryRouter.DeliveryTarget(
            DeliveryRouter.DeliveryTarget.Platform.TELEGRAM, "123456", null, false, true);
        DeliveryRouter.DeliveryResult result = router.deliverToPlatform(target, "Hello", null, (chatId, threadId, content) -> true);
        assertThat(result.success()).isTrue();
    }

    @Test
    void deliverToPlatform_noChatId_fails() {
        DeliveryRouter.DeliveryTarget target = new DeliveryRouter.DeliveryTarget(
            DeliveryRouter.DeliveryTarget.Platform.TELEGRAM, null, null, false, false);
        DeliveryRouter.DeliveryResult result = router.deliverToPlatform(target, "Hello", null, (chatId, threadId, content) -> true);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("No chat ID");
    }

    @Test
    void deliverToPlatform_truncatesLongOutput() {
        DeliveryRouter.DeliveryTarget target = new DeliveryRouter.DeliveryTarget(
            DeliveryRouter.DeliveryTarget.Platform.TELEGRAM, "123456", null, false, true);
        String longContent = "a".repeat(DeliveryRouter.MAX_PLATFORM_OUTPUT + 1000);
        DeliveryRouter.DeliveryResult result = router.deliverToPlatform(target, longContent, Map.of("job_id", "test-job"), (chatId, threadId, content) -> {
            // Verify the content was truncated
            return content.length() < longContent.length();
        });
        assertThat(result.success()).isTrue();
    }

    @Test
    void deliverToPlatform_threadNotFound_retriesWithoutThread() {
        DeliveryRouter.DeliveryTarget target = new DeliveryRouter.DeliveryTarget(
            DeliveryRouter.DeliveryTarget.Platform.TELEGRAM, "123456", "789", false, true);
        AtomicInteger callCount = new AtomicInteger(0);
        DeliveryRouter.DeliveryResult result = router.deliverToPlatform(target, "Hello", null, (chatId, threadId, content) -> {
            callCount.incrementAndGet();
            // First call (with thread) fails, second (without thread) succeeds
            return callCount.get() == 2;
        });
        assertThat(result.success()).isTrue();
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void deliver_multipleTargets_allProcessed() {
        List<DeliveryRouter.DeliveryTarget> targets = List.of(
            DeliveryRouter.DeliveryTarget.parse("local", null),
            DeliveryRouter.DeliveryTarget.parse("telegram:123", null)
        );
        Map<String, DeliveryRouter.DeliveryResult> results = router.deliver(
            "content", targets, "job1", Map.of(),
            (chatId, threadId, content) -> true
        );
        assertThat(results).hasSize(2);
        assertThat(results.get("local").success()).isTrue();
        assertThat(results.get("telegram:123").success()).isTrue();
    }

    @Test
    void saveFullOutput_writesFile() {
        java.nio.file.Path path = router.saveFullOutput("full content here", "job-123");
        assertThat(java.nio.file.Files.exists(path)).isTrue();
    }
}