package com.azhukov.agent.bot.hooks;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Event Hook System — lightweight event-driven system that fires handlers at key lifecycle points.
 *
 * <p>Ported from Hermes' {@code gateway/hooks.py} HookRegistry. Supports:
 * <ul>
 *   <li>Event types: gateway:startup, session:start, session:end, session:reset,
 *       agent:start, agent:end, command:* (wildcard)</li>
 *   <li>Handler registration via {@link #register(String, BiConsumer)}</li>
 *   <li>{@link #emit} — fire handlers, discarding return values</li>
 *   <li>{@link #emitCollect} — fire handlers, collecting non-null return values</li>
 *   <li>Loading hooks from {@code ~/.java-agent/hooks/} directory</li>
 * </ul>
 *
 * <p>Errors in hooks are caught and logged but never block the main pipeline.
 */
@Slf4j
public class EventHookRegistry {

    /** Supported event types. */
    public static final String GATEWAY_STARTUP = "gateway:startup";
    public static final String SESSION_START = "session:start";
    public static final String SESSION_END = "session:end";
    public static final String SESSION_RESET = "session:reset";
    public static final String AGENT_START = "agent:start";
    public static final String AGENT_END = "agent:end";

    /** Hooks directory — ~/.java-agent/hooks/ by default. */
    private final Path hooksDir;
    private final Map<String, List<BiConsumer<String, Map<String, Object>>>> handlers = new ConcurrentHashMap<>();
    private final List<LoadedHook> loadedHooks = new ArrayList<>();

    public EventHookRegistry() {
        this(Path.of(System.getProperty("user.home"), ".java-agent", "hooks"));
    }

    public EventHookRegistry(Path hooksDir) {
        this.hooksDir = hooksDir;
    }

    /** Return metadata about all loaded hooks. */
    public List<LoadedHook> getLoadedHooks() {
        return List.copyOf(loadedHooks);
    }

    /** Register a handler for an event type. */
    public void register(String eventType, BiConsumer<String, Map<String, Object>> handler) {
        handlers.computeIfAbsent(eventType, k -> new ArrayList<>()).add(handler);
    }

    /** Discover and load hooks from the hooks directory. */
    public void discoverAndLoad() {
        if (!Files.exists(hooksDir) || !Files.isDirectory(hooksDir)) {
            return;
        }
        try (var dirs = Files.list(hooksDir)) {
            dirs.filter(Files::isDirectory).sorted().forEach(this::loadHookDir);
        } catch (IOException e) {
            log.warn("[hooks] Error scanning hooks directory {}: {}", hooksDir, e.getMessage());
        }
    }

    private void loadHookDir(Path hookDir) {
        Path manifestPath = hookDir.resolve("HOOK.json");
        Path handlerPath = hookDir.resolve("handler.groovy");
        if (!Files.exists(manifestPath) || !Files.exists(handlerPath)) {
            return;
        }
        try {
            // Load manifest JSON
            String manifestContent = Files.readString(manifestPath);
            // Simple JSON parse — we use Jackson if available, but this is lightweight
            // For now, just parse with a minimal approach
            LoadedHook hook = parseManifest(hookDir, manifestContent);
            if (hook == null) {
                return;
            }

            // Note: in a real implementation, we'd dynamically load and execute
            // the handler script. For the Java port, hooks are registered as
            // BiConsumers programmatically. The discovery mechanism loads
            // metadata and would need a script engine (Groovy) to execute handlers.
            // For now, we store the metadata and log that the handler was found.
            loadedHooks.add(hook);
            log.info("[hooks] Loaded hook '{}' for events: {}", hook.name(), hook.events());

            // Register a handler that loads and executes the script
            // In production this would use Groovy scripting engine
            for (String event : hook.events()) {
                register(event, (eventType, context) -> {
                    try {
                        // Placeholder: in a full implementation, the handler.groovy
                        // script would be loaded and executed here
                        log.debug("[hooks] Hook '{}' fired for event '{}'", hook.name(), eventType);
                    } catch (Exception e) {
                        log.warn("[hooks] Error in handler '{}' for '{}': {}", hook.name(), eventType, e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            log.warn("[hooks] Error loading hook {}: {}", hookDir, e.getMessage());
        }
    }

    private LoadedHook parseManifest(Path hookDir, String json) {
        // Minimal JSON parsing — extract name, description, events
        // In production, use Jackson ObjectMapper
        String name = extractJsonString(json, "name");
        if (name == null) {
            name = hookDir.getFileName().toString();
        }
        String description = extractJsonString(json, "description");
        if (description == null) {
            description = "";
        }
        List<String> events = extractJsonArray(json, "events");
        if (events.isEmpty()) {
            log.warn("[hooks] Skipping {}: no events declared", name);
            return null;
        }
        return new LoadedHook(name, description, events, hookDir.toString());
    }

    private static String extractJsonString(String json, String key) {
        // Very simple JSON string extraction
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx);
        if (colon < 0) return null;
        int start = json.indexOf("\"", colon + 1);
        if (start < 0) return null;
        int end = json.indexOf("\"", start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static List<String> extractJsonArray(String json, String key) {
        List<String> result = new ArrayList<>();
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return result;
        int colon = json.indexOf(":", idx);
        if (colon < 0) return result;
        int start = json.indexOf("[", colon + 1);
        if (start < 0) return result;
        int end = json.indexOf("]", start);
        if (end < 0) return result;
        String arrayContent = json.substring(start + 1, end);
        // Extract quoted strings
        int pos = 0;
        while (pos < arrayContent.length()) {
            int s = arrayContent.indexOf("\"", pos);
            if (s < 0) break;
            int e = arrayContent.indexOf("\"", s + 1);
            if (e < 0) break;
            result.add(arrayContent.substring(s + 1, e));
            pos = e + 1;
        }
        return result;
    }

    /** Resolve all handlers that should fire for an event type, including wildcards. */
    List<BiConsumer<String, Map<String, Object>>> resolveHandlers(String eventType) {
        List<BiConsumer<String, Map<String, Object>>> result = new ArrayList<>(handlers.getOrDefault(eventType, List.of()));
        if (eventType.contains(":")) {
            String base = eventType.substring(0, eventType.indexOf(":"));
            String wildcardKey = base + ":*";
            result.addAll(handlers.getOrDefault(wildcardKey, List.of()));
        }
        return result;
    }

    /**
     * Fire all handlers registered for an event, discarding return values.
     *
     * <p>Supports wildcard matching: handlers registered for "command:*" will
     * fire for any "command:..." event.
     */
    public void emit(String eventType, Map<String, Object> context) {
        if (context == null) {
            context = Map.of();
        }
        for (var handler : resolveHandlers(eventType)) {
            try {
                handler.accept(eventType, context);
            } catch (Exception e) {
                log.warn("[hooks] Error in handler for '{}': {}", eventType, e.getMessage());
            }
        }
    }

    /** Convenience overload with no context. */
    public void emit(String eventType) {
        emit(eventType, Map.of());
    }

    /**
     * Fire handlers and return their results.
     *
     * <p>Like {@link #emit} but captures each handler's return value (via a
     * special context key "__return__"). Used for decision-style hooks.
     */
    public List<Object> emitCollect(String eventType, Map<String, Object> context) {
        if (context == null) {
            context = new HashMap<>();
        }
        List<Object> results = new ArrayList<>();
        for (var handler : resolveHandlers(eventType)) {
            try {
                Map<String, Object> ctx = new HashMap<>(context);
                handler.accept(eventType, ctx);
                Object result = ctx.get("__return__");
                if (result != null) {
                    results.add(result);
                }
            } catch (Exception e) {
                log.warn("[hooks] Error in handler for '{}': {}", eventType, e.getMessage());
            }
        }
        return results;
    }

    /** Metadata about a loaded hook. */
    public record LoadedHook(String name, String description, List<String> events, String path) {}
}