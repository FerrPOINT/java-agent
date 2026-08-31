# Multi-User Implementation Plan — 2026-08-31

## Current State

### Entities WITH userId (already migrated in V29)
| Entity | userId column | Used in queries? |
|--------|--------------|-----------------|
| SessionEntity | ✅ | ✅ findByUserId, findAllByUserId |
| MemoryEntity | ✅ | ✅ findByUserIdOrderByCreatedAtDesc |
| PendingMemoryEntity | ✅ | ✅ findByUserIdAndStatus |
| UsageEntity | ✅ | ✅ findByUserIdAndCreatedAtBetween |
| TodoEntity | ✅ | ✅ findByUserId |
| CronJobEntity | ✅ | ❌ findAll — no userId filtering |
| SkillEntity | ✅ | ❌ findAll, findByName — no userId filtering |
| CheckpointEntity | ✅ | ❌ findAll — no userId filtering |
| CronExecutionLogEntity | ✅ | ❌ findByJobId — no userId filtering |
| SkillAuditLogEntity | ✅ (hardcoded "curator") | ❌ findBySkillName — no userId filtering |

### Entities WITHOUT userId (global by design)
| Entity | Why global |
|--------|-----------|
| MessageEntity | Messages belong to sessions; isolation via session.userId |
| BackgroundJobEntity | Transient; tied to session |
| CompressionLockEntity | Transient; tied to session |
| CheckpointFileEntity | Child of checkpoint; isolation via checkpoint.userId |
| CuratorSnapshotEntity | System-level backup |
| McpOAuthEntity | System-level integration |

### Auth: single global API key
- `ApiKeyAuthFilter` validates against `agent.security.api-key` (one key)
- No UserEntity, no per-user API keys
- Bot: `AuthorizationService` uses flat allowlist (userIds, usernames, chatIds)
- Bot: `PairingService` exists but generates one-time codes, not persistent users

## Implementation Phases

### Phase 1: Migration V36 — audit_log.user_id + backfill (CURRENT)

**What**: Add `user_id` to `audit_log` table (the only remaining global table that needs ownership).

**Why**: `audit_log` currently records session_id but not user_id. For multi-user audit trails, we need to know which user triggered each audit event.

**Migration**:
```sql
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS user_id TEXT;
CREATE INDEX IF NOT EXISTS idx_audit_log_user_id ON audit_log(user_id);
```

**Risk**: Low — nullable column, no existing data loss.

### Phase 2: Service-layer userId filtering

**What**: Add userId-scoped methods to repositories and services that currently bypass ownership.

**Files to change**:
- `CronJobRepository`: add `findByUserId(String userId)`, `findByIdAndUserId(UUID id, String userId)`
- `CronJobService.list()`: accept optional userId parameter
- `CronJobController`: extract userId from auth context, pass to service
- `SkillRepository`: add `findByUserId(String userId)`, `findByNameAndUserId(String name, String userId)`
- `DatabaseSkillManager`: list/get/save with optional userId scope
- `CheckpointRepository`: add `findByUserId(String userId)`
- `CheckpointManager.list()`: accept optional userId
- `SkillAuditLogRepository`: add `findBySkillNameAndUserIdOrderByTimestampDesc`

**Principle**: `null` userId = global/admin access (backward-compatible). Non-null userId = scoped.

### Phase 3: Authenticated principal propagation

**What**: Extract userId from the authenticated principal in API requests and propagate it through the service layer.

**Files to change**:
- `ApiKeyAuthFilter`: when auth disabled (dev), use `AgentProperties.DEFAULT_USER_ID` as principal
- New `UserContext` holder (ThreadLocal or SecurityContext principal) carrying userId
- Controllers extract userId from `UserContext`, pass to service methods
- `ChatRequest.userId` already exists — wire it to the same context

**Bot side**:
- `BotMessageProcessor` already extracts `event.userId()` and passes to backend
- `AgentBackendClient` already sends `userId` in chat request body
- No bot changes needed — it already propagates userId

### Phase 4: Per-user API keys (V37 migration)

**What**: `UserEntity` + `UserApiKeyEntity` for per-user authentication.

**Migration V37**:
```sql
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    display_name TEXT,
    role VARCHAR(20) NOT NULL DEFAULT 'user',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS user_api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(id),
    key_hash TEXT NOT NULL UNIQUE,
    label TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);
```

**Auth flow**:
1. `ApiKeyAuthFilter` checks `user_api_keys` table by hash
2. If found, sets `UserContext` with the key's userId
3. If not found, falls back to global key (admin)
4. If neither, 401

**Admin CLI**: `java -jar ... --add-user username --role user` generates a key.

### Phase 5: Isolation enforcement

**What**: Enforce that users can only access their own resources.

**Rules**:
- Sessions: user sees only own sessions (already works via `findAllByUserId`)
- Memory: user sees only own memory (already works)
- Cron jobs: user sees only own jobs (needs Phase 2)
- Skills: shared skills (userId=null) visible to all; personal skills only to owner
- Checkpoints: user sees only own checkpoints (needs Phase 2)
- Audit log: user sees only own audit entries (needs Phase 1+2)

**Admin role**: can see all resources (userId=null in queries = global).

### Phase 6: Tests, migration verification, deployment

**Tests**:
- Migration test: V36+V37 apply cleanly on existing DB
- Isolation test: two users, verify no cross-access
- Auth test: per-user API key works, wrong key = 401
- Backward compat: existing API key still works as admin

**Deployment**: rebuild, deploy, verify live.