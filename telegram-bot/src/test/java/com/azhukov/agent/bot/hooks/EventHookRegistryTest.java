package com.azhukov.agent.bot.hooks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

class EventHookRegistryTest {

    private EventHookRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new EventHookRegistry();
    }

    @Test
    void registerAndEmit_firesHandler() {
        AtomicInteger fired = new AtomicInteger(0);
        registry.register(EventHookRegistry.AGENT_START, (eventType, context) -> fired.incrementAndGet());

        registry.emit(EventHookRegistry.AGENT_START, Map.of("session_id", "abc"));

        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    void emit_multipleHandlers_allFired() {
        AtomicInteger fired = new AtomicInteger(0);
        registry.register(EventHookRegistry.AGENT_START, (e, c) -> fired.incrementAndGet());
        registry.register(EventHookRegistry.AGENT_START, (e, c) -> fired.incrementAndGet());

        registry.emit(EventHookRegistry.AGENT_START);

        assertThat(fired.get()).isEqualTo(2);
    }

    @Test
    void emit_wildcardMatch_firesForSubEvents() {
        AtomicInteger fired = new AtomicInteger(0);
        registry.register("command:*", (eventType, context) -> fired.incrementAndGet());

        registry.emit("command:reset");
        registry.emit("command:new");
        registry.emit("agent:start"); // Should NOT fire wildcard

        assertThat(fired.get()).isEqualTo(2);
    }

    @Test
    void emit_noHandler_doesNotThrow() {
        registry.emit("nonexistent:event");
        // Should not throw
    }

    @Test
    void emit_handlerError_doesNotBlockOtherHandlers() {
        AtomicInteger firedAfterError = new AtomicInteger(0);
        registry.register("test:event", (e, c) -> { throw new RuntimeException("boom"); });
        registry.register("test:event", (e, c) -> firedAfterError.incrementAndGet());

        registry.emit("test:event");

        assertThat(firedAfterError.get()).isEqualTo(1);
    }

    @Test
    void emit_nullContext_worksFine() {
        AtomicInteger fired = new AtomicInteger(0);
        registry.register("test:event", (e, c) -> fired.incrementAndGet());

        registry.emit("test:event", null);

        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    void emitCollect_collectsReturnValues() {
        BiConsumer<String, Map<String, Object>> h1 = (e, c) -> c.put("__return__", "result1");
        BiConsumer<String, Map<String, Object>> h2 = (e, c) -> c.put("__return__", "result2");
        BiConsumer<String, Map<String, Object>> h3 = (e, c) -> { /* no return */ };
        registry.register("decision:event", h1);
        registry.register("decision:event", h2);
        registry.register("decision:event", h3);

        List<Object> results = registry.emitCollect("decision:event", new HashMap<>());

        assertThat(results).containsExactly("result1", "result2");
    }

    @Test
    void resolveHandlers_exactAndWildcard() {
        AtomicInteger exactFired = new AtomicInteger(0);
        AtomicInteger wildcardFired = new AtomicInteger(0);
        registry.register("command:reset", (e, c) -> exactFired.incrementAndGet());
        registry.register("command:*", (e, c) -> wildcardFired.incrementAndGet());

        registry.emit("command:reset");

        assertThat(exactFired.get()).isEqualTo(1);
        assertThat(wildcardFired.get()).isEqualTo(1);
    }

    @Test
    void getLoadedHooks_emptyByDefault() {
        assertThat(registry.getLoadedHooks()).isEmpty();
    }

    @Test
    void discoverAndLoad_noDirectory_doesNothing() {
        registry = new EventHookRegistry(Path.of("/nonexistent/hooks/path"));
        registry.discoverAndLoad();
        assertThat(registry.getLoadedHooks()).isEmpty();
    }

    @Test
    void discoverAndLoad_loadsFromDirectory() throws Exception {
        Path tempDir = Files.createTempDirectory("hooktest");
        Path hookDir = tempDir.resolve("test-hook");
        Files.createDirectories(hookDir);
        Files.writeString(hookDir.resolve("HOOK.json"), """
            {"name": "test-hook", "description": "Test hook", "events": ["agent:start", "command:*"]}
            """);
        Files.writeString(hookDir.resolve("handler.groovy"), "// handler");

        registry = new EventHookRegistry(tempDir);
        registry.discoverAndLoad();

        List<EventHookRegistry.LoadedHook> hooks = registry.getLoadedHooks();
        assertThat(hooks).hasSize(1);
        assertThat(hooks.get(0).name()).isEqualTo("test-hook");
        assertThat(hooks.get(0).events()).contains("agent:start", "command:*");
    }

    @Test
    void discoverAndLoad_executesGroovyScript_onEmit() throws Exception {
        // Create a temp file that the Groovy script will write to
        Path tempDir = Files.createTempDirectory("hooktest-exec");
        Path hookDir = tempDir.resolve("write-hook");
        Files.createDirectories(hookDir);
        Path markerFile = tempDir.resolve("marker.txt");

        Files.writeString(hookDir.resolve("HOOK.json"), """
            {"name": "write-hook", "description": "Writes a marker file", "events": ["agent:start"]}
            """);
        Files.writeString(hookDir.resolve("handler.groovy"),
            "new File('" + markerFile.toString().replace("\\", "\\\\") + "').text = 'fired:' + eventType");

        registry = new EventHookRegistry(tempDir);
        registry.discoverAndLoad();

        // Emit the event — should execute the Groovy script
        registry.emit(EventHookRegistry.AGENT_START, Map.of("session_id", "test"));

        // Verify the script was actually executed
        assertThat(Files.exists(markerFile)).isTrue();
        assertThat(Files.readString(markerFile)).isEqualTo("fired:agent:start");
    }

    @Test
    void discoverAndLoad_groovyScriptError_doesNotBlockEmit() throws Exception {
        Path tempDir = Files.createTempDirectory("hooktest-err");
        Path hookDir = tempDir.resolve("error-hook");
        Files.createDirectories(hookDir);

        Files.writeString(hookDir.resolve("HOOK.json"), """
            {"name": "error-hook", "description": "Always throws", "events": ["agent:start"]}
            """);
        Files.writeString(hookDir.resolve("handler.groovy"), "throw new RuntimeException('boom')");

        registry = new EventHookRegistry(tempDir);
        registry.discoverAndLoad();

        // Should not throw — errors are caught and logged
        registry.emit(EventHookRegistry.AGENT_START);
        // No exception propagated = test passes
    }

    @Test
    void allEventTypes_defined() {
        assertThat(EventHookRegistry.GATEWAY_STARTUP).isEqualTo("gateway:startup");
        assertThat(EventHookRegistry.SESSION_START).isEqualTo("session:start");
        assertThat(EventHookRegistry.SESSION_END).isEqualTo("session:end");
        assertThat(EventHookRegistry.SESSION_RESET).isEqualTo("session:reset");
        assertThat(EventHookRegistry.AGENT_START).isEqualTo("agent:start");
        assertThat(EventHookRegistry.AGENT_END).isEqualTo("agent:end");
    }
}