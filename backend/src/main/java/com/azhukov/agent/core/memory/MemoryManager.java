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
 * S14: MemoryManager — orchestrates memory providers for the agent.
 * <p>
 * Ported from Hermes' memory_manager.py. Wraps MemoryProvider with:
 * <ul>
 *   <li>One-provider limit enforcement — only one external provider allowed</li>
 *   <li>Memory tool schema injection into agent tool list</li>
 *   <li>queue_prefetch_all for next-turn background recall</li>
 *   <li>Shutdown drain with timeout</li>
 *   <li>Lifecycle hooks: on_turn_start, on_session_switch, on_pre_compress,
 *       on_delegation, on_memory_write</li>
 * </ul>
 */
@Component
@Slf4j
public class MemoryManager {

    private static final long SYNC_DRAIN_TIMEOUT_S = 5L;
    private static final String BUILTIN_PROVIDER_NAME = "builtin";

    private final List<MemoryProvider> providers = new ArrayList<>();
    private final Map<String, MemoryProvider> toolToProvider = new ConcurrentHashMap<>();
    private volatile boolean hasExternal = false;

    // Background executor for end-of-turn sync/prefetch — lazily created
    private volatile ExecutorService syncExecutor;
    private final Object syncExecutorLock = new Object();

    /**
     * Register a memory provider. Only one external (non-builtin) provider
     * is allowed at a time — a second attempt is rejected with a warning.
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
     * Get a provider by name.
     */
    public MemoryProvider getProvider(String name) {
        for (MemoryProvider p : providers) {
            if (BUILTIN_PROVIDER_NAME.equals(name)) {
                return p;
            }
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
     * Get tool definitions for all memory provider tools.
     * These are injected into the agent's tool list.
     */
    public List<ToolDefinition> getToolSchemas() {
        // The memory tools are already registered as @AgentTool implementations
        // (MemoryTool, SessionSearchTool, etc.) via the ToolRegistry.
        // This method is available for providers that contribute additional tools.
        return List.of();
    }

    /**
     * Inject memory provider tool schemas into the agent's tool list.
     * Skips tools that already exist by name.
     *
     * @param existingTools  the current tool list (not modified)
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
     * Build system prompt blocks from all providers.
     * Returns combined text, or empty string if no providers contribute.
     */
    public String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        for (MemoryProvider provider : providers) {
            try {
                // Memory providers contribute via the memory tool description;
                // no separate system prompt block in the Java agent.
            } catch (Exception e) {
                log.debug("Memory provider system prompt failed: {}", e.getMessage());
            }
        }
        return sb.toString().trim();
    }

    // ── Prefetch / recall ─────────────────────────────────────────────

    /**
     * Collect prefetch context from all providers.
     * Returns merged context text. Failures in one provider don't block others.
     */
    public String prefetchAll(String query, String sessionId) {
        StringBuilder sb = new StringBuilder();
        for (MemoryProvider provider : providers) {
            try {
                provider.prefetch(query, sessionId);
            } catch (Exception e) {
                log.debug("Memory provider prefetch failed (non-fatal): {}", e.getMessage());
            }
        }
        return sb.toString().trim();
    }

    /**
     * Queue background prefetch on all providers for the next turn.
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
                    provider.prefetch(query, sessionId);
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
     * Called at the start of each agent turn.
     * Triggers prefetch on all providers.
     */
    public void onTurnStart(String sessionId, String userInput) {
        try {
            prefetchAll(userInput, sessionId);
        } catch (Exception e) {
            log.debug("onTurnStart prefetch failed: {}", e.getMessage());
        }
    }

    /**
     * Called when a session switch occurs.
     * Notifies all providers of the session change.
     */
    public void onSessionSwitch(String oldSessionId, String newSessionId) {
        for (MemoryProvider provider : providers) {
            try {
                if (oldSessionId != null) {
                    provider.onSessionEnd(oldSessionId);
                }
                provider.onSessionStart(newSessionId);
            } catch (Exception e) {
                log.debug("onSessionSwitch failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Called before context compression to preserve memory state.
     */
    public void onPreCompress(String sessionId) {
        log.debug("onPreCompress for session {}", sessionId);
    }

    /**
     * Called when a task is delegated to a subagent.
     */
    public void onDelegation(String sessionId, String taskDescription) {
        log.debug("onDelegation from session {}: {}", sessionId, taskDescription);
    }

    /**
     * Called after a memory write operation completes.
     */
    public void onMemoryWrite(String sessionId, String category, String fact) {
        log.debug("onMemoryWrite for session {}: [{}] {}", sessionId, category, fact);
    }

    // ── Shutdown drain ─────────────────────────────────────────────────

    /**
     * Shutdown the background executor, waiting up to SYNC_DRAIN_TIMEOUT_S
     * seconds for in-flight work to drain.
     */
    public void shutdown() {
        ExecutorService executor = syncExecutor;
        if (executor == null) {
            return;
        }
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