package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.metadata.ModelMetadataService;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Perf regression (2026-08-28): two rotation-storm root causes.
 * 1) missing ModelMetadataService → contextLength 0 → 16K fallback threshold.
 * 2) rotation mints a new session id → compression cooldown never applies.
 */
class RotationStormRegressionTest {

    private AgentProperties props() {
        AgentProperties p = new AgentProperties();
        p.getContext().setMaxTokens(16_000);       // config fallback (old bug path)
        p.getContext().setThresholdPercent(0.50);
        p.getContext().setTargetTokens(12_000);
        p.getContext().setProtectFirstN(1);
        p.getContext().setProtectLastN(4);
        return p;
    }

    @Test
    void metadataServiceRaisesPreflightThresholdToRealWindow() {
        // ~90KB of tool results ≈ 22K tokens at chars/4 — under a 256K window
        // (threshold 128K) but OVER the 16K config fallback.
        String bulky = "x".repeat(90_000);
        List<Message> messages = List.of(Message.system("sys"), Message.user(bulky));

        MemoryProvider mp = mock(MemoryProvider.class);
        SkillManager sm = mock(SkillManager.class);
        MessageRepository mr = mock(MessageRepository.class);
        ContextCompressor cc = mock(ContextCompressor.class);
        ModelMetadataService meta = mock(ModelMetadataService.class);
                org.mockito.Mockito.doReturn(256_000).when(meta).detectContextLength(any());
        org.mockito.Mockito.doReturn(new ModelMetadataService.ModelMetadata("m", "m", 256_000, 4))
            .when(meta).getMetadata(any());

        DefaultContextEngine withMeta = new DefaultContextEngine(mp, sm, mr, cc, props(), null, meta);
        assertThat(withMeta.shouldCompressPreflight(messages))
            .as("real 256K window: 22K estimated tokens must NOT trigger preflight")
            .isFalse();

        DefaultContextEngine withoutMeta = new DefaultContextEngine(mp, sm, mr, cc, props(), null, null);
        assertThat(withoutMeta.shouldCompressPreflight(messages))
            .as("legacy fallback (16K): same context DOES trip the old threshold — proves the bug")
            .isTrue();
    }

    @Test
    void rotationCarriesCompressionCooldownToChildSession() throws Exception {
        MemoryProvider mp = mock(MemoryProvider.class);
        SkillManager sm = mock(SkillManager.class);
        MessageRepository mr = mock(MessageRepository.class);
        AgentProperties p = props();
        DefaultContextCompressor cc = mock(DefaultContextCompressor.class);
        // Deliberately WITHOUT metadata service: reproduces the legacy 16K-window
        // path where preflight fires and rotation happens (the cooldown-carry fix
        // is what we assert below).
        DefaultContextEngine engine = new DefaultContextEngine(mp, sm, mr, cc, p, null, null);
        // expose cooldown map via the package-private seam: run a compressed prepareContext
        UUID parentId = UUID.randomUUID();
        Session parent = new Session(parentId, "u", "t", "openai-compatible", "m", null, java.util.Map.of(), null);

        // small config window so preflight trips; compressor returns trimmed; rotation returns child
        p.getContext().setMaxTokens(100);
        p.getContext().setTargetTokens(25);   // targetChars=100 < bulky 2000 → trim active
        org.mockito.Mockito.doReturn((Object) null).when(cc).compress(any(), any(Integer.class));
        org.mockito.Mockito.doReturn(java.util.Optional.of(
            new DefaultContextCompressor.SessionRotationResult(UUID.randomUUID(), "child")))
            .when(cc).rotateSession(any());
        com.azhukov.agent.persistence.entity.MessageEntity bulkyRow =
            new com.azhukov.agent.persistence.entity.MessageEntity();
        bulkyRow.setId(UUID.randomUUID());
        bulkyRow.setSessionId(parentId);
        bulkyRow.setContent("y".repeat(2_000));
        bulkyRow.setRole("user");
        bulkyRow.setCreatedAt(Instant.now());
        bulkyRow.setActive(true);
        lenient().when(mr.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), any()))
            .thenReturn(java.util.List.of(bulkyRow));

        String bulky = "y".repeat(2_000);
        engine.prepareContext(parent, List.of(Message.system("s"), Message.user(bulky)));
        // After prepareContext: if preflight tripped, lastCompressedAt(parentId) is set
        // even when rotation returns a child (cooldown carry below).

        Instant parentCooldown = engine.getLastCompressionAt(parentId);
        assertThat(parentCooldown).as("parent recorded a compression timestamp").isNotNull();

        // rotate: engine maps parent->child; child must inherit the cooldown
        java.lang.reflect.Field f = DefaultContextEngine.class.getDeclaredField("rotatedSessionIds");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<UUID, UUID> rotated = (java.util.Map<UUID, UUID>) f.get(engine);
        UUID childId = rotated.get(parentId);
        assertThat(childId).isNotNull();
        assertThat(engine.getLastCompressionAt(childId))
            .as("child inherits the parent's cooldown baseline — no re-rotation storm")
            .isEqualTo(parentCooldown);
    }
}
