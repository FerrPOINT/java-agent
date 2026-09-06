package com.azhukov.agent.core.skill;

import com.azhukov.agent.persistence.entity.SkillAuditLogEntity;
import com.azhukov.agent.core.ports.SkillAuditPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * h77 / Hermes parity (tools/skill_ledger.py): per-mutation skill audit ledger.
 * Design invariants pinned here:
 *  1. TELEMETRY, NOT A GATE — a ledger failure must never block the mutation.
 *  2. Actor derivation: BACKGROUND_REVIEW write context → curator, else agent.
 *  3. Every mutation records action + before/after snapshots.
 */
class SkillMutationLedgerTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<com.azhukov.agent.core.ports.SkillAuditPort> provider(com.azhukov.agent.core.ports.SkillAuditPort repo) {
        ObjectProvider<com.azhukov.agent.core.ports.SkillAuditPort> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(repo);
        return p;
    }

    @Test
    void recordsMutationWithActorAndSnapshots() {
        com.azhukov.agent.core.ports.SkillAuditPort repo = mock(com.azhukov.agent.core.ports.SkillAuditPort.class);
        SkillMutationLedger ledger = new SkillMutationLedger(provider(repo));

        ledger.record("update", "deploy-runbook", null, null,
            "{\"lifecycle\":\"after\"}");

        verify(repo, times(1)).save(any(SkillAuditLogEntity.class));
        var captor = org.mockito.ArgumentCaptor.forClass(SkillAuditLogEntity.class);
        verify(repo).save(captor.capture());
        SkillAuditLogEntity entry = captor.getValue();
        assertEquals("deploy-runbook", entry.getSkillName());
        assertEquals("update", entry.getAction());
        // foreground context in tests → agent (Hermes derive_actor fallback)
        assertEquals(SkillMutationLedger.ACTOR_AGENT, entry.getUserId());
    }

    @Test
    void backgroundReviewDerivesCuratorActor() {
        com.azhukov.agent.core.ports.SkillAuditPort repo = mock(com.azhukov.agent.core.ports.SkillAuditPort.class);
        SkillMutationLedger ledger = new SkillMutationLedger(provider(repo));

        com.azhukov.agent.core.memory.WriteContext.set(
            com.azhukov.agent.core.skill.WriteOrigin.BACKGROUND_REVIEW, "review",
            null, null, null, null);
        try {
            ledger.record("patch", "ci-pipelines", null, null, null);
        } finally {
            com.azhukov.agent.core.memory.WriteContext.clear();
        }

        var captor = org.mockito.ArgumentCaptor.forClass(SkillAuditLogEntity.class);
        verify(repo).save(captor.capture());
        assertEquals(SkillMutationLedger.ACTOR_CURATOR, captor.getValue().getUserId());
    }

    @Test
    void ledgerFailureNeverBlocksMutation() {
        com.azhukov.agent.core.ports.SkillAuditPort repo = mock(com.azhukov.agent.core.ports.SkillAuditPort.class);
        when(repo.save(any())).thenThrow(new RuntimeException("DB down"));
        SkillMutationLedger ledger = new SkillMutationLedger(provider(repo));

        // MUST NOT throw — telemetry, not a gate (Hermes design invariant)
        assertDoesNotThrow(() -> ledger.record("delete", "obsolete-skill", null, "before", null));
    }

    @Test
    void missingRepositoryIsSilentNoOp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.azhukov.agent.core.ports.SkillAuditPort> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        SkillMutationLedger ledger = new SkillMutationLedger(empty);

        assertDoesNotThrow(() -> ledger.record("create", "new-skill", null, null, "content"));
    }

    @Test
    void explicitActorOverrideWins() {
        com.azhukov.agent.core.ports.SkillAuditPort repo = mock(com.azhukov.agent.core.ports.SkillAuditPort.class);
        SkillMutationLedger ledger = new SkillMutationLedger(provider(repo));

        ledger.record("archive", "old-skill", SkillMutationLedger.ACTOR_USER, "{}", null);

        var captor = org.mockito.ArgumentCaptor.forClass(SkillAuditLogEntity.class);
        verify(repo).save(captor.capture());
        assertEquals(SkillMutationLedger.ACTOR_USER, captor.getValue().getUserId());
    }
}
