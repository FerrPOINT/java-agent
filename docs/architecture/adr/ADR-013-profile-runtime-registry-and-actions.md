# ADR-013: Profile runtime registry, safe config/env writes and dashboard actions

Status: Accepted
Date: 2026-09-14
Wave: docs/35 WP-4

## Context

Profile file metadata and named-profile config writes exist (`ProfileService.writeConfig`,
atomic writer, validation). But the default profile's config/env writes are 501 stubs, the
gateway/worker state is a static `gateway_running=false`, and every dashboard operation
(doctor, prompt-size, dump, security-audit, backup/import, config-migrate, checkpoint
prune) returns `501 not implemented in the Java port`. Nothing records what revision of
config/skills/toolsets a running runtime was built from, so there is no safe reload path.

Hermes reference: each profile owns `config.yaml` + `.env` under `~/.hermes/profiles/<name>/`;
gateway/config readers are profile-aware; dashboard ops run as audited, cancellable actions
that stream real status; runtime reload swaps a candidate runtime atomically.

## Decision

1. **`profile_runtime_state` (single row per profile)** — authoritative runtime view
   replacing static JSON: config revision, tool-registry revision, skill-manager revision,
   gateway binding state (per-platform home channel reference), worker state
   (`running|draining|stopped|failed`), last reload outcome and timestamps. Updated by the
   reload pipeline, read by dashboards.
2. **`profile_config_revisions`** — append-only revision log per profile config write:
   actor, revision number, redacted summary hash (never raw secret content). The default
   profile uses the same serialized writer as named profiles
   (`ProfileService.writeConfig` semantics: validate → atomic write → revision row).
3. **Profile `.env` store** — allowlisted keys only (the same key catalog `envRows`
   exposes), values persisted with filesystem permissions 600 under the profile dir,
   masked reads (`is_set` + `redact()`), NO reveal endpoint. If the secure store is
   unavailable (read-only FS), writes fail closed — the endpoint stays 501 rather than
   writing plaintext.
4. **Reload pipeline** — config change → validate against the typed `AgentProperties`
   binder → build candidate runtime pieces (tool registry revisions, skill manager) →
   swap atomically → bump `profile_runtime_state` → publish a revision event → close old
   managed resources. Failed validation keeps the previous runtime; the revision row
   records `failed` state. Gateway/skills/MCP mutations go through this pipeline only,
   never mutate global `AgentProperties` directly.
5. **`DashboardActionService`** — typed, whitelisted, auditable, cancellable actions:
   `doctor`, `prompt-size`, `dump`, `security-audit`, `config-migrate`, `backup`,
   `checkpoint-prune`. Each action has a Java implementation (no shell out), an action id,
   streamed status via the existing action status API, and an output artifact
   (path + hash, no inline secrets). `dashboard_actions` ledger persists id/state/output
   path/actor/profile/timestamps for recovery and audit.
6. **Worker lifecycle** — profile worker start/stop/restart/drain rides on
   `GatewayLifecycleService` (WP-2) + `profile_runtime_state.worker_state`, with the same
   permission/audit surface as gateway lifecycle.

## Consequences

- Dashboard operations no longer report success with static data: each one runs a real
  implementation or reports capability-disabled honestly.
- Config writes are auditable and rollback-safe: the previous config remains in the
  revision log, and a failed candidate never swaps.
- Env values never round-trip: reads are masked; there is no reveal path; secrets are not
  copied into backup/dump artifacts (redaction floor applies).
- Migrations: V56 adds `profile_runtime_state`, `profile_config_revisions`,
  `dashboard_actions`.
