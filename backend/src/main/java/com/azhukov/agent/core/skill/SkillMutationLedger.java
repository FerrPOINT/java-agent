package com.azhukov.agent.core.skill;

import com.azhukov.agent.persistence.entity.SkillAuditLogEntity;
import com.azhukov.agent.core.ports.SkillAuditPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * h77 / Hermes parity (tools/skill_ledger.py, tracker #79686 P3): per-mutation
 * skill audit ledger. Every skill mutation — regardless of actor — appends one
 * entry describing who changed what, with before/after snapshots.
 *
 * <p>The ledger is TELEMETRY, NOT A GATE (Hermes design decision, Teknium-approved):
 * a ledger failure must never block the mutation it describes. Every public write
 * path here is wrapped so exceptions are logged and swallowed.</p>
 *
 * <p>Actors mirror Hermes {@code derive_actor()}: explicit override first, then
 * the background-review provenance signal (→ curator), else agent. The user-facing
 * CLI/API surface would pass {@code user} explicitly.</p>
 */
@Slf4j
@Component
public class SkillMutationLedger {

    public static final String ACTOR_CURATOR = "curator";
    public static final String ACTOR_AGENT = "agent";
    public static final String ACTOR_USER = "user";

    private final ObjectProvider<com.azhukov.agent.core.ports.SkillAuditPort> repositoryProvider;

    public SkillMutationLedger(ObjectProvider<com.azhukov.agent.core.ports.SkillAuditPort> repositoryProvider) {
        this.repositoryProvider = repositoryProvider;
    }

    /**
     * Record one skill mutation. NEVER raises and never blocks the caller.
     *
     * @param action   mutation action ("create", "update", "delete", "patch",
     *                 "write_file", "remove_file")
     * @param skill    skill name
     * @param actor    curator / agent / user; null derives from write context
     * @param oldValue JSON snapshot of the previous state (may be null)
     * @param newValue JSON snapshot of the new state (may be null)
     */
    public void record(String action, String skill, String actor, String oldValue, String newValue) {
        com.azhukov.agent.core.ports.SkillAuditPort repo = repositoryProvider.getIfAvailable();
        if (repo == null) {
            log.debug("Skill audit repository unavailable — skipping ledger entry '{}' for '{}'", action, skill);
            return;
        }
        try {
            String effectiveActor = actor != null ? actor : deriveActor();
            SkillAuditLogEntity entry = SkillAuditLogEntity.create(
                skill, action, effectiveActor, oldValue, newValue);
            repo.save(entry);
            log.debug("Skill ledger: skill='{}' action='{}' actor='{}'", skill, action, effectiveActor);
        } catch (Exception e) {
            log.warn("Skill ledger write failed for '{}' ({}): {} — mutation unaffected",
                skill, action, e.getMessage());
        }
    }

    /**
     * Hermes derive_actor(): background-review provenance → curator, else agent.
     */
    private String deriveActor() {
        return com.azhukov.agent.core.skill.WriteOrigin.BACKGROUND_REVIEW
            == com.azhukov.agent.core.memory.WriteContext.effectiveOrigin()
            ? ACTOR_CURATOR
            : ACTOR_AGENT;
    }
}
