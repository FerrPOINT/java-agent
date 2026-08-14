package com.azhukov.agent.bot.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts {@code MEDIA:/path/to/file} tags and bare file paths from agent
 * response text, returning the list of media descriptors and the cleaned
 * text with all media references removed.
 *
 * <p>Ported from Hermes {@code gateway/platforms/base.py}:
 * <ul>
 *   <li>{@code extract_media()} — MEDIA: tag extraction with code-block/JSON masking</li>
 *   <li>{@code extract_local_files()} — bare file path detection with existence check</li>
 *   <li>{@code _mask_protected_spans()} — masks code blocks and blockquotes</li>
 *   <li>{@code _mask_json_string_media()} — masks MEDIA: inside JSON string values</li>
 * </ul>
 *
 * <p>Directives:
 * <ul>
 *   <li>{@code [[audio_as_voice]]} — deliver audio files as voice messages</li>
 *   <li>{@code [[as_document]]} — deliver images as documents (no recompression)</li>
 * </ul>
 */
@Service
@Slf4j
public class MediaDeliveryService {

    // ─── Extension allowlist ──────────────────────────────────────

    /**
     * Extensions eligible for media delivery. Matches the task spec allowlist.
     * Used for both MEDIA: tag path validation and bare-path detection.
     */
    public static final Set<String> EXTENSION_ALLOWLIST = Set.of(
        ".png", ".jpg", ".jpeg", ".webp", ".gif",
        ".mp4", ".webm",
        ".mp3", ".ogg", ".wav",
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".zip", ".tar", ".gz", ".bz2", ".7z",
        ".txt", ".csv", ".json", ".yaml", ".yml", ".md"
    );

    /** Image extensions — routed to sendPhoto / sendMediaGroup. */
    public static final Set<String> IMAGE_EXTENSIONS = Set.of(
        ".png", ".jpg", ".jpeg", ".webp", ".gif"
    );

    /** Video extensions — routed to sendVideo. */
    public static final Set<String> VIDEO_EXTENSIONS = Set.of(
        ".mp4", ".webm"
    );

    /** Audio extensions — routed to sendVoice (with [[audio_as_voice]]) or sendDocument. */
    public static final Set<String> AUDIO_EXTENSIONS = Set.of(
        ".mp3", ".ogg", ".wav"
    );

    /** Maximum images per sendMediaGroup call (Telegram album limit). */
    public static final int MAX_MEDIA_GROUP_SIZE = 10;

    // ─── Patterns ────────────────────────────────────────────────

    /**
     * Anchored MEDIA:&lt;path&gt; cleanup pattern. Only matches paths ending
     * in a known deliverable extension. Supports optional quoting (backtick,
     * double-quote, single-quote). Path anchors: ~/ (home), / (absolute),
     * X:\ or X:/ (Windows drive-letter).
     */
    private static final String EXT_ALTERNATION = buildExtAlternation();

    private static final Pattern MEDIA_TAG_CLEANUP_RE = Pattern.compile(
        "[`\"']?MEDIA:\\s*" +
        "(?<path>`[^`\\n]+`|\"[^\"\\n]+\"|'[^'\\n]+'|" +
        "(?:~/|/|[A-Za-z]:[/\\\\])\\S+(?:[^\\S\\n]+\\S+)*?\\.(?:" + EXT_ALTERNATION + "))" +
        "(?=[\\s`\"',;:)}\\]]|$)[`\"']?",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Bare file path pattern for detecting local files without MEDIA: prefix.
     * Anchors: ~/ (home), / (absolute), X:\ or X:/ (Windows).
     * Excludes paths inside URLs (negative lookbehind for /, :, word chars, dot).
     */
    private static final Pattern BARE_PATH_RE = Pattern.compile(
        "(?<![/:\\w.])(?:~/|/|[A-Za-z]:[/\\\\])(?:[\\w.\\-]+[/\\\\])*[\\w.\\-]+\\.(?:" + EXT_ALTERNATION + ")\\b",
        Pattern.CASE_INSENSITIVE
    );

    // Code-block masking patterns
    private static final Pattern FENCED_CODE_BLOCK_RE = Pattern.compile("```[^\\n]*\\n.*?```", Pattern.DOTALL);
    private static final Pattern INLINE_CODE_RE = Pattern.compile("`[^`\\n]+`");
    private static final Pattern BLOCKQUOTE_RE = Pattern.compile("^>.*$", Pattern.MULTILINE);

    // JSON string value pattern: a quote preceded by : , { or [ (with optional ws)
    private static final Pattern JSON_STRING_VALUE_RE =
        Pattern.compile("(?<=[:,{\\[])\\s*\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    // Directive patterns
    private static final String AUDIO_AS_VOICE = "[[audio_as_voice]]";
    private static final String AS_DOCUMENT = "[[as_document]]";

    // ─── Media Descriptor ────────────────────────────────────────

    /**
     * Describes a single media file to deliver.
     *
     * @param path       absolute or home-relative file path
     * @param extension  lowercased file extension including the dot (e.g. ".png")
     * @param asVoice    whether to deliver as a voice message ([[audio_as_voice]])
     * @param asDocument whether to deliver as a document, not photo ([[as_document]])
     */
    public record MediaDescriptor(String path, String extension, boolean asVoice, boolean asDocument) {

        /** True if this file is an image (png, jpg, jpeg, webp, gif). */
        public boolean isImage() {
            return IMAGE_EXTENSIONS.contains(extension);
        }

        /** True if this file is a video (mp4, webm). */
        public boolean isVideo() {
            return VIDEO_EXTENSIONS.contains(extension);
        }

        /** True if this file is an audio file (mp3, ogg, wav). */
        public boolean isAudio() {
            return AUDIO_EXTENSIONS.contains(extension);
        }
    }

    /**
     * Result of media extraction.
     *
     * @param media      list of media descriptors to deliver
     * @param cleanedText response text with all MEDIA: tags and bare paths removed
     */
    public record ExtractionResult(List<MediaDescriptor> media, String cleanedText) {}

    // ─── Public API ───────────────────────────────────────────────

    // M3: Known limitation — partial MEDIA: tags that are split across stream
    // chunks are not reassembled. This is a streaming-level issue: the regex
    // operates on per-chunk text, so a MEDIA: tag whose path is split across
    // two stream deltas will not be matched. This is acceptable because:
    // (1) MEDIA: tags are typically short enough to fit in a single chunk,
    // (2) the final (non-streaming) extraction in extractMediaTags() operates
    // on the complete text and will catch any tags missed during streaming.
    // The stripMediaTagsForDisplay method below handles the streaming case.

    /**
     * Extract MEDIA: tags and bare file paths from response text.
     *
     * <p>Process:
     * <ol>
     *   <li>Strip {@code [[audio_as_voice]]} and {@code [[as_document]]} directives</li>
     *   <li>Mask code blocks, inline code, blockquotes, and JSON string values</li>
     *   <li>Extract MEDIA:/path tags from the masked text</li>
     *   <li>Remove matched MEDIA: tags from the cleaned text</li>
     *   <li>Detect bare file paths (with File.exists() validation)</li>
     *   <li>Remove bare paths from the cleaned text</li>
     * </ol>
     *
     * @param content the raw agent response text
     * @return extraction result with media descriptors and cleaned text
     */
    public ExtractionResult extractMediaTags(String content) {
        if (content == null || content.isEmpty()) {
            return new ExtractionResult(List.of(), "");
        }

        // Check directives before stripping
        boolean hasVoiceDirective = content.contains(AUDIO_AS_VOICE);
        boolean forceDocument = content.contains(AS_DOCUMENT);

        // Strip directives from text
        String cleaned = content.replace(AUDIO_AS_VOICE, "");
        cleaned = cleaned.replace(AS_DOCUMENT, "");

        // ── Extract MEDIA: tags ──
        List<String> mediaPaths = new ArrayList<>();

        // Mask protected spans to prevent false positives
        String scanContent = maskProtectedSpans(cleaned);
        scanContent = maskJsonStringMedia(scanContent);

        Matcher mediaMatcher = MEDIA_TAG_CLEANUP_RE.matcher(scanContent);
        List<int[]> mediaSpans = new ArrayList<>();
        while (mediaMatcher.find()) {
            String path = mediaMatcher.group("path");
            if (path != null) {
                path = unquotePath(path);
                if (!path.isEmpty()) {
                    mediaPaths.add(expandUserHome(path));
                    mediaSpans.add(new int[]{mediaMatcher.start(), mediaMatcher.end()});
                }
            }
        }

        // Remove MEDIA: tag spans from the unmasked cleaned text
        if (!mediaSpans.isEmpty()) {
            StringBuilder sb = new StringBuilder(cleaned);
            // Sort spans in reverse order to delete without shifting indices
            mediaSpans.sort((a, b) -> Integer.compare(b[0], a[0]));
            for (int[] span : mediaSpans) {
                sb.delete(span[0], span[1]);
            }
            cleaned = sb.toString();
        }

        // ── Extract bare file paths ──
        // Mask code blocks for bare-path detection too
        String bareScanContent = maskCodeBlocks(cleaned);
        Matcher bareMatcher = BARE_PATH_RE.matcher(bareScanContent);
        List<String> barePaths = new ArrayList<>();
        List<int[]> bareSpans = new ArrayList<>();
        while (bareMatcher.find()) {
            String raw = bareMatcher.group();
            String expanded = expandUserHome(raw);
            File f = new File(expanded);
            if (f.exists() && f.isFile()) {
                barePaths.add(expanded);
                bareSpans.add(new int[]{bareMatcher.start(), bareMatcher.end()});
            }
        }

        // Remove bare path spans from cleaned text
        if (!bareSpans.isEmpty()) {
            StringBuilder sb = new StringBuilder(cleaned);
            bareSpans.sort((a, b) -> Integer.compare(b[0], a[0]));
            for (int[] span : bareSpans) {
                sb.delete(span[0], span[1]);
            }
            cleaned = sb.toString();
        }

        // Collapse excessive blank lines
        cleaned = collapseBlankLines(cleaned);

        // ── Build media descriptors ──
        // Deduplicate by path, preserving discovery order
        Set<String> seen = new LinkedHashSet<>();
        List<MediaDescriptor> descriptors = new ArrayList<>();

        for (String path : mediaPaths) {
            if (seen.add(path)) {
                String ext = getExtension(path);
                descriptors.add(new MediaDescriptor(path, ext, hasVoiceDirective, forceDocument));
            }
        }
        for (String path : barePaths) {
            if (seen.add(path)) {
                String ext = getExtension(path);
                descriptors.add(new MediaDescriptor(path, ext, hasVoiceDirective, forceDocument));
            }
        }

        return new ExtractionResult(descriptors, cleaned);
    }

    /**
     * Strip MEDIA: tags and directives from text for streaming display.
     * This is a lightweight version that just removes the tags without
     * doing file existence checks or building descriptors.
     *
     * <p>Used during streaming so the user never sees raw MEDIA: tags.
     *
     * @param text the streaming text to clean
     * @return text with MEDIA: tags and directives removed
     */
    public String stripMediaTagsForDisplay(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (!text.contains("MEDIA:") && !text.contains(AUDIO_AS_VOICE) && !text.contains(AS_DOCUMENT)) {
            return text;
        }

        String cleaned = text.replace(AUDIO_AS_VOICE, "");
        cleaned = cleaned.replace(AS_DOCUMENT, "");

        // Use the anchored regex to strip MEDIA: tags
        cleaned = MEDIA_TAG_CLEANUP_RE.matcher(cleaned).replaceAll("");

        // Collapse excessive blank lines
        cleaned = collapseBlankLines(cleaned);

        return cleaned;
    }

    // ─── Masking ──────────────────────────────────────────────────

    /**
     * Mask fenced code blocks, inline code, and blockquotes with spaces
     * to prevent MEDIA: false positives. Preserves character count so
     * regex match offsets stay valid. Newlines are preserved.
     *
     * <p>Ported from Hermes {@code _mask_protected_spans()}.
     */
    static String maskProtectedSpans(String content) {
        char[] chars = content.toCharArray();
        int n = chars.length;

        List<int[]> spans = new ArrayList<>();

        // Fenced code blocks: ```...\n...```
        Matcher fenced = FENCED_CODE_BLOCK_RE.matcher(content);
        while (fenced.find()) {
            spans.add(new int[]{fenced.start(), fenced.end()});
        }

        // Inline code: `...` but NOT backtick-quoted paths in MEDIA: tags
        Matcher inline = INLINE_CODE_RE.matcher(content);
        while (inline.find()) {
            int start = inline.start();
            // Check if this is a backtick-quoted path after MEDIA:
            String prefix = content.substring(Math.max(0, start - 20), start);
            if (Pattern.compile("MEDIA:\\s*$").matcher(prefix).find()) {
                continue; // This is a MEDIA path quote, not inline code
            }
            spans.add(new int[]{start, inline.end()});
        }

        // Blockquote lines: > at line start
        Matcher blockquote = BLOCKQUOTE_RE.matcher(content);
        while (blockquote.find()) {
            spans.add(new int[]{blockquote.start(), blockquote.end()});
        }

        // Apply masking (preserve newlines)
        for (int[] span : spans) {
            for (int i = span[0]; i < span[1] && i < n; i++) {
                if (chars[i] != '\n') {
                    chars[i] = ' ';
                }
            }
        }

        return new String(chars);
    }

    /**
     * Mask {@code MEDIA:<bare-path>} occurrences inside JSON string values
     * to prevent re-delivery of stored tool-result text.
     *
     * <p>Ported from Hermes {@code _mask_json_string_media()}.
     */
    static String maskJsonStringMedia(String content) {
        if (!content.contains("\"") || !content.contains("MEDIA:")) {
            return content;
        }
        char[] chars = content.toCharArray();
        Matcher m = JSON_STRING_VALUE_RE.matcher(content);
        while (m.find()) {
            String seg = m.group(1);
            if (seg != null && Pattern.compile("MEDIA:\\s*(?:~/|/|[A-Za-z]:[/\\\\])").matcher(seg).find()) {
                // Mask the string body (group 1), preserving newlines
                for (int i = m.start(1); i < m.end(1) && i < chars.length; i++) {
                    if (chars[i] != '\n') {
                        chars[i] = ' ';
                    }
                }
            }
        }
        return new String(chars);
    }

    /**
     * Mask fenced code blocks and inline code for bare-path detection.
     * Simpler than {@link #maskProtectedSpans} — no blockquote masking needed
     * for bare paths.
     */
    private static String maskCodeBlocks(String content) {
        char[] chars = content.toCharArray();
        int n = chars.length;

        List<int[]> spans = new ArrayList<>();

        Matcher fenced = FENCED_CODE_BLOCK_RE.matcher(content);
        while (fenced.find()) {
            spans.add(new int[]{fenced.start(), fenced.end()});
        }

        Matcher inline = INLINE_CODE_RE.matcher(content);
        while (inline.find()) {
            spans.add(new int[]{inline.start(), inline.end()});
        }

        for (int[] span : spans) {
            for (int i = span[0]; i < span[1] && i < n; i++) {
                if (chars[i] != '\n') {
                    chars[i] = ' ';
                }
            }
        }

        return new String(chars);
    }

    // ─── Helpers ──────────────────────────────────────────────────

    /**
     * Unquote a MEDIA: path, removing surrounding backticks, double quotes,
     * or single quotes, and trimming trailing punctuation.
     */
    private static String unquotePath(String path) {
        path = path.trim();
        if (path.length() >= 2 && path.charAt(0) == path.charAt(path.length() - 1)
            && (path.charAt(0) == '`' || path.charAt(0) == '"' || path.charAt(0) == '\'')) {
            path = path.substring(1, path.length() - 1).trim();
        }
        // Strip remaining surrounding quotes and trailing punctuation
        path = path.replaceAll("^[`\"']+|[`\"',.;:)}\\]]+$", "");
        return path;
    }

    /**
     * Expand {@code ~/} to the user's home directory.
     */
    private static String expandUserHome(String path) {
        if (path.startsWith("~/")) {
            String home = System.getProperty("user.home");
            return home + path.substring(1);
        }
        return path;
    }

    /**
     * Get the lowercased file extension including the dot.
     */
    private static String getExtension(String path) {
        int dotIdx = path.lastIndexOf('.');
        if (dotIdx >= 0) {
            return path.substring(dotIdx).toLowerCase();
        }
        return "";
    }

    /**
     * Collapse 3+ consecutive newlines to 2.
     */
    private static String collapseBlankLines(String text) {
        return text.replaceAll("\\n{3,}", "\\n\\n").strip();
    }

    /**
     * Build the extension alternation string for regex, sorted longest-first
     * so the alternation never matches a shorter ext as a prefix of a longer one.
     */
    private static String buildExtAlternation() {
        List<String> exts = new ArrayList<>(EXTENSION_ALLOWLIST);
        // Sort by length descending, then alphabetically
        exts.sort((a, b) -> {
            int cmp = Integer.compare(b.length(), a.length());
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        // Strip leading dot from each
        List<String> parts = new ArrayList<>();
        for (String ext : exts) {
            parts.add(ext.substring(1));
        }
        return String.join("|", parts);
    }
}