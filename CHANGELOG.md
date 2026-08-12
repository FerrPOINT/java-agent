# Changelog

## Session 4 — Architecture, Security, and Documentation Overhaul

### API Key Authentication
- Added `ApiKeyAuthFilter` for stateless API key authentication
- API key configured via `agent.security.api-key` property, validated on all non-public endpoints
- Health, actuator, webhook, and swagger-ui endpoints exempt from auth

### Controller Split (8 controllers)
- Split monolithic `AgentController` (~100 endpoints) into 8 focused controllers:
  - `AgentChatController` — chat, streaming, steer, stop, approvals, TTS, transcription
  - `SessionController` — session lifecycle, compression, undo, model switching, snapshots
  - `MemoryController` — memory CRUD, pending memory approval
  - `SkillController` — skill listing, reload, bundle management
  - `CheckpointController` — checkpoint create, list, diff, restore, delete
  - `RuntimeSettingsController` — config, reasoning, tools, goals, credits, codex runtime
  - `KanbanController` — todo/kanban board
  - `CuratorController` — curator status, run, pause, resume

### Agentic Loop Deduplication
- Consolidated duplicate agent loop logic between `AgentRuntimeService` and `AgentStreamingService`
- Shared `AgentLoopExecutor` handles tool execution, memory sync, and turn management
- Both sync and streaming paths use the same loop, eliminating behavioral drift

### Streaming UX Improvements
- **Cursor events**: SSE emits cursor positioning events for client-side rendering
- **Heartbeat**: periodic keepalive events prevent proxy/load-balancer timeouts
- **Fresh-final**: final response event always carries the complete response, not just accumulated deltas

### Database Fixes
- **FK constraints** (V21): added missing foreign keys on `sessions.parent_session_id` and `usage_log.session_id`
- **Composite indexes** (V22): added composite indexes for common query patterns (todos, messages, usage_log, memory_pending, skills, cron_jobs, bot_sessions)
- **Dead table cleanup** (V23): dropped `gateway_routing`, `context_references`, `approvals`, `session_model_usage`
- **Session rotation** (V19): added `parent_session_id` and `session_status` columns for compression-based rotation
- **Todos nullable session_id** (V20): `todos.session_id` now nullable for global kanban items
- **Pagination**: fixed pagination queries in session listing and usage log
- **HikariCP**: tuned pool size, idle timeout, max lifetime, leak detection

### Security
- **Security headers**: `SecurityHeadersFilter` adds X-Content-Type-Options, X-Frame-Options, X-XSS-Protection, Referrer-Policy
- **cdpUrl validation**: `UrlSafetyHandler` validates CDP URLs before connecting (SSRF protection)
- **MCP command validation**: MCP server commands validated against allowlist before execution
- **InboundMessageProcessor auth**: Telegram inbound messages validated against allowed user IDs/usernames

### Metrics (Micrometer + Prometheus)
- Added `micrometer-registry-prometheus` dependency
- `AgentMetrics` class tracks chat requests, streaming requests, tool executions, model calls
- Prometheus endpoint exposed at `/actuator/prometheus`

### Testcontainers
- Added Testcontainers dependencies for real PostgreSQL integration tests
- `@Tag("slow")` integration tests use Testcontainers PostgreSQL containers
- Default tests still use H2 in PostgreSQL mode for speed

### Flyway Migrations (23 total, V1–V23)
- V19: Session rotation (parent_session_id, session_status)
- V20: Todos nullable session_id
- V21: FK constraints
- V22: Composite indexes
- V23: Dead table cleanup

### Bot Fixes
- **Content-type**: fixed content-type headers on bot API responses
- **Session persistence**: bot sessions now properly persisted across restarts
- **Streaming**: fixed streaming callback lifecycle and error handling
- **MarkdownConverter**: fixed markdown-to-HTML conversion for Telegram messages

### OpenAPI / Swagger UI
- Added `springdoc-openapi-starter-webmvc-ui` dependency
- Swagger UI accessible at `/swagger-ui.html`, API docs at `/api-docs`
- `@Tag` annotations on all 8 split controllers
- `@Operation` annotations on key methods
- Swagger endpoints exempt from API key auth