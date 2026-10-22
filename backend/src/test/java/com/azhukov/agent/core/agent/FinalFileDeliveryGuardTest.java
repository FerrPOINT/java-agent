package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.tool.FileMutationTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FinalFileDeliveryGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void telegramFileMutationWithoutFinalPathGetsOneCorrectionPass() {
        FileMutationTracker tracker = mutatedFileTracker();
        Session telegram = Session.create("user", "provider", "model").withMetadata("platform", "telegram");

        assertThat(FinalFileDeliveryGuard.shouldNudge(telegram, "Created: result.zip", tracker)).isTrue();
        assertThat(FinalFileDeliveryGuard.shouldNudge(telegram, "Created: result.zip", tracker)).isFalse();
    }

    @Test
    void existingAbsoluteFinalPathDoesNotGetCorrectionPass() throws Exception {
        Path document = Files.writeString(tempDir.resolve("result.zip"), "archive");
        FileMutationTracker tracker = mutatedFileTracker();
        Session telegram = Session.create("user", "provider", "model").withMetadata("platform", "telegram");

        assertThat(FinalFileDeliveryGuard.shouldNudge(telegram,
            "Ready\nMEDIA:" + document, tracker)).isFalse();
    }

    @Test
    void missingOrCodeExamplePathDoesNotSuppressCorrectionPass() {
        FileMutationTracker tracker = mutatedFileTracker();
        Session telegram = Session.create("user", "provider", "model").withMetadata("platform", "telegram");

        assertThat(FinalFileDeliveryGuard.shouldNudge(telegram,
            "Example: `MEDIA:/tmp/missing.zip`", tracker)).isTrue();
    }

    @Test
    void nonTelegramTurnDoesNotGetDeliveryCorrectionPass() {
        FileMutationTracker tracker = mutatedFileTracker();
        Session local = Session.create("user", "provider", "model");

        assertThat(FinalFileDeliveryGuard.shouldNudge(local, "Created: result.zip", tracker)).isFalse();
    }

    private FileMutationTracker mutatedFileTracker() {
        FileMutationTracker tracker = new FileMutationTracker();
        tracker.recordMutation("write_file", "{\"path\":\"result.txt\"}",
            "{\"resolved_path\":\"result.txt\"}", true);
        return tracker;
    }
}
