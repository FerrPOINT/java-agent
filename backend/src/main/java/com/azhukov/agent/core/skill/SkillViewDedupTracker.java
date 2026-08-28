package com.azhukov.agent.core.skill;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hermes parity (tools/skills_tool.py:2030-2135): repeat-view dedup for
 * skill_view. When the SAME skill file was already served to a session and is
 * unchanged on disk, a re-view returns a short stub instead of re-sending the
 * full content — the earlier tool result in the conversation already carries
 * it verbatim. Cleared on context compression (the original content is
 * summarized away, so a re-view must return full content again) via
 * {@link ContextCompressedEvent}.
 * <p>
 * Never dedups setup-needed views: readiness depends on config/env state that
 * can change without the skill file changing, and the model must see the
 * refreshed setup status on a re-view (Hermes: skills_tool.py:2061-2065).
 * <p>
 * The tracker key is the SESSION id (Hermes uses task_id for the same
 * purpose — the conversation-scoped identity available to the tool).
 * <p>
 * Thread-safe: sessions can interleave tool calls on the async executor.
 */
@Component
public class SkillViewDedupTracker {

    /** Hermes _SKILL_VIEW_DEDUP_CAP = 200 — per-session LRU-ish cap. */
    static final int CAP = 200;

    /** Hermes _SKILL_VIEW_DEDUP_MESSAGE. */
    static final String UNCHANGED_MESSAGE =
        "Skill content unchanged since it was loaded earlier in this "
            + "conversation — refer to the earlier skill_view result; it is still "
            + "current and complete. (Re-issued after context compression, this "
            + "returns the full content again.)";

    /** record NameFileKey(resolvedName, filePath) — use a compact string key. */
    private final Map<String, Map<String, Fingerprint>> bySession = new ConcurrentHashMap<>();

    /** file mtime_ns + size, mirroring the Hermes fingerprint tuple. */
    record Fingerprint(String source, long mtimeMillis, long size) {}

    /** The payload of a dedup hit for the caller to render as a stub. */
    public record DedupHit(String resolvedName) {
        public String message() {
            return UNCHANGED_MESSAGE;
        }
    }

    /**
     * Record a served skill view. Called AFTER a full-content view succeeded
     * (never for setup-needed payloads).
     */
    public void record(String sessionId, String resolvedName, String filePath,
                       String sourcePath, long mtimeMillis, long size) {
        if (sessionId == null || resolvedName == null || sourcePath == null) {
            return;
        }
        Map<String, Fingerprint> cache = bySession.computeIfAbsent(sessionId, k -> bounded());
        synchronized (cache) {
            cache.put(key(resolvedName, filePath), new Fingerprint(sourcePath, mtimeMillis, size));
            while (cache.size() > CAP) {
                // FIFO eviction like Hermes cache.pop(next(iter(cache)))
                String first = cache.keySet().iterator().next();
                cache.remove(first);
            }
        }
    }

    /**
     * Check whether this exact skill file was already served to this session
     * and is unchanged on disk. Returns the hit to render as a stub, or null.
     */
    public DedupHit check(String sessionId, String requestedName, String filePath) {
        if (sessionId == null || requestedName == null) {
            return null;
        }
        Map<String, Fingerprint> cache = bySession.get(sessionId);
        if (cache == null || cache.isEmpty()) {
            return null;
        }
        synchronized (cache) {
            for (Map.Entry<String, Fingerprint> e : cache.entrySet()) {
                String recKey = e.getKey();
                String recName = nameOf(recKey);
                String recFp = fileOf(recKey);
                if (!java.util.Objects.equals(recFp, filePath == null ? "" : filePath)) {
                    continue;
                }
                // Hermes: raw arg / 'category/skill' / 'plugin:skill' all coalesce
                if (!requestedName.equals(recName)
                    && !requestedName.endsWith("/" + recName)
                    && !recName.endsWith("/" + requestedName)
                    && !requestedName.substring(requestedName.lastIndexOf(':') + 1).equals(recName)) {
                    continue;
                }
                Fingerprint fp = e.getValue();
                try {
                    java.nio.file.Path p = java.nio.file.Path.of(fp.source());
                    java.nio.file.attribute.BasicFileAttributes attrs =
                        java.nio.file.Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
                    if (attrs.lastModifiedTime().toMillis() != fp.mtimeMillis() || attrs.size() != fp.size()) {
                        cache.remove(recKey);
                        return null;
                    }
                } catch (Exception dropped) {
                    cache.remove(recKey);
                    return null;
                }
                return new DedupHit(recName);
            }
        }
        return null;
    }

    /** Compression reset: session-scoped when sessionId is present, else all. */
    @EventListener
    public void onContextCompressed(ContextCompressedEvent event) {
        if (event.sessionId() != null) {
            bySession.remove(event.sessionId());
        } else {
            bySession.clear();
        }
    }

    private static String key(String name, String filePath) {
        return name + "\u0000" + (filePath == null ? "" : filePath);
    }

    private static String nameOf(String key) {
        int i = key.indexOf('\u0000');
        return i < 0 ? key : key.substring(0, i);
    }

    private static String fileOf(String key) {
        int i = key.indexOf('\u0000');
        return i < 0 ? "" : key.substring(i + 1);
    }

    private static <K, V> Map<K, V> bounded() {
        return new java.util.LinkedHashMap<>();
    }
}
