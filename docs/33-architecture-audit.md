# Architecture Audit Report — java-agent

**Date:** 2026-08-15  
**Scope:** Full project — backend (346 files), telegram-bot (134 files), CLI (19 files)  
**Total:** 1030 Java files, ~65K LOC  

---

## Executive Summary

| Metric | Value |
|--------|-------|
| Modules | 3 (backend, telegram-bot, cli) |
| Main files | 499 |
| Test files | 531 |
| Test:Code ratio | 1.06:1 |
| God classes (>500 LOC or >15 deps) | 7 |
| Circular package dependencies | 6 genuine |
| Duplicated packages (security, health) | 2 pairs |
| Layering violations | 4 CRITICAL/HIGH |
| Overall architecture score | **5.6/10** |

---

## 1. Backend Module (346 files, ~43K LOC)

### 1.1 Package Structure

Top-level: `api`, `client`, `config`, `core`, `gateway`, `health`, `metrics`, `persistence`, `security`, `service`, `tools`

**Problems found:**

| Sev | Issue | Details |
|-----|-------|---------|
| MEDIUM | `core` is overloaded | 16 sub-packages — agent, audit, budget, client, context, memory, metadata, model, profile, prompt, sanitizer, security, skill, state, tool |
| MEDIUM | Duplicate `health` packages | `health/` (3 files) + `api/health/` (2 files) — both contain `HealthIndicator` impls |
| HIGH | Duplicate `security` packages | `security/` (12 files) + `core/security/` (13 files) — overlapping interfaces: `FileSafety`, `ToolCallGuardrail`, `MessageSanitizer`, `Redactor`, `UrlSafety` |

### 1.2 Layering Violations

| Sev | Violation | Details |
|-----|----------|---------|
| **CRITICAL** | Controller → Repository (bypasses service) | `SessionCrudController` injects `SessionRepository`, `MessageRepository` directly |
| **HIGH** | Controller returns JPA Entity | `CronJobController` returns `CronJobEntity` as API response |
| **HIGH** | Core → Persistence (entity/repository) | 11 core classes import `persistence.entity.*` / `persistence.repository.*` directly |
| **HIGH** | Core → Tools (inverted dependency) | `core.memory.BackgroundReviewService` imports `tools.memory.*` concrete tool classes |
| MEDIUM | Controller → Core (bypasses service) | `CuratorController` → `core.skill.*`, `MemoryController` → `core.memory.*` |

### 1.3 God Classes

| Class | LOC | Deps | Sev | Responsibilities |
|-------|-----|------|-----|-------------------|
| `DefaultAgentRuntime` | **1,847** | **31** | **CRITICAL** | Model orchestration, tool execution, context, memory, skills, fallback, interrupts, steer, commentary, mid-turn persistence, session locking |
| `AgentStreamingService` | **716** | **29** | **CRITICAL** | Duplicated agentic loop for SSE streaming — same logic as `DefaultAgentRuntime.runTurnInternal()` |
| `CuratorService` | **1,093** | ~14 | **CRITICAL** | Skill CRUD, backup, snapshot, import, export, validation, diff |
| `DefaultPromptBuilder` | **943** | ~10 | HIGH | System prompt assembly, personality, skills, memory, context, tool schemas |
| `DefaultContextCompressor` | **941** | ~11 | HIGH | Compression orchestration, locking, summarization, image placeholder, session rotation |
| `AgentConfig` | **368** | **68 imports** | HIGH | Single `@Configuration` wiring everything: model, context, memory, security, skills, tools, gateway |
| `AgentRuntimeService` | **507** | **25** | HIGH | Service-layer orchestration with inline entity construction |

### 1.4 Circular Dependencies

| Cycle | Sev | Description |
|-------|-----|-------------|
| `core.agent` ↔ `core.context` | HIGH | `DefaultAgentRuntime` ↔ `SessionLineageService` |
| `core.memory` ↔ `tools.memory` | HIGH | Core should not depend on tools |
| `core.memory` ↔ `core.skill` | MEDIUM | Bidirectional imports |
| `core.context` ↔ `core.prompt` | MEDIUM | `DefaultContextEngine` ↔ `DefaultPromptBuilder` |
| `core.agent` ↔ `security` | MEDIUM | Runtime ↔ guardrails |
| `client.langchain4j` ↔ `core.agent` | MEDIUM | Model client ↔ interrupt types |

### 1.5 Classes in Wrong Packages

| Class | Current | Should be | Sev |
|-------|---------|-----------|-----|
| `MidTurnPersistenceService` | `persistence` (root) | `service` or `persistence/service` | MEDIUM |
| `MessagePersistenceService` | `persistence` (root) | `service` or `persistence/service` | MEDIUM |
| `OsvCheckService` | `client/mcp` | `security` or `core/security` | MEDIUM |
| `ThinkScrubber` | `service` | `core/sanitizer` or `core/agent` | MEDIUM |
| `AgentMetrics` | `metrics` (1 file) | `config` or `core/agent` | LOW |

---

## 2. Telegram Bot Module (134 files, ~17K LOC)

### 2.1 God Classes

| Class | LOC | Deps | Sev | Responsibilities |
|-------|-----|------|-----|-------------------|
| `BotMessageProcessor` | **1,164** | **20** | **CRITICAL** | Event dispatch, command routing, text/media pipeline, streaming orchestration, busy session handling, media delivery, TTS, PII redaction, formatting, locking |
| `StreamEditor` | **1,497** | 17 maps | **CRITICAL** | Streaming edit, rate limiting, flood fallback, think scrubbing, heartbeat, draft streaming, rich messages — all in 17 `ConcurrentHashMap` state maps |
| `AgentBackendClient` | **1,474** | ~10 | **CRITICAL** | Sync chat, SSE streaming, session mgmt, context, usage, memory ops, skills, TTS, kanban, approvals — raw `JsonNode` parsing, no DTOs |
| `TelegramClient` | **1,032** | ~5 | HIGH | Text messaging, editing, media sending, chat actions, reactions, callbacks, file download, command registration, webhook, draft streaming, rate limiting, rich messages |

### 2.2 Other Issues

| Sev | Issue |
|-----|-------|
| HIGH | `BotMessageProcessor` double-locks same `ReentrantLock` (dead code in `handleTextOrMediaInternal`) |
| HIGH | `BotMessageProcessor` imports `GoalCommand` directly — breaks command abstraction |
| HIGH | `RichMessageSupport` manually `new`'d in `StreamEditor.init()`, not a Spring bean |
| HIGH | `CallbackQueryHandler` in `keyboard` package — does approval/session/model filtering, has hardcoded provider patterns |
| MEDIUM | `BotProperties` — 213 LOC, 12 nested config classes, 29 dependents |
| MEDIUM | `UpdateEvent` — 18-field record, 3 telescoping constructors, kitchen-sink DTO |
| MEDIUM | `MediaDeliveryService` in `media` package but does text parsing/regex, not delivery |
| MEDIUM | `DmTopicManager` — direct file I/O to `~/.java-agent/bot-config.json` |
| MEDIUM | `DeliveryRouter.to_string()` — Python naming convention |
| LOW | 4 single-class packages: `lock`, `footer`, `monitor`, `reaction` |
| LOW | `BotConfig` — double-brace initialization `SimpleClientHttpRequestFactory` |

---

## 3. CLI Module (19 files, ~5.4K LOC)

### 3.1 Package Structure

| Sev | Issue |
|-----|-------|
| **HIGH** | All 19 classes in single flat package — no sub-packages despite clear boundaries (`repl`, `command`, `backend`, `render`, `state`, `config`) |

### 3.2 God Classes

| Class | LOC | Sev | Responsibilities |
|-------|-----|-----|-------------------|
| `BackendClient` | **1,888** | **CRITICAL** | HTTP transport + JSON parsing + presentation formatting (3 responsibilities), ~65 methods, 40x duplicated error handling |
| `SlashCommandRegistry` | **1,041** | **CRITICAL** | Command resolution + 800-line `registerAll()` with 92 inline lambdas + owns non-injected state objects (`CliState`, `SessionStore`, `DestructiveCommandConfirmation`) |
| `MarkdownRenderer` | **564** | MEDIUM | Batch rendering + streaming renderer + syntax highlighting with hardcoded keywords |

### 3.3 Other Issues

| Sev | Issue |
|-----|-------|
| HIGH | `ReplLoop` (181 LOC) — dead code, not `@Component`, never instantiated; duplicates `CliReplRunner` |
| HIGH | `SlashCommandRegistry` manually instantiates `CliState`/`SessionStore`/`DestructiveCommandConfirmation` — service locator anti-pattern |
| MEDIUM | `BackendClient` — dual constructors (AGENTS.md violation) |
| MEDIUM | `SlashAutoSuggest` — hardcoded subcommand map duplicates registry knowledge |
| MEDIUM | Duplicate commands: `/handoff`≈`/model`, `/gquota`≈`/insights`, `/platforms`≈`/plugins`, `/quit`=`/exit` |
| MEDIUM | `System.exit(0)` in command lambdas — untestable |
| MEDIUM | `ContextReferenceExpander` — shell-out to `curl` via `sh -c` (command injection risk) |
| MEDIUM | `SessionStore.save()` — file write on every message |
| LOW | `BackendProperties` misnomer — should be `CliProperties` |
| LOW | `codex_runtime` — snake_case outlier among kebab-case commands |
| LOW | `SlashCommand.description()` default method — dead code, never called |

---

## 4. Cross-Module Issues

### 4.1 Module Dependencies

| Sev | Issue |
|-----|-------|
| **CRITICAL** | `telegram-bot/build.gradle` declares `implementation project(':backend')` but **zero imports** from backend — phantom dependency pulls entire backend JAR into bot |
| INFO | `cli` correctly has no direct backend dependency — REST only |
| INFO | Backend has no dependencies on bot or CLI |

### 4.2 Code Duplication

| Sev | What | Files |
|-----|------|-------|
| HIGH | `SharedObjectMapper` — 3 copies with divergent config | backend, telegram-bot, cli |
| HIGH | `AgentBackendClient` (1474 LOC) vs `BackendClient` (1888 LOC) — duplicate REST client logic | telegram-bot vs cli |
| MEDIUM | Runtime state concept — `CliState` vs `BotSessionEntity` — same flags, different names (`reasoningEffort` vs `reasoningLevel`) | cli vs telegram-bot |

### 4.3 API Contract

| Sev | Issue |
|-----|-------|
| HIGH | No shared DTO module — clients build untyped `Map<String,Object>` requests, parse `JsonNode` responses by string field names |
| MEDIUM | `response`/`content` field fallback — backend DTO uses `content`, both clients try `response` first |
| MEDIUM | API version mixing: `/api/v1/agent/*`, `/api/v2/sessions/*`, `/v1/chat/completions`, `/v1/models`, `/v1/capabilities` |
| MEDIUM | Bot sends `chatId`/`threadId` fields not in `ChatRequest` DTO — silently ignored |
| MEDIUM | Backend `gateway/telegram/` — second Telegram implementation alongside standalone bot |

---

## 5. Architecture Score by Module

| Dimension | Backend | Bot | CLI | Overall |
|-----------|---------|-----|-----|---------|
| Package organization | 7/10 | 7/10 | 4/10 | 6/10 |
| Layering | 4/10 | 6/10 | 5/10 | 5/10 |
| Coupling | 3/10 | 4/10 | 3/10 | 3/10 |
| Cohesion | 7/10 | 6/10 | 5/10 | 6/10 |
| DTO/Domain separation | 6/10 | 5/10 | 3/10 | 5/10 |
| Naming | 8/10 | 7/10 | 7/10 | 7/10 |
| Testability | 4/10 | 6/10 | 4/10 | 5/10 |
| **Overall** | **5.6/10** | **5.9/10** | **4.4/10** | **5.6/10** |

---

## 6. Refactoring Priorities

### CRITICAL (Immediate)

1. **Decompose `DefaultAgentRuntime`** (1847 LOC, 31 deps) → `TurnOrchestrator`, `FallbackController`, `ToolExecutionCoordinator`, `MemoryNudgeManager`, `SessionLockManager`
2. **Eliminate duplicated agentic loop** — extract shared `TurnExecutor` from `DefaultAgentRuntime` + `AgentStreamingService`
3. **Fix `SessionCrudController`** — delegate to service layer, not repositories directly
4. **Stop returning `CronJobEntity`** from `CronJobController` — create `CronJobDto`
5. **Split `BotMessageProcessor`** (1164 LOC, 20 deps) → `UpdateDispatcher`, `CommandExecutionService`, `TextMediaProcessor`, `StreamingOrchestrator`, `BusyMessageHandler`, `MediaDeliveryCoordinator`
6. **Extract `StreamSession`** from `StreamEditor` — consolidate 17 concurrent maps into per-chat state objects
7. **Split `BackendClient`** (CLI, 1888 LOC) → transport + formatter, or per-domain API classes
8. **Split `SlashCommandRegistry.registerAll()`** (800 LOC) → grouped command classes

### HIGH (Next Sprint)

9. **Remove phantom `project(':backend')`** from `telegram-bot/build.gradle`
10. **Create `shared` module** — API DTOs, `SharedObjectMapper`, base REST client
11. **Consolidate duplicate `security/` packages** — merge `security/` + `core/security/`
12. **Introduce repository ports in `core`** — 11 core classes depend on JPA repositories directly
13. **Break `core.memory` ↔ `tools.memory` cycle** — abstract behind `ToolSchemaProvider`
14. **Split `AgentConfig`** (68 imports) → `ModelClientConfig`, `MemoryConfig`, `SecurityConfig`, etc.
15. **Split `AgentBackendClient`** (bot, 1474 LOC) → per-domain delegate classes + typed DTOs
16. **Make `CliState`/`SessionStore`/`DestructiveCommandConfirmation`** Spring beans
17. **Delete `ReplLoop`** — dead code
18. **Break `core.agent` ↔ `core.context` cycle**

### MEDIUM (Backlog)

19. Unify `health/` + `api/health/` packages
20. Introduce CLI sub-packages (`repl`, `command`, `backend`, `render`, `state`)
21. Move inline controller records to `api/dto/`
22. Move `OsvCheckService` → `security`
23. Move `MidTurnPersistenceService` → `service`
24. Fix `BotMessageProcessor` double-lock dead code
25. Make `RichMessageSupport` a Spring bean
26. Remove duplicate CLI commands (`/handoff`, `/gquota`, `/platforms`, `/quit`)
27. Remove `System.exit(0)` from command lambdas
28. Replace shell-out `curl` in `ContextReferenceExpander` with `RestClient`
29. Decide on backend `gateway/telegram/` — remove or document
30. Standardize API versioning strategy
31. Remove `backend/settings.gradle` (ignored, misleading)

### LOW (Nice to Have)

32. Rename `ManagedToolGateway` → avoid `gateway` collision
33. Rename `ImageShrinker` → `ImageShrinkerService`
34. Rename `BackendProperties` → `CliProperties`
35. Rename `codex_runtime` → `codex-runtime`
36. Remove `SlashCommand.description()` dead code
37. Consolidate single-class packages (`lock`, `footer`, `monitor`, `reaction`)
38. Replace double-brace init in `BotConfig`
39. Merge `metrics` (1 file) into existing package

---

## 7. What Works Well

- **Command pattern** (bot) — `CommandHandler` interface + `CommandRegistry` + Spring auto-discovery is clean and extensible
- **Test coverage** — 531 test files, 1.06:1 ratio, every package tested
- **Bot persistence isolation** — separate tables, own Flyway history, no backend entity dependency
- **CLI REST-only architecture** — no direct backend dependency, pure HTTP client
- **Concurrency model** — per-chat locks, virtual threads for tools, `ConcurrentHashMap` for session state
- **Naming conventions** — consistent `*Controller`, `*Service`, `*Repository`, `*Entity`, `*Dto`, `*Tool`
- **Lombok usage** — `@RequiredArgsConstructor` + `@Slf4j` consistently applied
- **MapStruct** — clean entity↔domain↔DTO mapping with `unmappedTargetPolicy = ERROR`
