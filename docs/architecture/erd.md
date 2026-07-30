# Entity-Relationship Diagram

Database schema for the Java Agent platform, covering all 12 core tables defined in the migration DDL.

---

## ERD

```mermaid
erDiagram
    sessions {
        BIGINT id PK
        VARCHAR user_id
        VARCHAR title
        VARCHAR model_provider
        VARCHAR model_name
        TEXT system_prompt
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    messages {
        BIGINT id PK
        BIGINT session_id FK
        INTEGER turn_index
        VARCHAR role
        TEXT content
        VARCHAR tool_call_id
        VARCHAR tool_call_name
        TEXT tool_call_arguments
        TIMESTAMP created_at
    }

    memory {
        BIGINT id PK
        VARCHAR user_id
        VARCHAR category
        TEXT fact
        VARCHAR target
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    memory_pending {
        BIGINT id PK
        VARCHAR user_id
        VARCHAR action
        VARCHAR target
        TEXT content
        TEXT old_text
        TEXT summary
        VARCHAR origin
        VARCHAR status
        TIMESTAMP created_at
        TIMESTAMP resolved_at
    }

    todos {
        BIGINT id PK
        BIGINT session_id FK
        VARCHAR user_id
        VARCHAR title
        VARCHAR priority
        VARCHAR status
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    skills {
        BIGINT id PK
        VARCHAR name
        VARCHAR category
        TEXT description
        TEXT content
        INTEGER version
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    compression_locks {
        BIGINT id PK
        BIGINT session_id FK
        TIMESTAMP locked_at
    }

    checkpoints {
        BIGINT id PK
        VARCHAR description
        INTEGER file_count
        BIGINT total_size_bytes
        TIMESTAMP created_at
        JSON files_json
    }

    cron_jobs {
        BIGINT id PK
        VARCHAR name
        VARCHAR schedule
        TEXT prompt
        BOOLEAN enabled
        VARCHAR deliver_to
        TIMESTAMP created_at
        TIMESTAMP last_run_at
        TIMESTAMP next_run_at
    }

    usage_log {
        BIGINT id PK
        BIGINT session_id
        VARCHAR user_id
        VARCHAR model
        INTEGER prompt_tokens
        INTEGER completion_tokens
        INTEGER total_tokens
        DECIMAL cost
        TIMESTAMP created_at
    }

    mcp_oauth_tokens {
        BIGINT id PK
        VARCHAR server_name
        TEXT access_token
        TEXT refresh_token
        TIMESTAMP expires_at
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    audit_log {
        BIGINT id PK
        BIGINT session_id
        VARCHAR actor
        VARCHAR action
        VARCHAR resource
        JSON details
        TIMESTAMP created_at
    }

    sessions ||--o{ messages : "has"
    sessions ||--o{ todos : "has"
    sessions ||--o{ compression_locks : "has"
    sessions ||--o{ usage_log : "tracks"
```

---

## Relationship Summary

| Parent | Child | Cardinality | Description |
|---|---|---|---|
| sessions | messages | one-to-many | A session contains many conversation messages |
| sessions | todos | one-to-many | A session tracks many todo items |
| sessions | compression_locks | one-to-many | A session may hold compression locks per generation |
| sessions | usage_log | one-to-many | A session accumulates per-call usage/cost entries |

---

## Standalone Tables (No FK Relationships)

These tables are keyed by `user_id` or `server_name` rather than `session_id` and have no foreign-key relationship to `sessions`:

| Table | Keyed By | Purpose |
|---|---|---|
| memory | user_id | Persistent user-level memory facts |
| memory_pending | user_id | Staged memory changes awaiting reconciliation |
| skills | name (unique) | Skill definitions (prompts/workflows) |
| checkpoints | id | System-state snapshots for rollback |
| cron_jobs | name (unique) | Scheduled agent prompts |
| mcp_oauth_tokens | server_name | OAuth credentials for MCP tool servers |
| audit_log | session_id (nullable) | Compliance/security audit trail |