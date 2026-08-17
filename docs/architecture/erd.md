# Entity-Relationship Diagram

> Derived from JPA entities (`persistence/entity/`) and Flyway migrations (V1–V26).
> Mermaid ER diagram syntax.

---

## Core Schema

```mermaid
erDiagram
    sessions {
        UUID id PK
        UUID parent_session_id FK "V19: session rotation"
        TEXT user_id
        TEXT title
        TEXT model_provider
        TEXT model_name
        TEXT system_prompt
        TEXT subgoal
        TEXT session_status "V19: default 'active'"
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    messages {
        UUID id PK
        UUID session_id FK
        INTEGER turn_index
        TEXT role
        TEXT content
        TEXT tool_call_id
        TEXT tool_call_name
        TEXT tool_call_arguments
        TIMESTAMPTZ created_at
    }

    memory {
        UUID id PK
        TEXT user_id
        TEXT category
        TEXT fact
        TEXT target "default: memory"
        TIMESTAMPTZ created_at
    }

    memory_pending {
        UUID id PK
        TEXT user_id
        TEXT action
        TEXT target
        TEXT content
        TEXT old_text
        TEXT summary
        TEXT origin
        TEXT status "default: pending"
        TIMESTAMPTZ created_at
        TIMESTAMPTZ resolved_at
    }

    todos {
        UUID id PK
        UUID session_id FK "V20: nullable (global kanban)"
        TEXT user_id
        TEXT title
        TEXT status "default: pending"
        TEXT priority
        TIMESTAMPTZ created_at
    }

    skills {
        UUID id PK
        TEXT name UK
        TEXT category
        TEXT content
        TEXT description
        TEXT version
        TEXT write_origin
        INTEGER view_count
        INTEGER manage_count
        BOOLEAN archived
        TEXT trust_level
        TEXT lifecycle_state
        BOOLEAN pinned
        TEXT absorbed_into
        TIMESTAMPTZ last_activity_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    session_cli_state {
        UUID session_id FK
        TEXT state_key PK
        TEXT state_value
    }

    sessions ||--o{ messages : "session_id"
    sessions ||--o{ todos : "session_id"
    sessions ||--o{ session_cli_state : "session_id"
    sessions ||--o{ sessions : "parent_session_id (V19 rotation)"
```

---

## Checkpoint & Usage

```mermaid
erDiagram
    checkpoints {
        UUID id PK
        TEXT description
        INTEGER file_count
        BIGINT total_size_bytes
        TEXT files_json
        TIMESTAMPTZ created_at
    }

    checkpoint_files {
        UUID id PK
        UUID checkpoint_id FK
        TEXT file_path
        TEXT file_hash
        BIGINT file_size
        TEXT content_base64
    }

    usage_log {
        UUID id PK
        UUID session_id FK "V21: FK to sessions"
        TEXT user_id
        TEXT model
        INTEGER prompt_tokens
        INTEGER completion_tokens
        INTEGER total_tokens
        DOUBLE cost
        INTEGER cache_read_tokens
        INTEGER cache_write_tokens
        TIMESTAMPTZ created_at
    }

    compression_locks {
        UUID id PK
        UUID session_id UK
        TIMESTAMPTZ locked_at
    }

    checkpoints ||--o{ checkpoint_files : "checkpoint_id"
    sessions ||--o{ usage_log : "session_id (V21 FK)"
```

---

## MCP, Cron & Curator

```mermaid
erDiagram
    mcp_oauth_tokens {
        UUID id PK
        TEXT server_name
        TEXT access_token
        TEXT refresh_token
        TIMESTAMPTZ expires_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    cron_jobs {
        UUID id PK
        TEXT name
        TEXT schedule
        TEXT prompt
        BOOLEAN enabled "default: true"
        TEXT deliver_to
        TEXT skills
        TIMESTAMPTZ created_at
        TIMESTAMPTZ last_run_at
        TIMESTAMPTZ next_run_at
    }

    curator_snapshots {
        UUID id PK
        TEXT reason
        INTEGER skill_count
        TEXT snapshot_data
        TEXT manifest
        TIMESTAMPTZ created_at
    }

    audit_log {
        BIGINT id PK
        TEXT session_id
        TEXT actor
        TEXT action
        TEXT resource
        TEXT details
        TIMESTAMPTZ created_at
    }
```

---

## Migration History

| Migration | Description |
|-----------|-------------|
| V1 | Baseline (placeholder) |
| V2 | Agent schema: sessions, messages, memory, todos, skills, context_references, compression_locks, approvals, gateway_routing, session_model_usage |
| V3 | Todos: add title, priority columns |
| V4 | Todos: drop content column |
| V5 | Memory: add target column |
| V6 | Memory: pending memory table |
| V7 | Cron jobs table |
| V8 | Checkpoints table |
| V9 | Usage log table |
| V10 | MCP OAuth tokens |
| V11 | Audit log |
| V12 | Skill provenance & telemetry (write_origin, view_count, manage_count, last_activity_at) |
| V13 | Curator snapshots |
| V14 | Cron jobs: add skills column |
| V15 | Curator lifecycle fields (lifecycle_state, pinned, absorbed_into) |
| V16 | Session CLI state (ElementCollection → session_cli_state table) |
| V17 | Checkpoint files table |
| V18 | Session search FTS |
| V19 | Session rotation (parent_session_id, session_status) |
| V20 | Todos: nullable session_id for global kanban items |
| V21 | FK constraints (sessions.parent_session_id, usage_log.session_id) |
| V22 | Composite indexes (todos, messages, usage_log, memory_pending, skills, cron_jobs, bot_sessions) |
| V23 | Dead table cleanup (dropped: gateway_routing, context_references, approvals, session_model_usage) |
| V24 | Memory version (version column on memory table) |
| V25 | Cron context_from (context_from column on cron_jobs) |
| V26 | Cron full fields (additional cron job metadata columns) |

---

## Entity Count

| Entity | Table | Migrations |
|--------|-------|------------|
| `SessionEntity` | `sessions` | V2, V16, V19 |
| `MessageEntity` | `messages` | V2 |
| `MemoryEntity` | `memory` | V2, V5 |
| `PendingMemoryEntity` | `memory_pending` | V6 |
| `TodoEntity` | `todos` | V2, V3, V4, V20 |
| `SkillEntity` | `skills` | V2, V12, V15 |
| `CheckpointEntity` | `checkpoints` | V8, V17 |
| `CheckpointFileEntity` | `checkpoint_files` | V17 |
| `UsageEntity` | `usage_log` | V9, V21 |
| `CronJobEntity` | `cron_jobs` | V7, V14 |
| `McpOAuthEntity` | `mcp_oauth_tokens` | V10 |
| `AuditLogEntity` | `audit_log` | V11 |
| `CuratorSnapshotEntity` | `curator_snapshots` | V13 |
| `CompressionLockEntity` | `compression_locks` | V2 |

Total: **14 JPA entities**, **26 Flyway migrations**.

Dropped tables (V23): `gateway_routing`, `context_references`, `approvals`, `session_model_usage`.