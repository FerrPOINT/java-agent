# 01 — Scope: What to Port, What to Skip

Hermes Agent is a large Python project (~693 MB source, ~0.7 MLOC). A Java prototype must focus on the **narrow waist** described in `AGENTS.md`: the runtime that drives one conversation with tool calling. Everything else is either edge capability or platform glue.

## 1. Core — MUST port to Java

These modules form the irreducible agent loop. They are model/provider-agnostic and represent the actual intelligence layer.

| Python module | Purpose in Java | Notes |
|---------------|-------------------|-------|
| `run_agent.py` → `agent/conversation_loop.py` | `AgentRuntime` | conversation loop, tool dispatch, retries, compression |
| `model_tools.py` | `ToolRegistry` + `ToolDispatcher` | self-registering tool schemas and dispatch |
| `tools/registry.py` | `ToolScanner` / `ToolRegistrar` | AST-free discovery via classpath scanning or annotation |
| `toolsets.py` | `ToolsetResolver` | compose tool aliases |
| `agent/tool_executor.py` | `ToolExecutor` | execute tool calls, async bridging, sandbox checks |
| `agent/prompt_builder.py` | `PromptBuilder` | system prompt construction |
| `agent/context_engine.py` | `ContextEngine` interface | pluggable context management |
| `agent/context_compressor.py` | `ContextCompressor` | default compaction implementation |
| `agent/memory_manager.py` | `MemoryManager` | durable user/session memory |
| `agent/memory_provider.py` | `MemoryProvider` interface | Honcho / supermemory / SQLite adapters |
| `agent/skill_*.py` | `SkillManager` | skill discovery, loading, execution |
| `tools/skills_tool.py` | `SkillToolRegistry` | agent-facing skill tools |
| `agent/iteration_budget.py` | `IterationBudget` | prevents infinite tool loops |
| `agent/message_sanitization.py` | `MessageSanitizer` | role alternation, cache safety |
| `agent/display.py` | `AgentLogger` / spinner | optional, keep minimal |
| `hermes_logging.py` | `StructuredLogger` | replaces Python logging |
| `hermes_state.py` | `AgentState` | cross-turn mutable state |
| `hermes_constants.py` | `HermesConstants` | version, limits |

## 2. Gateway — port the skeleton, defer channels

The gateway is how Hermes talks to Telegram, Discord, Slack, etc. For a Java prototype we need:

| Python module | Java equivalent | Priority |
|---------------|-----------------|----------|
| `gateway/run.py` core | `GatewayRuntime` | high — lifecycle, config loading |
| `gateway/session.py` | `ConversationSession` | high |
| `gateway/platforms/` | pluggable `PlatformAdapter` | **defer all channels** except maybe Telegram |
| `gateway/delivery.py` | `MessageDelivery` | medium |
| `gateway/authz_mixin.py` | `AuthorizationMixin` | medium |
| `gateway/slash_commands.py` | `CommandRouter` | low |

**Defer:** WhatsApp, Matrix, Slack, Discord, WeChat, Feishu, DingTalk, Teams, SMS. Each requires third-party SDKs and OAuth flows. Keep only the adapter interface so they can be added later.

## 3. CLI — minimal REPL only

| Python module | Java equivalent | Priority |
|---------------|-----------------|----------|
| `cli.py` / `hermes_cli/main.py` | `HermesRepl` or `HermesCli` | medium — a simple command loop is enough |
| `hermes_cli/subcommands/` | JCommander / picocli commands | low |
| `hermes_cli/setup.py` | setup wizard | low |
| `hermes_cli/web_server.py` | Spring Boot `WebController` | medium |

**Defer:** full TUI, desktop app, dashboard, billing, profiles UI.

## 4. Tooling — selective port

| Tool category | Tools to port | Tools to skip |
|---------------|---------------|---------------|
| Essential file/terminal | `read_file`, `write_file`, `patch`, `search_files`, `terminal`, `process` | — |
| Web | `web_search`, `web_extract` | browser automation (heavy) |
| Memory/Skills | `skills_list`, `skill_view`, `skill_manage`, `memory` | — |
| Kanban | `kanban_*` | optional — depends on SQLite schema |
| Messaging | `send_message` | gateway already covers |
| Vision | `vision_analyze` | **port** — via OpenAI-compatible multimodal endpoint (Kimi K2.7-code) |
| Browser | `browser_*` (core subset) | **port** — local Chromium via CDP, lightweight |
| Image generation | `image_generate` | **skip** — FAL-specific, add later |
| Computer-use | `computer_use_tool.py` | **skip** — native OS automation |
| Voice/TTS | `tts_*`, `voice_mode`, `transcription_*` | **skip** — requires native audio |
| MCP | `mcp_tool.py` | **port** via `io.modelcontextprotocol.sdk` |
| Cron | `cronjob_tools.py` | **defer** — use `cron` or `ScheduledExecutorService` |
| Desktop UI | `desktop_ui.py`, `read_terminal`, `focus_pane` | **skip** — Electron/TUI specific |

## 5. Integrations to skip entirely

These are edge capabilities or vendor-specific SaaS integrations. They bloat the core without adding agent-ness.

- `optional-mcps/` (Blender, Linear, n8n, Unreal Engine)
- `optional-skills/` — most vertical domains (blockchain, finance, gaming, health, payments, etc.)
- `apps/desktop/`, `apps/bootstrap-installer/`
- `agent/lsp/` — LSP client is IDE-specific
- `agent/pet/` — PET protocol, niche
- `agent/transports/` — transport specifics
- `infographic/` — GitHub issue artifacts
- `native/fts5_cjk/` — SQLite extension, can be replaced by H2/Lucene if needed
- `cron/scripts/`, `datagen-config-examples/`
- Provider-specific extras: Anthropic, Exa, Firecrawl, FAL, Daytona, Hindsight, etc.
- All messaging SDKs except Telegram (optional)
- All OAuth/SaaS clients (Google, Microsoft Graph, Feishu, DingTalk, etc.)

## 6. Sandbox / Governance

Keep the interface but not the full sandbox runtime:

- `OpenShell/` — study for policy language; implement lightweight Java sandbox via `ProcessBuilder` + seccomp/Jail if needed.
- `agent-governance-toolkit/` — extract permission model, approval flow, audit log schema.
- `tirith_security.py` / `approval.py` — port the approval gate.

## 7. Hermes-Function-Calling

`Hermes-Function-Calling/` is a dataset and JSON schema standard, not runtime code. Include it as reference for how tool schemas and tool-call envelopes must look. Java code should generate equivalent JSON schemas (use Jackson + JSON Schema generator or manual).

## 8. Paperclip Adapter

`hermes-paperclip-adapter/` contains adapter patterns. Study for how external tools/APIs are wrapped; use as a template for Java plugin adapters.

## 9. NousFlash Agents

`nousflash-agents/` is experimental. Review for advanced patterns (multi-agent, reflection, learning graph) but do not port for the prototype.

## 10. wterm

`wterm/` is a terminal UI component. Skip for Java; replace with Java virtual terminal or simple process I/O.

## 11. Summary Table

| Layer | In Scope | Out of Scope |
|-------|----------|--------------|
| Agent loop + tools | ✅ | ❌ |
| Memory + skills | ✅ | ❌ |
| Gateway skeleton | ✅ | ❌ |
| Telegram adapter | maybe | default skip |
| Web API server | ✅ | ❌ |
| CLI REPL | ✅ | ❌ |
| Browser / computer-use | ❌ | ✅ |
| Image generation (FAL) | ❌ | ✅ |
| Voice / TTS / vision | ❌ | ✅ (vision теперь берём) |
| Desktop / TUI / dashboard | ❌ | ✅ |
| All other messaging platforms | ❌ | ✅ |
| SaaS OAuth integrations | ❌ | ✅ |
| Optional MCPs / skills | ❌ | ✅ |

## 12. Recommendation

Build three Maven modules in this order:

1. `hermes-core` — agent runtime, tool registry, memory, skills.
2. `hermes-gateway` — platform adapter interface + Telegram skeleton.
3. `hermes-cli` — REPL + Web server.

Do **not** add a channel until `hermes-core` can run a conversation with `read_file` and `terminal` end-to-end.
