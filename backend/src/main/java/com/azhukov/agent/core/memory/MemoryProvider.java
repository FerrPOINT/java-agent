package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.model.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * Abstract base class for pluggable memory providers.
 * <p>
 * Ported from the original project's memory_provider.py. Gives the agent persistent recall across sessions.
 * The MemoryManager enforces a one-external-provider limit to prevent tool schema bloat
 * and conflicting memory backends.
 * <p>
 * Lifecycle (called by MemoryManager):
 * <ul>
 * <li>initialize() — connect, create resources, warm up</li>
 * <li>systemPromptBlock() — static text for the system prompt</li>
 * <li>prefetch(query) — background recall before each turn</li>
 * <li>syncTurn(user, asst) — async write after each turn</li>
 * <li>getToolSchemas() — tool schemas to expose to the model</li>
 * <li>handleToolCall() — dispatch a tool call</li>
 * <li>shutdown() — clean exit</li>
 * </ul>
 * Optional hooks (override to opt in):
 * <ul>
 * <li>onTurnStart(turn, message) — per-turn tick</li>
 * <li>onSessionEnd(messages) — end-of-session extraction</li>
 * <li>onSessionSwitch(newSessionId, ...) — mid-process session_id rotation</li>
 * <li>onPreCompress(messages) — extract before context compression</li>
 * <li>onMemoryWrite(action, target, content) — mirror built-in memory writes</li>
 * <li>onDelegation(task, result) — parent-side observation of subagent work</li>
 * </ul>
 */
public interface MemoryProvider {

 // ── Core recall / store ──────────────────────────────────────────────

 List<String> recall(String userId, String query, int limit);

 void store(String userId, String category, String fact);

 // ── Two-store methods (backward-compatible defaults) ────────────────

    default void store(String userId, String target, String category, String fact) {
        store(userId, category, fact);
    }

    /**
     * Finding 4.1: Store with provenance metadata.
     * Default implementation ignores provenance and delegates to the 4-arg store.
     */
    default void store(String userId, String target, String category, String fact,
                       java.util.Map<String, String> provenance) {
        store(userId, target, category, fact);
    }

    default String replace(String userId, String target, String oldText, String newText) {
        throw new UnsupportedOperationException("replace not supported");
    }

    /**
     * Finding 4.1: Replace with provenance metadata.
     * Default implementation ignores provenance and delegates to the 4-arg replace.
     */
    default String replace(String userId, String target, String oldText, String newText,
                           java.util.Map<String, String> provenance) {
        return replace(userId, target, oldText, newText);
    }

    default String remove(String userId, String target, String oldText) {
        throw new UnsupportedOperationException("remove not supported");
    }

    /**
     * Finding 4.1: Remove with provenance metadata.
     * Default implementation ignores provenance and delegates to the 4-arg remove.
     */
    default String remove(String userId, String target, String oldText,
                          java.util.Map<String, String> provenance) {
        return remove(userId, target, oldText);
    }

 default String read(String userId, String target) {
     List<String> facts = recall(userId, "", 100);
     return String.join("§", facts);
 }

 /**
  * Return the raw (unformatted) entry texts for the target store.
  * Unlike {@link #read}, this excludes headers, category prefixes, and
  * other formatting — just the pure entry content.
  * Used for accurate char counting (parity with Hermes _char_count).
  */
 default List<String> getRawEntries(String userId, String target) {
     return recall(userId, "", 100);
 }

 /**
  * Return the total char count of raw entries joined by the § delimiter.
  * Parity with Hermes _char_count(): len(ENTRY_DELIMITER.join(entries)).
  */
 default int getCharCount(String userId, String target) {
     List<String> entries = getRawEntries(userId, target);
     if (entries == null || entries.isEmpty()) {
         return 0;
     }
     return String.join("\n§\n", entries).length();
 }

 /**
  * Return the number of raw entries for the target store.
  */
 default int getEntryCount(String userId, String target) {
     List<String> entries = getRawEntries(userId, target);
     return entries == null ? 0 : entries.size();
 }

 default Map<String, String> getSnapshot(String userId) {
 String facts = read(userId, "memory");
 return Map.of("memory", facts, "user", "");
 }

 // ── Provider identity ────────────────────────────────────────────────

 /**
 * Short identifier for this provider (e.g. 'builtin', 'honcho', 'hindsight').
 * Default returns "builtin" for backward compatibility.
 */
 default String name() {
 return "builtin";
 }

 /**
 * Return True if this provider is configured, has credentials, and is ready.
 * Called during agent init to decide whether to activate the provider.
 * Should not make network calls — just check config and installed deps.
 */
 default boolean isAvailable() {
 return true;
 }

 // ── Core lifecycle ──────────────────────────────────────────────────

 /**
 * Initialize for a session. Called once at agent startup.
 * May create resources (banks, tables), establish connections, start background threads.
 */
 default void initialize(String sessionId, Map<String, Object> kwargs) {}

 /**
 * Return text to include in the system prompt.
 * Called during system prompt assembly. Return empty string to skip.
 * This is for STATIC provider info (instructions, status).
 * Prefetched recall context is injected separately via prefetch().
 */
 default String systemPromptBlock() {
 return "";
 }

 /**
 * Called before each turn to prefetch relevant memories.
 * Default implementation is a no-op.
 *
 * @param query the user input for this turn (may be used for semantic search)
 * @param sessionId the session identifier
 * @return formatted text to inject as context, or empty string if nothing relevant
 */
 default String prefetch(String query, String sessionId) {
 return "";
 }

 /**
 * Queue a background recall for the NEXT turn.
 * Called after each turn completes. The result will be consumed by prefetch() on the next turn.
 * Default is no-op — providers that do background prefetching should override this.
 */
 default void queuePrefetch(String query, String sessionId) {}

 /**
 * Called after a turn completes (success or error) to sync turn data.
 * Default implementation is a no-op. Implementations should make this non-blocking.
 *
 * @param sessionId the session identifier
 * @param turnMessages the messages from the completed turn
 */
 default void syncTurn(String sessionId, List<com.azhukov.agent.core.model.Message> turnMessages) {}

 /**
 * Return tool schemas this provider exposes.
 * Each schema follows the OpenAI function calling format.
 * Return empty list if this provider has no tools (context-only).
 */
 default List<ToolDefinition> getToolSchemas() {
 return List.of();
 }

 /**
 * Handle a tool call for one of this provider's tools.
 * Must return a JSON string (the tool result).
 * Only called for tool names returned by getToolSchemas().
 */
 default String handleToolCall(String toolName, Map<String, Object> args) {
 throw new UnsupportedOperationException("Provider " + name() + " does not handle tool " + toolName);
 }

 /**
 * Clean shutdown — flush queues, close connections.
 */
 default void shutdown() {}

 // ── Optional hooks (override to opt in) ─────────────────────────────

 /**
 * Called at the start of each turn with the user message.
 * Use for turn-counting, scope management, periodic maintenance.
 */
 default void onTurnStart(int turnNumber, String message, Map<String, Object> kwargs) {}

 /**
 * Called when a session starts.
 * Default implementation is a no-op.
 *
 * @param sessionId the session identifier
 */
 default void onSessionStart(String sessionId) {}

 /**
 * Called when a session ends.
 * Default implementation is a no-op.
 *
 * @param sessionId the session identifier
 */
 default void onSessionEnd(String sessionId) {}

 /**
 * Called when the agent switches session_id mid-process.
 * Fires on /resume, /branch, /reset, /new, and context compression.
 */
 default void onSessionSwitch(String newSessionId, String parentSessionId, boolean reset, boolean rewound) {}

 /**
 * Called before context compression discards old messages.
 * Use to extract insights from messages about to be compressed.
 * Return text to include in the compression summary prompt.
 */
 default String onPreCompress(String sessionId, List<com.azhukov.agent.core.model.Message> messages) {
 return "";
 }

 /**
 * Called when the built-in memory tool writes an entry.
 * Use to mirror built-in memory writes to your backend.
 *
 * @param action 'add', 'replace', or 'remove'
 * @param target 'memory' or 'user'
 * @param content the entry content
 */
 default void onMemoryWrite(String action, String target, String content, Map<String, Object> metadata) {}

 /**
 * Called on the PARENT agent when a subagent completes.
 * The parent's memory provider gets the task+result pair as an observation.
 */
 default void onDelegation(String task, String result, String childSessionId) {}

 /**
 * Return config fields this provider needs for setup.
 * Return empty list if no config needed (e.g. local-only providers).
 */
 default List<Map<String, Object>> getConfigSchema() {
 return List.of();
 }

 /**
 * Write non-secret config to the provider's native location.
 * Called by 'memory setup' after collecting user inputs.
 */
 default void saveConfig(Map<String, Object> values, String hermesHome) {}

 /**
 * Flush any pending writes / queues. Block until drained or timeout.
 * Used at real session boundaries.
 */
 default void flushPending(long timeoutMs) {}

 /**
 * Persist any pending turn data to the backend. Called at session boundaries.
 */
 default void flushPending() {
 flushPending(5000L);
 }
}