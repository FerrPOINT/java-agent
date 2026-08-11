package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * S1/S4: MemoryManager — orchestrates memory providers for the agent.
 * <p>
 * Ported from Hermes' memory_manager.py. Wraps MemoryProvider with:
 * <ul>
 *   <li>One-provider limit enforcement — only one external provider allowed</li>
 *   <li>Memory tool schema injection into agent tool list</li>
 *   <li>queue_prefetch_all for next-turn background recall</li>
 *   <li>Shutdown drain with timeout — reverse-order provider shutdown</li>
 *   <li>Tool call routing via toolToProvider index</li>
 *   <li>Context fencing — sanitize_context + build_memory_context_block + StreamingContextScrubber</li>
 *   <li>Lifecycle hooks: on_turn_start, on_session_switch, on_pre_compress,
 *       on_delegation, on_memory_write</li>
 * </ul>
 */
@Component
@Slf4j
public class MemoryManager {

    private static final long SYNC_DRAIN_TIMEOUT_S = 5L;
    private static final String BUILTIN_PROVIDER_NAME = "builtin";

    /**
     * Reserved core tool names — provider tools that shadow these names are
     * rejected at registration (core tools always win). Ported from Hermes'
     * {@code _HERMES_CORE_TOOLS} (toolsets.py) per memory_manager.py lines 366-390.
     */
    private static final Set<String> CORE_TOOL_NAMES = Set.of(
        "clarify", "delegate_task", "memory", "send_message", "todo",
        "session_search", "skill_manage", "cronjob", "execute_code",
        "read_file", "write_file", "patch", "search_files", "terminal",
        "web_search", "web_extract",
        "browser_navigate", "browser_snapshot", "browser_click",
        "browser_type", "browser_scroll", "browser_back", "browser_press",
        "browser_vision", "browser_console", "browser_get_images",
        "browser_cdp", "browser_dialog",
        "vision_analyze", "delete_file", "process",
        "skills_list", "skill_view"
    );

    private final List<MemoryProvider> providers = new ArrayList<>();
    private final Map<String, MemoryProvider> toolToProvider = new ConcurrentHashMap<>();
    private volatile boolean hasExternal = false;

    // Background executor for end-of-turn sync/prefetch — lazily created
    private volatile ExecutorService syncExecutor;
    private final Object syncExecutorLock = new Object();

    // ── S1: Context fencing ─────────────────────────────────────────────

    /**
     * S1: Strip fence tags, injected blocks, and system notes from text.
     * Delegates to MemoryContextFence.sanitizeContext().
     */
    public String sanitizeContext(String text) {
        return MemoryContextFence.sanitizeContext(text);
    }

    /**
     * S1: Wrap prefetched memory in a fenced block with system note.
     * Delegates to MemoryContextFence.buildContextBlock().
     */
    public String buildMemoryContextBlock(String rawContext) {
        return MemoryContextFence.buildContextBlock(rawContext);
    }

    /**
     * S1: Create a fresh StreamingContextScrubber for streaming text.
     */
    public MemoryContextFence.StreamingContextScrubber createScrubber() {
        return new MemoryContextFence.StreamingContextScrubber();
    }

    // ── S1: Tool call routing ───────────────────────────────────────────

    /**
     * S1: Route a memory-related tool call to the appropriate provider.
     * Returns JSON string result. Returns error string if no provider handles the tool.
     */
    public String handleToolCall(String toolName, Map<String, Object> args) {
        MemoryProvider provider = toolToProvider.get(toolName);
        if (provider == null) {
            return "{\"error\":\"No memory provider handles tool '" + toolName + "'\"}";
        }
        try {
            return provider.handleToolCall(toolName, args);
        } catch (Exception e) {
            log.error("Memory provider '{}' handleToolCall({}) failed: {}", provider.name(), toolName, e.getMessage());
            return "{\"error\":\"Memory tool '" + toolName + "' failed: " + e.getMessage() + "\"}";
        }
    }

    /**
     * S1: Check if any provider handles this tool.
     */
    public boolean hasTool(String toolName) {
        return toolToProvider.containsKey(toolName);
    }

    /**
     * S1: Get all tool names across all providers.
     */
    public Set<String> getAllToolNames() {
        return Set.copyOf(toolToProvider.keySet());
    }

    // ── Registration ─────────────────────────────────────────────────────

    /**
     * Register a memory provider. Only one external (non-builtin) provider
     * is allowed at a time — a second attempt is rejected with a warning.
     * S4: Also populates toolToProvider index for tool call routing.
     */
    public synchronized void addProvider(MemoryProvider provider, String name) {
        boolean isBuiltin = BUILTIN_PROVIDER_NAME.equals(name);
        if (!isBuiltin) {
            if (hasExternal) {
                log.warn("Rejected memory provider '{}' — an external provider is already registered. " +
                    "Only one external memory provider is allowed at a time.", name);
                return;
            }
            hasExternal = true;
        }
        providers.add(provider);

        // S4: Populate toolToProvider index for routing
        try {
            for (ToolDefinition schema : provider.getToolSchemas()) {
                String toolName = schema.name();
                if (toolName == null || toolName.isEmpty()) {
                    continue;
                }
                // Gap 1: Reject provider tools that shadow reserved core tool names
                if (CORE_TOOL_NAMES.contains(toolName)) {
                    log.warn("Memory provider '{}' tool '{}' shadows a reserved core tool name; " +
                        "registration ignored. Core tools always win — rename the provider's tool " +
                        "to something unique.", name, toolName);
                    continue;
                }
                // Gap 3: Warn on duplicate tool name instead of silent skip
                if (toolToProvider.containsKey(toolName)) {
                    log.warn("Memory tool name conflict: '{}' already registered by '{}', " +
                        "ignoring from '{}'", toolName, toolToProvider.get(toolName).name(), name);
                    continue;
                }
                toolToProvider.put(toolName, provider);
            }
        } catch (Exception e) {
            log.debug("Failed to index tools for provider '{}': {}", name, e.getMessage());
        }

        log.info("Memory provider '{}' registered", name);
    }

    /**
     * Register the built-in provider.
     */
    public void addBuiltinProvider(MemoryProvider provider) {
        addProvider(provider, BUILTIN_PROVIDER_NAME);
    }

    /**
     * Get all registered providers in order.
     */
    public List<MemoryProvider> getProviders() {
        return List.copyOf(providers);
    }

    /**
     * S4: Get a provider by name — actually match by name, not always return first.
     */
    public MemoryProvider getProvider(String name) {
        if (name == null) return null;
        for (MemoryProvider p : providers) {
            if (name.equals(p.name())) {
                return p;
            }
        }
        // Backward compat: if looking for builtin and no name() match, return first
        if (BUILTIN_PROVIDER_NAME.equals(name) && !providers.isEmpty()) {
            return providers.get(0);
        }
        return null;
    }

    /**
     * Get the primary (first) provider — convenience for single-provider setups.
     */
    public MemoryProvider getPrimaryProvider() {
        return providers.isEmpty() ? null : providers.get(0);
    }

    // ── Tool schema injection ──────────────────────────────────────────

    /**
     * S4: Get tool definitions for all memory provider tools.
     * Collects from all providers' getToolSchemas() methods.
     */
    public List<ToolDefinition> getToolSchemas() {
        List<ToolDefinition> schemas = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MemoryProvider provider : providers) {
            try {
                for (ToolDefinition schema : provider.getToolSchemas()) {
                    if (!seen.contains(schema.name())) {
                        schemas.add(schema);
                        seen.add(schema.name());
                    }
                }
            } catch (Exception e) {
                log.debug("Memory provider getToolSchemas() failed: {}", e.getMessage());
            }
        }
        return schemas;
    }

    /**
     * Inject memory provider tool schemas into the agent's tool list.
     * Skips tools that already exist by name.
     *
     * @param existingTools  the current tool list (modified in place)
     * @param validToolNames  the set of valid tool names (updated in place)
     * @return the number of tools added
     */
    public int injectTools(List<ToolDefinition> existingTools, Set<String> validToolNames) {
        if (existingTools == null || validToolNames == null) {
            return 0;
        }
        List<ToolDefinition> schemas = getToolSchemas();
        Set<String> existingNames = new HashSet<>();
        for (ToolDefinition t : existingTools) {
            existingNames.add(t.name());
        }
        int added = 0;
        for (ToolDefinition schema : schemas) {
            if (existingNames.contains(schema.name())) {
                continue;
            }
            existingTools.add(schema);
            validToolNames.add(schema.name());
            existingNames.add(schema.name());
            added++;
        }
        return added;
    }

    // ── System prompt ──────────────────────────────────────────────────

    /**
     * S4: Build system prompt blocks from all providers.
     * Returns combined text, or empty string if no providers contribute.
     * Each non-empty block is labeled with the provider name.
     */
    public String buildSystemPrompt() {
        List<String> blocks = new ArrayList<>();
        for (MemoryProvider provider : providers) {
            try {
                String block = provider.systemPromptBlock();
                if (block != null && !block.isBlank()) {
                    blocks.add(block);
                }
            } catch (Exception e) {
                log.debug("Memory provider system prompt block failed: {}", e.getMessage());
            }
        }
        return String.join("\n\n", blocks);
    }

    // ── Prefetch / recall ─────────────────────────────────────────────

    /**
     * S4: Collect prefetch context from all providers.
     * Returns merged context text (not discarded). Failures in one provider don't block others.
     */
    public String prefetchAll(String query, String sessionId) {
        List<String> parts = new ArrayList<>();
        for (MemoryProvider provider : providers) {
            try {
                String result = provider.prefetch(query, sessionId);
                if (result != null && !result.isBlank()) {
                    parts.add(result);
                }
            } catch (Exception e) {
                log.debug("Memory provider prefetch failed (non-fatal): {}", e.getMessage());
            }
        }
        return String.join("\n\n", parts);
    }

    /**
     * S4: Queue background prefetch on all providers for the next turn.
     * Calls queuePrefetch() (not prefetch()) on each provider.
     * Provider work is dispatched to a background worker so a slow provider
     * can never block the caller.
     */
    public void queuePrefetchAll(String query, String sessionId) {
        List<MemoryProvider> snapshot = List.copyOf(providers);
        if (snapshot.isEmpty()) {
            return;
        }
        submitBackground(() -> {
            for (MemoryProvider provider : snapshot) {
                try {
                    provider.queuePrefetch(query, sessionId);
                } catch (Exception e) {
                    log.debug("Memory provider queue_prefetch failed (non-fatal): {}", e.getMessage());
                }
            }
        });
    }

    // ── Sync ───────────────────────────────────────────────────────────

    /**
     * Sync a completed turn to all providers on a background worker thread.
     */
    public void syncAll(String sessionId, List<Message> turnMessages) {
        List<MemoryProvider> snapshot = List.copyOf(providers);
        if (snapshot.isEmpty()) {
            return;
        }
        submitBackground(() -> {
            for (MemoryProvider provider : snapshot) {
                try {
                    provider.syncTurn(sessionId, turnMessages);
                } catch (Exception e) {
                    log.debug("Memory provider sync_turn failed (non-fatal): {}", e.getMessage());
                }
            }
        });
    }

    // ── Lifecycle hooks ────────────────────────────────────────────────

    /**
     * S4: Called at the start of each agent turn.
     * Forwards to all providers' onTurnStart() and triggers prefetch.
     */
    public void onTurnStart(String sessionId, String userInput) {
        List<MemoryProvider> snapshot = List.copyOf(providers);
        for (MemoryProvider provider : snapshot) {
            try {
                provider.onTurnStart(0, userInput, Map.of());
            } catch (Exception e) {
                log.debug("Memory provider onTurnStart failed: {}", e.getMessage());
            }
        }
        try {
            prefetchAll(userInput, sessionId);
        } catch (Exception e) {
            log.debug("onTurnStart prefetch failed: {}", e.getMessage());
        }
    }

    /**
     * S4: Called when a session switch occurs.
     * Forwards to all providers' onSessionSwitch() and notifies of session change.
     */
    public void onSessionSwitch(String oldSessionId, String newSessionId) {
        for (MemoryProvider provider : providers) {
            try {
                if (oldSessionId != null) {
                    provider.onSessionEnd(oldSessionId);
                }
                provider.onSessionStart(newSessionId);
                provider.onSessionSwitch(newSessionId, oldSessionId != null ? oldSessionId : "", false, false);
            } catch (Exception e) {
                log.debug("onSessionSwitch failed: {}", e.getMessage());
            }
        }
    }

    /**
     * S4: Called before context compression to preserve memory state.
     * Forwards to all providers' onPreCompress() and returns combined text.
     *
     * @param sessionId the session being compressed
     * @param messages  the actual messages about to be compressed (not empty list)
     * @return combined pre-compression text from all providers
     */
    public String onPreCompress(String sessionId, List<Message> messages) {
        List<String> parts = new ArrayList<>();
        for (MemoryProvider provider : providers) {
            try {
                String result = provider.onPreCompress(sessionId, messages);
                if (result != null && !result.isBlank()) {
                    parts.add(result);
                }
            } catch (Exception e) {
                log.debug("Memory provider onPreCompress failed: {}", e.getMessage());
            }
        }
        return String.join("\n\n", parts);
    }

    /**
     * S4: Called when a task is delegated to a subagent.
     * Forwards to all providers' onDelegation().
     */
    public void onDelegation(String sessionId, String taskDescription) {
        for (MemoryProvider provider : providers) {
            try {
                provider.onDelegation(taskDescription, "", sessionId);
            } catch (Exception e) {
                log.debug("Memory provider onDelegation failed: {}", e.getMessage());
            }
        }
    }

    /**
     * S4: Called after a memory write operation completes.
     * Forwards to external providers (skips builtin — it's the source of the write).
     */
    public void onMemoryWrite(String sessionId, String category, String fact) {
        for (MemoryProvider provider : providers) {
            if (BUILTIN_PROVIDER_NAME.equals(provider.name())) {
                continue;
            }
            try {
                provider.onMemoryWrite("add", "memory", fact, Map.of("session_id", sessionId, "category", category));
            } catch (Exception e) {
                log.debug("Memory provider onMemoryWrite failed: {}", e.getMessage());
            }
        }
    }

    /**
     * S1: Initialize all providers for a session.
     */
    public void initializeAll(String sessionId, Map<String, Object> kwargs) {
        for (MemoryProvider provider : providers) {
            try {
                provider.initialize(sessionId, kwargs);
            } catch (Exception e) {
                log.warn("Memory provider '{}' initialize failed: {}", provider.name(), e.getMessage());
            }
        }
    }

    /**
     * S1: Flush pending work across all providers.
     */
    public void flushPending(long timeoutMs) {
        for (MemoryProvider provider : providers) {
            try {
                provider.flushPending(timeoutMs);
            } catch (Exception e) {
                log.debug("Memory provider flushPending failed: {}", e.getMessage());
            }
        }
    }

    // ── Shutdown drain ─────────────────────────────────────────────────

    /**
     * S4: Shutdown the background executor and all providers in reverse order.
     * Drains the executor first (bounded by SYNC_DRAIN_TIMEOUT_S), then
     * calls shutdown() on each provider in reverse registration order.
     */
    public void shutdown() {
        // S4: Drain the executor first
        ExecutorService executor = syncExecutor;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(SYNC_DRAIN_TIMEOUT_S, TimeUnit.SECONDS)) {
                    log.warn("Memory manager drain timed out after {}s — forcing shutdown", SYNC_DRAIN_TIMEOUT_S);
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
            syncExecutor = null;
        }

        // S4: Reverse-order provider shutdown
        List<MemoryProvider> snapshot = List.copyOf(providers);
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            try {
                snapshot.get(i).shutdown();
            } catch (Exception e) {
                log.warn("Memory provider '{}' shutdown failed: {}", snapshot.get(i).name(), e.getMessage());
            }
        }

        log.info("Memory manager shut down");
    }

    // ── Internal ───────────────────────────────────────────────────────

    private void submitBackground(Runnable task) {
        if (syncExecutor == null) {
            synchronized (syncExecutorLock) {
                if (syncExecutor == null) {
                    syncExecutor = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "memory-sync");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        syncExecutor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.debug("Memory background task failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Check whether any provider is registered.
     */
    public boolean hasProviders() {
        return !providers.isEmpty();
    }
}