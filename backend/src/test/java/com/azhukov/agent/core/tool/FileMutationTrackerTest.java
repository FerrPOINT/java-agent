package com.azhukov.agent.core.tool;

import org.junit.jupiter.api.Test;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FileMutationTrackerTest {

    @Test
    void extractsWriteFilePath() {
        Set<String> paths = FileMutationTracker.extractMutationTargets("write_file",
            "{\"path\":\"/tmp/test.txt\",\"content\":\"hello\"}");
        assertThat(paths).contains("/tmp/test.txt");
    }

    @Test
    void extractsPatchReplacePath() {
        Set<String> paths = FileMutationTracker.extractMutationTargets("patch",
            "{\"path\":\"src/Main.java\",\"old_string\":\"a\",\"new_string\":\"b\",\"mode\":\"replace\"}");
        assertThat(paths).contains("src/Main.java");
    }

    @Test
    void extractsV4APatchPaths() {
        String args = "{\"mode\":\"patch\",\"patch\":\""
            + "*** Update File: src/Main.java\\n*** Add File: src/New.java\\n*** Delete File: src/Old.java\"}";
        Set<String> paths = FileMutationTracker.extractMutationTargets("patch", args);
        assertThat(paths).contains("src/Main.java", "src/New.java", "src/Old.java");
    }

    @Test
    void extractsV4AMovePaths() {
        String args = "{\"mode\":\"patch\",\"patch\":\"*** Move File: src/old.java -> src/new.java\"}";
        Set<String> paths = FileMutationTracker.extractMutationTargets("patch", args);
        assertThat(paths).contains("src/old.java", "src/new.java");
    }

    @Test
    void ignoresNonFileTools() {
        Set<String> paths = FileMutationTracker.extractMutationTargets("read_file",
            "{\"path\":\"/tmp/test.txt\"}");
        assertThat(paths).isEmpty();
    }

    @Test
    void recordMutationTracksPaths() {
        FileMutationTracker tracker = new FileMutationTracker();
        tracker.recordMutation("write_file", "{\"path\":\"src/Main.java\",\"content\":\"x\"}",
            "{\"success\":true}", true);
        assertThat(tracker.getTurnMutationPaths()).contains("src/Main.java");
    }

    @Test
    void recordMutationExtractsResolvedPath() {
        FileMutationTracker tracker = new FileMutationTracker();
        tracker.recordMutation("write_file", "{\"path\":\"test.txt\",\"content\":\"x\"}",
            "{\"success\":true,\"resolved_path\":\"/absolute/path/test.txt\"}", true);
        assertThat(tracker.getTurnMutationPaths()).contains("/absolute/path/test.txt");
    }

    @Test
    void recordMutationIgnoresFailedCalls() {
        FileMutationTracker tracker = new FileMutationTracker();
        tracker.recordMutation("write_file", "{\"path\":\"test.txt\",\"content\":\"x\"}",
            "{\"success\":false}", false);
        assertThat(tracker.getTurnMutationPaths()).isEmpty();
    }

    @Test
    void resetForTurnClearsState() {
        FileMutationTracker tracker = new FileMutationTracker();
        tracker.recordMutation("write_file", "{\"path\":\"a.java\"}", "{}", true);
        tracker.incrementVerificationStopNudges();
        assertThat(tracker.getTurnMutationPaths()).isNotEmpty();
        assertThat(tracker.getVerificationStopNudges()).isEqualTo(1);

        tracker.resetForTurn();
        assertThat(tracker.getTurnMutationPaths()).isEmpty();
        assertThat(tracker.getVerificationStopNudges()).isEqualTo(0);
    }

    @Test
    void extractLandedPathsFromFilesModified() {
        String result = "{\"files_modified\":[\"a.java\",\"b.java\"]}";
        Set<String> landed = FileMutationTracker.extractLandedPaths(result, Set.of("fallback.java"));
        assertThat(landed).contains("a.java", "b.java");
    }

    @Test
    void extractLandedPathsFallbackToTargets() {
        String result = "not json";
        Set<String> landed = FileMutationTracker.extractLandedPaths(result, Set.of("fallback.java"));
        assertThat(landed).contains("fallback.java");
    }
}