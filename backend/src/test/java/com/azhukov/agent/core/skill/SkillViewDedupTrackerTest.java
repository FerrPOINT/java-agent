package com.azhukov.agent.core.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermes parity (tools/skills_tool.py:2030-2135): skill_view repeat-view dedup.
 */
class SkillViewDedupTrackerTest {

    private final SkillViewDedupTracker tracker = new SkillViewDedupTracker();
    private final String session = UUID.randomUUID().toString();

    private Path skillFile(String content) throws Exception {
        Path dir = Files.createTempDirectory("skill");
        Path md = dir.resolve("SKILL.md");
        Files.writeString(md, content);
        return md;
    }

    @Test
    void repeatViewOfUnchangedFileReturnsStub() throws Exception {
        Path md = skillFile("# content");
        var attrs = Files.readAttributes(md, java.nio.file.attribute.BasicFileAttributes.class);
        tracker.record(session, "my-skill", null, md.toString(),
            attrs.lastModifiedTime().toMillis(), attrs.size());

        var hit = tracker.check(session, "my-skill", null);

        assertThat(hit).isNotNull();
        assertThat(hit.message()).contains("unchanged since it was loaded earlier");
    }

    @Test
    void categoryPathAndBareNameCoalesce() throws Exception {
        Path md = skillFile("# content");
        var attrs = Files.readAttributes(md, java.nio.file.attribute.BasicFileAttributes.class);
        tracker.record(session, "my-skill", null, md.toString(),
            attrs.lastModifiedTime().toMillis(), attrs.size());

        // Hermes: 'category/skill' and bare-name views coalesce
        assertThat(tracker.check(session, "mlops/my-skill", null)).isNotNull();
        // 'plugin:skill' qualified form resolves to the bare name too
        assertThat(tracker.check(session, "plugin:my-skill", null)).isNotNull();
    }

    @Test
    void modifiedFileEvictsFingerprint() throws Exception {
        Path md = skillFile("# v1");
        var attrs = Files.readAttributes(md, java.nio.file.attribute.BasicFileAttributes.class);
        tracker.record(session, "my-skill", null, md.toString(),
            attrs.lastModifiedTime().toMillis(), attrs.size());

        // Change content on disk (ensure mtime/size differ)
        Thread.sleep(5);
        Files.writeString(md, "# v2 with more text");
        // mtime granularity safety: re-read and compare against recorded fp
        var hit = tracker.check(session, "my-skill", null);
        if (hit != null) {
            // mtime may be too coarse on some FS; size check must still catch it
            assertThat(hit).as("modified file must not hit dedup (size changed)").isNull();
        }
    }

    @Test
    void differentSessionNeverHits() throws Exception {
        Path md = skillFile("# content");
        var attrs = Files.readAttributes(md, java.nio.file.attribute.BasicFileAttributes.class);
        tracker.record(session, "my-skill", null, md.toString(),
            attrs.lastModifiedTime().toMillis(), attrs.size());

        assertThat(tracker.check(UUID.randomUUID().toString(), "my-skill", null)).isNull();
    }

    @Test
    void supportFilePathIsPartOfTheKey() throws Exception {
        Path dir = Files.createTempDirectory("skill");
        Path ref = dir.resolve("references");
        Files.createDirectories(ref);
        Path api = ref.resolve("api.md");
        Files.writeString(api, "docs");
        var attrs = Files.readAttributes(api, java.nio.file.attribute.BasicFileAttributes.class);
        tracker.record(session, "my-skill", "references/api.md", api.toString(),
            attrs.lastModifiedTime().toMillis(), attrs.size());

        assertThat(tracker.check(session, "my-skill", "references/api.md")).isNotNull();
        // Same skill, different file → no hit
        assertThat(tracker.check(session, "my-skill", null)).isNull();
    }

    @Test
    void compressionEventClearsSession() throws Exception {
        Path md = skillFile("# content");
        var attrs = Files.readAttributes(md, java.nio.file.attribute.BasicFileAttributes.class);
        tracker.record(session, "my-skill", null, md.toString(),
            attrs.lastModifiedTime().toMillis(), attrs.size());
        assertThat(tracker.check(session, "my-skill", null)).isNotNull();

        tracker.onContextCompressed(new ContextCompressedEvent(null));

        assertThat(tracker.check(session, "my-skill", null)).isNull();
    }
}
