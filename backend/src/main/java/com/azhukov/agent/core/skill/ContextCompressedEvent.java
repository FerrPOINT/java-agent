package com.azhukov.agent.core.skill;

import java.util.UUID;

/**
 * Published after a successful context compression for a session. Listeners
 * that keep conversation-scoped caches (skill_view dedup, file-view dedup)
 * clear them: the original content is summarized away by compression, so a
 * re-view must return the full content again (Hermes:
 * conversation_compression.py:3989 resets skill-view dedup next to
 * read_file's file dedup on the same event).
 * <p>
 * sessionId may be null for a global reset.
 */
public record ContextCompressedEvent(UUID sessionId) {}
