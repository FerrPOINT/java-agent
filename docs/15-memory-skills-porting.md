# the original → Java Agent: Memory & Skills Porting Summary

**Scope:** Reverse-engineer the Python original memory and skills subsystems so a Java implementation (`com.azhukov.agent.memory.*` and `com.azhukov.agent.skills.*`) can mirror observable behavior. This document lists key classes, storage models, recall/search algorithms, skill file formats, loading/management flows, and a per-area **port / skip / defer** recommendation.

**Source tree analyzed:** `/opt/dev/java-agent/prototype/python-agent`

---

## 1. Executive summary: port / skip / defer

| Area | Verdict | Rationale |
|------|---------|-----------|
| Built-in file-backed memory (`MEMORY.md` / `USER.md`) | **PORT** | Core user-visible feature; simple text-file store with JSON-array helper. Straightforward Java port. |
| Session/state SQLite database (`session_state.py`) | **PORT schema + FTS search** | Contains sessions, messages, and full-text search. Needed for session recall and `session_search`. Semantic/embedding search is **not** implemented locally; port only FTS + message anchoring. |
| Memory provider plugin architecture | **PORT contract, DEFER individual providers** | The ABC and `MemoryManager` orchestration are core. Concrete providers (Honcho, RetainDB, Supermemory, Hindsight, Mem0) delegate to external services; port the contract and one minimal built-in wrapper first. |
| Context engine / memory-context fencing | **PORT** | Required to inject prefetched memory safely into prompts and scrub it from streams. |
| Skill file format (`SKILL.md`) | **PORT** | YAML frontmatter + markdown body is the lingua-franca of the skill ecosystem. |
| Skill discovery & listing (`skills_tool` / `skill_utils`) | **PORT** | Walks `~/.agent/skills/` and optional external dirs; resolves collisions; filters by platform. |
| Skill runtime serving (`_serve_plugin_skill`) | **PORT** | Loads SKILL.md body + support files into the system prompt / messages. |
| Skill management (`skill_manager_tool`) | **PORT core actions, DEFER security scans/approval gates** | Create/edit/patch/delete/write_file/remove_file are needed. Security scanner (`skills_guard`), staging approval, and hub install can come later. |
| Skills Hub / external sources (`skills_hub.py`) | **DEFER** | GitHub/skills.sh/well-known/URL adapters are large and network-dependent. Not needed for a self-contained local skill runtime. |
| Skill usage telemetry / provenance (`skill_usage`, `skill_provenance`) | **DEFER** | Needed for background curator and pinned/archived skills. Can be emulated minimally at first. |
| Autonomous curation write guards | **PORT rules, DEFER background review wiring** | The security rules (no write to pinned/bundled/hub/external/protected skills) are simple policy. The ContextVar-based "background review" detection is Python-specific; model as an operation origin flag in Java. |

---

## 2. Memory subsystem

### 2.1 Key classes / files

| Python file | Purpose | Proposed Java package / class |
|-------------|---------|-------------------------------|
| `agent/memory_provider.py` | `MemoryProvider` ABC + `BuiltinMemoryProvider` wrapper around the file store. | `com.azhukov.agent.memory.MemoryProvider` (interface) `com.azhukov.agent.memory.BuiltinMemoryProvider` |
| `agent/memory_manager.py` | Orchestrates providers: registration, system prompt, prefetch, sync, tool routing, context fencing. | `com.azhukov.agent.memory.MemoryManager` |
| `tools/memory_tool.py` | `MemoryStore`: reads/writes `MEMORY.md` and `USER.md`; tool schemas for `memory_search`, `memory_add`, `memory_replace`, `memory_remove`. | `com.azhukov.agent.memory.store.MemoryStore` `com.azhukov.agent.memory.tool.MemoryTool` |
| `session_state.py` | SQLite session DB, schema, migrations, message CRUD, FTS search, anchored views. | `com.azhukov.agent.memory.store.SessionDatabase` + schema/migration classes |
| `tools/session_search_tool.py` | Agent-facing `session_search` tool over `session_state.py`. | `com.azhukov.agent.memory.tool.SessionSearchTool` |
| `agent/context_engine.py` | Builds a ranked context string from memory files, sessions, and tools. | `com.azhukov.agent.memory.ContextEngine` |
| `agent/agent_init.py` | Wires `MemoryStore`, `MemoryManager`, and plugins into the agent. | Wiring code in `com.azhukov.agent.runtime.AgentInitializer` |
| `plugins/memory/__init__.py` | Plugin loader (`load_memory_provider`) and built-in registration. | `com.azhukov.agent.memory.spi.MemoryProviderLoader` |
| `plugins/memory/{honcho,retaindb,supermemory,...}/__init__.py` | External provider implementations. | Separate modules under `com.azhukov.agent.memory.provider.*` |

### 2.2 Storage model

#### 2.2.1 File-backed built-in memory (`MemoryStore`)

- Files live under the the original home directory (`~/.agent/`):
  - `MEMORY.md` — agent facts about the project/domain.
  - `USER.md` — user preferences / identity.
- Format: each file is a flat markdown-ish text file. `MemoryStore` treats it as a list of entries delimited by blank lines, but the canonical on-disk format is plain paragraphs/bullets.
- Runtime in-memory representation: `List[str]` chunks, loaded by `_read_file(path)`.
- Writes use `atomic_replace` (write temp file in same dir, then `os.replace`).
- Size limits enforced via `memory_char_limit` (default 2200) and `user_char_limit` (default 1375).
- Tool targets:
  - `"memory"` → `MEMORY.md`
  - `"user"` → `USER.md`

#### 2.2.2 Session/state SQLite (`session_state.py`)

Core tables (derived from schema/migration code):

```sql
CREATE TABLE sessions (
    id TEXT PRIMARY KEY,
    title TEXT,
    created_at TEXT,
    updated_at TEXT,
    parent_session_id TEXT,
    metadata TEXT  -- JSON
);

CREATE TABLE messages (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    role TEXT NOT NULL,        -- 'user' | 'assistant' | 'system' | 'tool'
    content TEXT,
    created_at TEXT,
    metadata TEXT,             -- JSON
    FOREIGN KEY (session_id) REFERENCES sessions(id)
);

-- Full-text search over messages.content
CREATE VIRTUAL TABLE messages_fts USING fts5(
    content,
    content='messages',
    content_rowid='rowid'
);

-- Triggers keep FTS index in sync
CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN
  INSERT INTO messages_fts(rowid, content) VALUES (new.rowid, new.content);
END;
CREATE TRIGGER messages_ad AFTER DELETE ON messages BEGIN
  INSERT INTO messages_fts(messages_fts, rowid, content) VALUES ('delete', old.rowid, old.content);
END;
CREATE TRIGGER messages_au AFTER UPDATE ON messages BEGIN
  INSERT INTO messages_fts(messages_fts, rowid, content) VALUES ('delete', old.rowid, old.content);
  INSERT INTO messages_fts(rowid, content) VALUES (new.rowid, new.content);
END;
```

- Additional bookkeeping tables may exist (e.g. for pending writes, migrations, FTS rebuild status).
- Java port: use any SQLite JDBC driver. Keep identical schema so existing `state.db` files remain compatible.

### 2.3 Recall / search algorithm

#### Built-in `MemoryStore.search(query)`

- Loads all chunks for the target file.
- Tokenizes query and chunks into lowercase words (splits on whitespace/punctuation).
- Computes per-chunk score: `sum of query term frequency in chunk / total words in chunk`.
- Returns top N chunks. This is a simple keyword density ranking, not semantic/embedding.

#### `ContextEngine`

- Combines:
  1. `MemoryStore` results for `MEMORY.md` and `USER.md`.
  2. `session_search` / message anchoring (recent messages + matches).
  3. Optional external memory provider context.
- Produces a single text block injected into the system prompt or user context.

#### Session search (`session_state.py` / `session_search_tool.py`)

- Searches `messages_fts` for query terms.
- Supports:
  - `query` (required)
  - `limit` / `max_messages`
  - `session_id` filter
  - anchored view: `get_messages_around(message_id, radius)` returns N messages before/after a hit.
- CJK handling: the code probes whether SQLite supports `fts5` tokenizers for CJK and falls back to a custom tokenizer/migration.

#### Memory provider plugins

- Plugins implement semantic/long-term recall via external APIs (Honcho dialectic, RetainDB, Supermemory, etc.).
- `MemoryManager.prefetch_all(query)` collects each provider’s prefetched context.
- External prefetches run in background threads with timeouts; failures are logged, not fatal.

### 2.4 Java mapping & porting notes

| Python concept | Java proposal |
|----------------|---------------|
| `MemoryProvider` ABC | `interface MemoryProvider` with methods: `String name()`, `boolean isAvailable()`, `void initialize(String sessionId, Map<String,Object> kwargs)`, `String systemPromptBlock()`, `String prefetch(String query, String sessionId)`, `void queuePrefetch(String query, String sessionId)`, `void syncTurn(String user, String assistant, String sessionId, List<Message> messages)`, `List<ToolSchema> getToolSchemas()`, `String handleToolCall(String name, Map<String,Object> args)`, lifecycle hooks. |
| `BuiltinMemoryProvider` | Wraps `MemoryStore` and exposes no extra tools (built-in tools live in `MemoryTool`). |
| `MemoryManager` | Holds `List<MemoryProvider>`; single external provider policy; thread-pool for background sync/prefetch; tool routing map. |
| `MemoryStore` | `MemoryStore.loadFromDisk()`, `search(String fileKey, String query, int limit)`, `addEntry(...)`, `replaceEntry(...)`, `removeEntry(...)`, atomic write helper. |
| `SessionDatabase` | Schema migrations, `searchMessages(...)`, `getMessagesAround(...)`, `listSessions(...)`, `getSession(...)`. |
| Context fencing | `MemoryContextFencer` / `StreamingContextScrubber`: strip `<memory-context>` tags and system notes from provider output and streams. |
| Tool injection | `MemoryManager.getAllToolSchemas()` returns bare function schemas; `AgentInitializer` wraps them into OpenAI-style `{type:"function", function: schema}`. |

---

## 3. Skills subsystem

### 3.1 Key classes / files

| Python file | Purpose | Proposed Java package / class |
|-------------|---------|-------------------------------|
| `agent/skill_utils.py` | Frontmatter parsing, namespace validation, platform matching, directory iteration, description normalization. | `com.azhukov.agent.skills.util.SkillUtils` |
| `agent/skill_commands.py` | `/skill`, `/bundle` slash commands; builds model messages from SKILL.md. | `com.azhukov.agent.skills.runtime.SkillCommandHandler` |
| `agent/skill_preprocessing.py` | Expands inline shell and template variables in skill text. | `com.azhukov.agent.skills.runtime.SkillPreprocessor` |
| `tools/skills_tool.py` | `skills_list`, `skill_view`, `_find_all_skills`, `_serve_plugin_skill`, collision-aware lookup. | `com.azhukov.agent.skills.service.SkillService` `com.azhukov.agent.skills.tool.SkillsTool` |
| `tools/skill_manager_tool.py` | `skill_manage`: create/edit/patch/delete/write_file/remove_file with guards. | `com.azhukov.agent.skills.manager.SkillManager` `com.azhukov.agent.skills.tool.SkillManageTool` |
| `tools/skills_hub.py` | Source adapters for GitHub/skills.sh/well-known/URL skill installation. | `com.azhukov.agent.skills.hub.*` (defer) |
| `tools/registry.py` | Tool registry (`registry.register(name, toolset, schema, handler, emoji)`). | `com.azhukov.agent.tools.ToolRegistry` (cross-cutting) |
| `skills/<category>/<skill>/SKILL.md` | Canonical in-repo skill files. | Read-only assets / test fixtures |

### 3.2 Skill format: `SKILL.md`

A skill is a directory (name = skill name) containing at minimum `SKILL.md`. Optionally it contains subdirectories: `references/`, `templates/`, `scripts/`, `assets/`.

`SKILL.md` structure:

```markdown
---
name: my-skill
description: "Use when X happens. Does Y."
metadata:
  agent:
    tags: [devops, docker]
    platforms: [linux, macos]   # optional platform filter
    requires: []                # optional list of required tool names
---

# My Skill

## When to use
...

## Steps
1. ...

## Pitfalls
...
```

Validation constraints (from `skill_manager_tool.py`):

- `name`: lowercase letters, digits, hyphens, underscores, dots; max 64 chars; must start with letter or digit.
- `description`: required; max 1024 chars; first ~57 chars shown in system prompt skill index (self-contained trigger recommended).
- Body must be non-empty after frontmatter.
- Max SKILL.md content: 100,000 chars.
- Supporting files max 1 MiB each, must live under `references/`, `templates/`, `scripts/`, `assets/`.

### 3.3 Skill loading and listing algorithm

1. **Skill directories** (from `agent/skill_utils.py`):
   - Primary: `~/.agent/skills/`
   - Optional external dirs from config `skills.external_dirs`.
   - Exclude paths matching `is_excluded_skill_path` (e.g. `.hub`, `.archive`, `optional-skills/` unless enabled).

2. **Discovery**: recursively find all `SKILL.md` files under each skill root.

3. **Collision resolution** (from `tools/skills_tool.py`):
   - Same skill name in multiple roots: local dir wins over external dirs; first external dir wins over later ones.
   - Log collisions but keep the winner.

4. **Lookup by name**:
   - Skill directory name matches the requested name.
   - Also supports qualified lookup `category/skill-name`.

5. **Platform filtering** (from `agent/skill_utils.py`):
   - If frontmatter contains `metadata.platforms`, only include skill if current platform matches.
   - Platform value normalized from `sys.platform`.

6. **Serving a skill** (`_serve_plugin_skill`):
   - Parse YAML frontmatter.
   - Read markdown body.
   - Replace `${references/...}` / `${templates/...}` placeholders with file content or URLs.
   - Truncate description to `SKILL_PROMPT_DESC_LIMIT` (60 chars) for compact indices.
   - Return as a system-prompt block or as a user/assistant message depending on invocation mode.

### 3.4 Skill management

`skill_manage` tool actions:

| Action | Behavior | Guards |
|--------|----------|--------|
| `create` | Write new `SKILL.md` under `~/.agent/skills/` (optionally in a category subdir). | Name validation; frontmatter validation; size limit; collision check; optional security scan; write-approval gate. |
| `edit` | Full rewrite of existing `SKILL.md`. | Same validations; background-review write guard; read-before-write guard for autonomous curation. |
| `patch` | Fuzzy find/replace in `SKILL.md` or a supporting file. | Validates result size & frontmatter; read-before-write guard. |
| `delete` | Remove skill dir. | Pinned guard; path-traversal guard; curator consolidation guard (`absorbed_into`); foreground = `rmtree`, background curator = `archive_skill`. |
| `write_file` | Add/overwrite supporting file. | Path must be under allowed subdirs; size limit; read-before-write if file exists. |
| `remove_file` | Delete supporting file. | Allowed-subdir path check; read-before-write guard. |

Important autonomous-curation policy (must preserve in Java):

- Background review/origin may **not** write to:
  - Pinned skills
  - External-dir skills
  - Protected built-in / bundled / hub-installed skills
  - Manually authored skills (`created_by != "agent"`)
- Foreground user-directed edits may still edit external/bundled/hub skills (but not pinned deletion).
- Read-before-write: a background review fork must have loaded the target file via `skill_view` in the same turn before mutating it.

### 3.5 Java mapping & porting notes

| Python concept | Java proposal |
|----------------|---------------|
| `Skill` domain object | `com.azhukov.agent.skills.model.Skill` (name, description, path, frontmatter map, body, tags, platforms, support files). |
| `SkillUtils` | `SkillParser.parseFrontmatter(String)`, `isValidNamespace(String)`, `iterSkillIndexFiles(Path)`, `getAllSkillsDirs()`, `skillMatchesPlatform(...)`, `extractSkillDescription(...)`. |
| `SkillsTool` | `SkillService.listSkills(...)`, `SkillService.viewSkill(String name, Optional<String> filePath)`, `SkillService.findSkill(String name)`. |
| `SkillPreprocessor` | `SkillPreprocessor.expand(String skillText, SkillContext ctx)` for shell/template substitution. |
| `SkillCommandHandler` | `/skill` and `/bundle` slash command handlers; builds messages and invalidates prompt cache. |
| `SkillManager` | `SkillManager.create(...)`, `edit(...)`, `patch(...)`, `delete(...)`, `writeFile(...)`, `removeFile(...)`; validators; atomic file writes. |
| `SkillManageTool` | Thin adapter wrapping `SkillManager` to produce JSON tool results. |
| Security/provenance | `SkillWriteGuard` encapsulating the policy above; `OperationOrigin` enum (`FOREGROUND`, `BACKGROUND_REVIEW`). |
| Skills Hub | Deferred to future `com.azhukov.agent.skills.hub.*` package. |

---

## 4. Recommended port order

1. **Skill file format & utilities** (`SkillUtils`, `Skill` model) — low risk, unblocks everything else.
2. **Skill discovery / listing / view** (`SkillService`) — needed to validate skill loading.
3. **Built-in memory store** (`MemoryStore`, `MEMORY.md` / `USER.md`) — core user feature.
4. **Session database schema + FTS search** — enables `session_search` and context anchoring.
5. **Memory provider ABC + `MemoryManager`** — integrate built-in provider; defer external providers.
6. **Context engine + fencing** — wire memory into prompts safely.
7. **Skill management** (`SkillManager` + guards) — allow agent to create/update skills.
8. **Skills Hub / external providers / usage telemetry** — defer until local runtime is stable.

---

## 5. Files created / modified

- **Created:** `/opt/dev/java-agent/docs/15-memory-skills-porting.md`
- **Modified:** none

## 6. Issues encountered

None. The source files were readable and no secrets/credentials were present in the inspected code.
