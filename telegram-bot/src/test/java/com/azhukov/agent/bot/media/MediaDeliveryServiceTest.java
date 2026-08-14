package com.azhukov.agent.bot.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MediaDeliveryService}.
 */
class MediaDeliveryServiceTest {

    private final MediaDeliveryService service = new MediaDeliveryService();

    @Test
    void extractMediaTags_simpleMediaTag() {
        String text = "Here is your image:\nMEDIA:/tmp/test.png\nDone!";
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        assertEquals(1, result.media().size());
        assertEquals("/tmp/test.png", result.media().get(0).path());
        assertEquals(".png", result.media().get(0).extension());
        assertFalse(result.media().get(0).asVoice());
        assertFalse(result.media().get(0).asDocument());
        assertFalse(result.cleanedText().contains("MEDIA:"));
        assertTrue(result.cleanedText().contains("Done!"));
    }

    @Test
    void extractMediaTags_multipleMediaTags() {
        String text = "MEDIA:/tmp/a.png\nMEDIA:/tmp/b.jpg\nMEDIA:/tmp/c.pdf";
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        assertEquals(3, result.media().size());
        assertEquals("/tmp/a.png", result.media().get(0).path());
        assertEquals("/tmp/b.jpg", result.media().get(1).path());
        assertEquals("/tmp/c.pdf", result.media().get(2).path());
        assertFalse(result.cleanedText().contains("MEDIA:"));
    }

    @Test
    void extractMediaTags_audioAsVoiceDirective() {
        String text = "[[audio_as_voice]]\nMEDIA:/tmp/audio.mp3";
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        assertEquals(1, result.media().size());
        assertTrue(result.media().get(0).asVoice());
        assertFalse(result.cleanedText().contains("[[audio_as_voice]]"));
    }

    @Test
    void extractMediaTags_asDocumentDirective() {
        String text = "[[as_document]]\nMEDIA:/tmp/image.png";
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        assertEquals(1, result.media().size());
        assertTrue(result.media().get(0).asDocument());
        assertFalse(result.cleanedText().contains("[[as_document]]"));
    }

    @Test
    void extractMediaTags_quotedPath() {
        String text = "MEDIA:`/tmp/quoted file.png`";
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        assertEquals(1, result.media().size());
        assertEquals("/tmp/quoted file.png", result.media().get(0).path());
    }

    @Test
    void extractMediaTags_doubleQuotedPath() {
        String text = "MEDIA:\"/tmp/quoted.png\"";
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        assertEquals(1, result.media().size());
        assertEquals("/tmp/quoted.png", result.media().get(0).path());
    }

    @Test
    void extractMediaTags_homeRelativePath() {
        String text = "MEDIA:~/output/chart.png";
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        assertEquals(1, result.media().size());
        String home = System.getProperty("user.home");
        assertEquals(home + "/output/chart.png", result.media().get(0).path());
    }

    @Test
    void extractMediaTags_unknownExtensionNotExtracted() {
        String text = "MEDIA:/tmp/file.xyz";
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        // Unknown extension should not be extracted as a MEDIA: tag
        // (it may still be detected as a bare path if the file exists, but
        // it won't exist in this test so it should be empty)
        assertEquals(0, result.media().size());
    }

    @Test
    void extractMediaTags_mediaTagInCodeBlockNotExtracted() {
        String text = "```python\nMEDIA:/tmp/inside.png\n```\nMEDIA:/tmp/outside.png";
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        // Only the one outside the code block should be extracted
        assertEquals(1, result.media().size());
        assertEquals("/tmp/outside.png", result.media().get(0).path());
    }

    @Test
    void extractMediaTags_mediaTagInJsonNotExtracted() {
        String text = "{\"result\": \"MEDIA:/tmp/stale.png\"}\nMEDIA:/tmp/real.png";
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        // Only the real MEDIA: tag (outside JSON) should be extracted
        assertEquals(1, result.media().size());
        assertEquals("/tmp/real.png", result.media().get(0).path());
    }

    @Test
    void extractMediaTags_bareFilePath(@TempDir Path tempDir) throws Exception {
        // Create a real file
        Path imgFile = tempDir.resolve("test.png");
        Files.writeString(imgFile, "fake png data");

        String text = "Here is the file: " + imgFile.toString();
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        assertEquals(1, result.media().size());
        assertEquals(imgFile.toString(), result.media().get(0).path());
        assertFalse(result.cleanedText().contains(imgFile.toString()));
    }

    @Test
    void extractMediaTags_bareFilePathNonExistentNotExtracted() {
        String text = "See /tmp/nonexistent_file_12345.png for details";
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        // Non-existent file should not be extracted
        assertEquals(0, result.media().size());
    }

    @Test
    void extractMediaTags_deduplication() {
        String text = "MEDIA:/tmp/dup.png\nMEDIA:/tmp/dup.png";
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        assertEquals(1, result.media().size());
    }

    @Test
    void extractMediaTags_emptyText() {
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags("");
        assertEquals(0, result.media().size());
        assertEquals("", result.cleanedText());
    }

    @Test
    void extractMediaTags_nullText() {
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(null);
        assertEquals(0, result.media().size());
        assertEquals("", result.cleanedText());
    }

    @Test
    void extractMediaTags_noMediaTags() {
        String text = "Just a regular message with no media.";
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        assertEquals(0, result.media().size());
        assertEquals("Just a regular message with no media.", result.cleanedText());
    }

    @Test
    void extractMediaTags_collapsesBlankLines() {
        String text = "Line 1\n\n\n\nMEDIA:/tmp/test.png\n\n\nLine 2";
        MediaDeliveryService.ExtractionResult result = service.extractMediaTags(text);

        assertFalse(result.cleanedText().contains("\n\n\n"));
    }

    @Test
    void stripMediaTagsForDisplay_simple() {
        String text = "Here is the result:\nMEDIA:/tmp/test.png\nDone!";
        String cleaned = service.stripMediaTagsForDisplay(text);

        assertFalse(cleaned.contains("MEDIA:"));
        assertTrue(cleaned.contains("Done!"));
    }

    @Test
    void stripMediaTagsForDisplay_withDirectives() {
        String text = "[[audio_as_voice]]\nMEDIA:/tmp/audio.mp3\n[[as_document]]\nMEDIA:/tmp/img.png";
        String cleaned = service.stripMediaTagsForDisplay(text);

        assertFalse(cleaned.contains("MEDIA:"));
        assertFalse(cleaned.contains("[[audio_as_voice]]"));
        assertFalse(cleaned.contains("[[as_document]]"));
    }

    @Test
    void stripMediaTagsForDisplay_noMediaTags() {
        String text = "Just regular text.";
        assertEquals("Just regular text.", service.stripMediaTagsForDisplay(text));
    }

    @Test
    void stripMediaTagsForDisplay_null() {
        assertEquals("", service.stripMediaTagsForDisplay(null));
    }

    @Test
    void stripMediaTagsForDisplay_empty() {
        assertEquals("", service.stripMediaTagsForDisplay(""));
    }

    // ─── Extension classification ────────────────────────────────

    @Test
    void mediaDescriptor_isImage() {
        var desc = new MediaDeliveryService.MediaDescriptor("/tmp/test.png", ".png", false, false);
        assertTrue(desc.isImage());
        assertFalse(desc.isVideo());
        assertFalse(desc.isAudio());
    }

    @Test
    void mediaDescriptor_isVideo() {
        var desc = new MediaDeliveryService.MediaDescriptor("/tmp/test.mp4", ".mp4", false, false);
        assertFalse(desc.isImage());
        assertTrue(desc.isVideo());
        assertFalse(desc.isAudio());
    }

    @Test
    void mediaDescriptor_isAudio() {
        var desc = new MediaDeliveryService.MediaDescriptor("/tmp/test.mp3", ".mp3", false, false);
        assertFalse(desc.isImage());
        assertFalse(desc.isVideo());
        assertTrue(desc.isAudio());
    }

    @Test
    void mediaDescriptor_isDocument() {
        var desc = new MediaDeliveryService.MediaDescriptor("/tmp/test.pdf", ".pdf", false, false);
        assertFalse(desc.isImage());
        assertFalse(desc.isVideo());
        assertFalse(desc.isAudio());
    }

    // ─── Masking tests ────────────────────────────────────────────

    @Test
    void maskProtectedSpans_masksCodeBlocks() {
        String content = "```python\nMEDIA:/tmp/inside.png\n```\nMEDIA:/tmp/outside.png";
        String masked = MediaDeliveryService.maskProtectedSpans(content);

        // The code block content should be masked (spaces)
        assertFalse(masked.contains("inside.png"));
        // The outside MEDIA: tag should be preserved
        assertTrue(masked.contains("outside.png"));
    }

    @Test
    void maskProtectedSpans_masksInlineCode() {
        String content = "Use `MEDIA:/tmp/example.png` for testing\nMEDIA:/tmp/real.png";
        String masked = MediaDeliveryService.maskProtectedSpans(content);

        // Inline code should be masked
        assertFalse(masked.contains("example.png"));
        // Real MEDIA: tag should be preserved
        assertTrue(masked.contains("real.png"));
    }

    @Test
    void maskProtectedSpans_preservesBacktickQuotedMediaPath() {
        String content = "MEDIA:`/tmp/backtick path.png`";
        String masked = MediaDeliveryService.maskProtectedSpans(content);

        // Backtick-quoted path after MEDIA: should NOT be masked
        assertTrue(masked.contains("backtick path.png"));
    }

    @Test
    void maskJsonStringMedia_masksMediaInJsonValues() {
        String content = "{\"result\": \"MEDIA:/tmp/stale.png\"}";
        String masked = MediaDeliveryService.maskJsonStringMedia(content);

        // The MEDIA: inside the JSON string value should be masked
        assertFalse(masked.contains("stale.png"));
    }

    @Test
    void maskJsonStringMedia_preservesMediaOutsideJson() {
        String content = "MEDIA:/tmp/real.png";
        String masked = MediaDeliveryService.maskJsonStringMedia(content);

        // MEDIA: outside JSON should be preserved
        assertTrue(masked.contains("real.png"));
    }

    @Test
    void maskJsonStringMedia_noMediaOrNoQuotes() {
        assertEquals("no quotes here", MediaDeliveryService.maskJsonStringMedia("no quotes here"));
        assertEquals("MEDIA: but no quotes", MediaDeliveryService.maskJsonStringMedia("MEDIA: but no quotes"));
    }
}