# Hermes Agent → Java MVP: Tool Layer Porting Summary

**Scope:** Inventory every tool implementation under `/opt/dev/hermes-workspace/hermes-agent/tools/*.py`, group by functional category, and decide whether each tool belongs in the Java MVP (`port`), is out of scope (`skip`), or should be postponed (`defer`).

**Target Java package:** `com.azhukov.agent.tools.*`

**Legend**

- **Port:** Implement in the Java MVP.
- **Defer:** Implement only after core tool plumbing and primary flows are solid.
- **Skip:** Not required for the Java MVP; keep as Python-only or drop.

---

## 1. Tool Framework / Registration

**Source files:** `registry.py`, `__init__.py`

The Python tool layer is not class-based. Tools are registered at import time by calling:

```python
from tools.registry import registry, tool_error
registry.register(
    name="<tool-name>",
    toolset="<toolset-name>",
    schema={...},               # OpenAI function-calling JSON schema
    handler=<callable>,         # sync or async handler(args, **kwargs)
    check_fn=<callable>,        # returns bool, gates availability
    is_async=<bool>,
    emoji=<str>,
    description=<str>,
    dynamic_schema_overrides=<callable>,  # optional
)
```

**Key registry capabilities to port:**

- Import-time module discovery (auto-scan `tools/*.py`).
- Schema + handler registration per tool name.
- Availability gating via `check_fn` (env/config/provider readiness).
- Toolset grouping and alias support (`register_toolset_alias`).
- Async/sync handler dispatch.
- Tool-result envelope helper (`tool_error`).
- Optional dynamic schema overrides for tools such as `delegate_task`.

**MVP mapping**

| File | Python artifacts | Java target | Decision |
|------|-------------------|-------------|----------|
| `tools/registry.py` | `ToolRegistry` class, `registry` singleton, `tool_error()` | `com.azhukov.agent.tools.ToolRegistry`, `ToolResult` | **port** |
| `tools/__init__.py` | package exports, `discover_builtin_tools` wiring | `com.azhukov.agent.tools.ToolLoader` | **port** |

---

## 2. File Tools

**Source files:** `file_operations.py`, `file_tools.py`, `debug_helpers.py`

| Tool name | File | Python handler / schema | Argument schema (key fields) | Return type | Side effects | Java package | Decision |
|-----------|------|------------------------|------------------------------|-------------|--------------|--------------|----------|
| `read_file` | `file_tools.py` | `READ_FILE_SCHEMA`, handler via lambda | `path`, `offset`, `limit` | JSON string (content, total_lines, truncated) | reads file | `com.azhukov.agent.tools.file.FileReadTool` | **port** |
| `write_file` | `file_tools.py` | `WRITE_FILE_SCHEMA`, `write_file_tool` | `path`, `content`, `encoding` | JSON success/error | writes file | `com.azhukov.agent.tools.file.FileWriteTool` | **port** |
| `patch` | `file_tools.py` | `PATCH_SCHEMA`, `patch_tool` | `path`, `mode` (`replace`/`patch`), `old_string`, `new_string`, `patch` | JSON diff/status | edits file | `com.azhukov.agent.tools.file.FilePatchTool` | **port** |
| `search_files` | `file_tools.py` | `SEARCH_FILES_SCHEMA`, `search_files_tool` | `pattern`, `path`, `target` (`content`/`files`), `output_mode`, `limit`, `file_glob` | JSON matches | none (read-only) | `com.azhukov.agent.tools.file.FileSearchTool` | **port** |
| `delete_file` | `file_tools.py` | `DELETE_FILE_SCHEMA`, `delete_file_tool` | `path` | JSON status | deletes file | `com.azhukov.agent.tools.file.FileDeleteTool` | **port** |
| `file_exists` | `file_tools.py` | `FILE_EXISTS_SCHEMA`, `file_exists_tool` | `path` | JSON exists metadata | none | `com.azhukov.agent.tools.file.FileInfoTool` | **defer** |
| `create_artifact` | `file_tools.py` | `CREATE_ARTIFACT_SCHEMA`, `create_artifact_tool` | `name`, `content`, `encoding`, `artifactManifest` | JSON artifact entry | writes artifact entry | `com.azhukov.agent.tools.file.ArtifactCreateTool` | **skip** (Open-Design-specific) |
| (helpers) | `file_operations.py` | low-level file helpers (read/write/apply patch) | — | — | — | `com.azhukov.agent.tools.file.FileOperations` | **port** |
| (debug) | `debug_helpers.py` | `DebugSession` logging utility | — | — | writes debug logs | `com.azhukov.agent.tools.common.DebugSession` | **defer** |

**MVP notes:**

- File I/O is foundational; all read/write/patch/delete/search operations should be in the MVP.
- `file_exists` is useful but can be covered by read/search in early versions.
- `create_artifact` is tied to the Open Design integration; skip for MVP.

---

## 3. Terminal / Code Execution Tools

**Source files:** `terminal_tool.py`, `code_execution_tool.py`, `read_terminal_tool.py`, `process_registry.py`

| Tool name | File | Python handler / schema | Argument schema (key fields) | Return type | Side effects | Java package | Decision |
|-----------|------|------------------------|------------------------------|-------------|--------------|--------------|----------|
| `terminal` | `terminal_tool.py` | `TERMINAL_SCHEMA`, `terminal_tool` | `command`, `workdir`, `timeout`, `background`, `notify_on_complete`, `pty`, `env` | JSON output / session_id | spawns shell process | `com.azhukov.agent.tools.terminal.ShellTool` | **port** |
| `read_terminal` | `read_terminal_tool.py` | `READ_TERMINAL_SCHEMA`, `read_terminal_tool` | `session_id`, `action` (`poll`/`log`/`wait`/`kill`/`write`/`submit`/`close`), `timeout`, `limit`, `data` | JSON output/status | reads/kills background process | `com.azhukov.agent.tools.terminal.ProcessMonitorTool` | **port** |
| `execute_code` | `code_execution_tool.py` | `EXECUTE_CODE_SCHEMA`, `execute_code_tool` | `language`, `code`, `timeout`, `workdir`, `args`, `save_as` | JSON result/stdout/stderr | writes temp file, executes interpreter | `com.azhukov.agent.tools.terminal.CodeExecutionTool` | **defer** |
| (process tracking) | `process_registry.py` | `ProcessRegistry`, background session tracking | — | — | manages subprocess lifecycle | `com.azhukov.agent.tools.terminal.ProcessRegistry` | **port** |

**MVP notes:**

- Terminal execution and background-process monitoring are required for the agent to run builds/tests.
- `execute_code` is valuable but can be implemented later as a convenience wrapper around `terminal`.

---

## 4. Web Tools

**Source files:** `web_tools.py`, `url_safety.py`

| Tool name | File | Python handler / schema | Argument schema (key fields) | Return type | Side effects | Java package | Decision |
|-----------|------|------------------------|------------------------------|-------------|--------------|--------------|----------|
| `web_search` | `web_tools.py` | `WEB_SEARCH_SCHEMA`, `web_search_tool` | `query`, `num_results`, `recency_days`, `source` | JSON results | HTTP calls to search APIs | `com.azhukov.agent.tools.web.WebSearchTool` | **port** |
| `fetch_url` | `web_tools.py` | `FETCH_URL_SCHEMA`, `fetch_url_tool` | `url`, `max_length`, `include_links`, `raw` | JSON content/markdown | HTTP fetch | `com.azhukov.agent.tools.web.UrlFetchTool` | **port** |
| `website_policy` | `web_tools.py` | internal access checker | — | block dict or None | none | `com.azhukov.agent.tools.web.WebsitePolicy` | **port** |
| `url_safety` | `url_safety.py` | SSRF/private-IP safety helpers | — | bool | none | `com.azhukov.agent.tools.web.UrlSafety` | **port** |

**MVP notes:**

- Web search and URL fetch are core to many agent workflows; port them.
- URL safety / SSRF protection should be ported alongside fetch.

---

## 5. Browser Tools

**Source files:** `browser_tool.py`, `browser_cdp_tool.py`, `browser_dialog_tool.py`

| Tool name | File | Python handler / schema | Argument schema (key fields) | Return type | Side effects | Java package | Decision |
|-----------|------|------------------------|------------------------------|-------------|--------------|--------------|----------|
| `browser` | `browser_tool.py` | `BROWSER_SCHEMA`, `browser_tool` | `command` (`open`/`snapshot`/`click`/`fill`/`scroll`/`eval`/`screenshot`/`close`/`...`), `url`, `session_id`, `coordinate`, `text`, etc. | JSON page state / screenshot / result | drives browser via `agent-browser` | `com.azhukov.agent.tools.browser.BrowserTool` | **defer** |
| (CDP override) | `browser_cdp_tool.py` | CDP backend helper | — | — | connects to existing browser | `com.azhukov.agent.tools.browser.BrowserCdpBackend` | **defer** |
| (dialog) | `browser_dialog_tool.py` | JS dialog handler | `session_id`, `accept`, `prompt_text` | JSON status | interacts with browser dialog | `com.azhukov.agent.tools.browser.BrowserDialogTool` | **defer** |

**MVP notes:**

- Browser automation is large and depends on an external `agent-browser` daemon; defer to post-MVP.
- The registry can reserve the `browser` tool name, but the implementation should come later.

---

## 6. Vision / Computer-Use Tools

**Source files:** `vision_tools.py`, `computer_use_tool.py`, `computer_use/schema.py`, `computer_use/tool.py`

| Tool name | File | Python handler / schema | Argument schema (key fields) | Return type | Side effects | Java package | Decision |
|-----------|------|------------------------|------------------------------|-------------|--------------|--------------|----------|
| `vision_analyze` | `vision_tools.py` | `VISION_ANALYZE_SCHEMA`, `_handle_vision_analyze` | `image_url`, `question` | JSON analysis / multimodal image envelope | downloads image, calls aux vision model | `com.azhukov.agent.tools.vision.VisionAnalyzeTool` | **defer** |
| `video_analyze` | `vision_tools.py` | `VIDEO_ANALYZE_SCHEMA`, `_handle_video_analyze` | `video_url`, `question` | JSON analysis | downloads video, calls aux video model | `com.azhukov.agent.tools.vision.VideoAnalyzeTool` | **defer** |
| `computer_use` | `computer_use_tool.py` (shim) + `computer_use/schema.py`, `computer_use/tool.py` | `COMPUTER_USE_SCHEMA`, `handle_computer_use` | `action` (`capture`/`click`/`type`/`scroll`/...), `coordinate`, `element`, `text`, etc. | JSON screen state/result | controls macOS desktop via `cua-driver` | `com.azhukov.agent.tools.computer.ComputerUseTool` | **skip** (macOS-only external dependency) |

**MVP notes:**

- Vision and video analysis depend on a multimodal auxiliary LLM client; defer.
- `computer_use` is macOS-only and requires `cua-driver`; skip for the Java MVP.

---

## 7. Memory / Todo / Skill Tools

**Source files:** `memory_tool.py`, `todo_tool.py`, `skills_tool.py`, `skill_manager_tool.py`, `kanban_tools.py`

| Tool name | File | Python handler / schema | Argument schema (key fields) | Return type | Side effects | Java package | Decision |
|-----------|------|------------------------|------------------------------|-------------|--------------|--------------|----------|
| `memory` | `memory_tool.py` | `MEMORY_SCHEMA`, `memory_tool` | `action` (`read`/`write`/`append`/`search`/`delete`), `key`, `content`, `limit` | JSON memory data | reads/writes `MEMORY.md`/session memory | `com.azhukov.agent.tools.memory.MemoryTool` | **port** |
| `todo` | `todo_tool.py` | `TODO_SCHEMA`, `todo_tool` | `action` (`list`/`add`/`complete`/`remove`/`clear`), `item`, `index` | JSON todo list | reads/writes `TODO.md` | `com.azhukov.agent.tools.memory.TodoTool` | **port** |
| `skills` | `skills_tool.py` | `SKILLS_SCHEMA`, `skills_tool` | `action` (`list`/`load`/`unload`/`search`/`info`), `name`, `query` | JSON skill info | changes loaded skills in session | `com.azhukov.agent.tools.memory.SkillsTool` | **port** |
| `skill_manager` | `skill_manager_tool.py` | installs/removes community skills | `action` (`install`/`remove`/`list`/`update`), `name`, `source` | JSON status | writes skill files | `com.azhukov.agent.tools.memory.SkillManagerTool` | **defer** |
| `kanban_*` | `kanban_tools.py` | `kanban_complete`, `kanban_status` | `task_id`, `result`, `status` | JSON status | updates kanban board | `com.azhukov.agent.tools.memory.KanbanTool` | **skip** (kanban subsystem) |

**MVP notes:**

- `memory`, `todo`, and `skills` are lightweight state-management tools; port them.
- `skill_manager` (install/remove skills) can be deferred.
- Kanban integration is out of MVP scope.

---

## 8. MCP / Delegate / Gateway Tools

**Source files:** `mcp_tool.py`, `delegate_tool.py`, `send_message_tool.py`

| Tool name | File | Python handler / schema | Argument schema (key fields) | Return type | Side effects | Java package | Decision |
|-----------|------|------------------------|------------------------------|-------------|--------------|--------------|----------|
| MCP tools (dynamic) | `mcp_tool.py` | `discover_mcp_tools()`, `register_mcp_servers()` | per-server OpenAI schemas, prefixed `mcp_<server>_<tool>` | per-server return types | calls external MCP servers | `com.azhukov.agent.tools.mcp.McpToolProvider` | **defer** |
| `delegate_task` | `delegate_tool.py` | `DELEGATE_TASK_SCHEMA`, `delegate_task` | `goal`, `context`, `toolsets`, `tasks`, `max_iterations`, `role`, `acp_command` | JSON subagent summaries | spawns child AI agents | `com.azhukov.agent.tools.delegate.DelegateTool` | **defer** |
| `send_message` | `send_message_tool.py` | `SEND_MESSAGE_SCHEMA`, `send_message_tool` | `action` (`send`/`list`/`react`/`unreact`), `target`, `message`, `emoji`, `message_id` | JSON send status | sends messages to messaging platforms | `com.azhukov.agent.tools.messaging.SendMessageTool` | **defer** |

**MVP notes:**

- MCP dynamic tool discovery is important but can be added after the core tool registry is stable.
- Subagent delegation (`delegate_task`) and cross-platform messaging (`send_message`) are advanced features; defer.

---

## 9. Media / Utility / Platform-Specific Tools

**Source files:** `image_generation_tool.py`, `video_generation_tool.py`, `tts_tool.py`, `transcription_tools.py`, `cronjob_tools.py`, `session_search_tool.py`, `x_search_tool.py`, `homeassistant_tool.py`, `discord_tool.py`, `feishu_doc_tool.py`, `feishu_drive_tool.py`, `yuanbao_tools.py`, `qrcode_tool.py`, `clarify_tool.py`, `mixture_of_agents_tool.py`, `managed_tool_gateway.py`, `tool_backend_helpers.py`

| Tool name | File | Python handler / schema | Argument schema (key fields) | Return type | Side effects | Java package | Decision |
|-----------|------|------------------------|------------------------------|-------------|--------------|--------------|----------|
| `image_gen` | `image_generation_tool.py` | `IMAGE_GEN_SCHEMA`, `image_gen_tool` | `prompt`, `model`, `aspect_ratio`, etc. | JSON image URL/path | calls FAL/Nous image API | `com.azhukov.agent.tools.media.ImageGenerationTool` | **defer** |
| `video_gen` | `video_generation_tool.py` | `VIDEO_GEN_SCHEMA` | `prompt`, `model`, `aspect_ratio`, etc. | JSON video URL/path | calls FAL video API | `com.azhukov.agent.tools.media.VideoGenerationTool` | **skip** |
| `tts` | `tts_tool.py` | `TTS_SCHEMA`, `tts_tool` | `text`, `voice`, `speed`, `model` | JSON audio path/URL | calls TTS API | `com.azhukov.agent.tools.media.TtsTool` | **skip** |
| `transcribe` | `transcription_tools.py` | `TRANSCRIBE_SCHEMA` | `audio_url`/`audio_path`, `language` | JSON transcript | calls Whisper API | `com.azhukov.agent.tools.media.TranscriptionTool` | **skip** |
| `cronjob` | `cronjob_tools.py` | `CRONJOB_SCHEMA`, `cronjob` | `action` (`create`/`list`/`pause`/`resume`/`remove`/`trigger`/`update`), `schedule`, `prompt`, `name` | JSON job status | reads/writes cron job JSON | `com.azhukov.agent.tools.scheduler.CronjobTool` | **defer** |
| `session_search` | `session_search_tool.py` | `SESSION_SEARCH_SCHEMA`, `session_search` | `query`, `session_id`, `around_message_id`, `window`, `limit` | JSON sessions/messages | reads session DB | `com.azhukov.agent.tools.memory.SessionSearchTool` | **defer** |
| `x_search` | `x_search_tool.py` | X/Twitter search | `query`, `count`, `recency` | JSON tweets | HTTP API | `com.azhukov.agent.tools.web.XSearchTool` | **skip** |
| `homeassistant` | `homeassistant_tool.py` | Home Assistant control | `action`, `entity_id`, `service`, `state` | JSON status | calls HA API | `com.azhukov.agent.tools.iot.HomeAssistantTool` | **skip** |
| `discord` | `discord_tool.py` | Discord operations | `action`, `channel`, `message` | JSON status | calls Discord API | `com.azhukov.agent.tools.messaging.DiscordTool` | **skip** |
| Feishu tools | `feishu_doc_tool.py`, `feishu_drive_tool.py` | Feishu doc/drive operations | doc/drive IDs, content | JSON status | calls Feishu API | `com.azhukov.agent.tools.messaging.FeishuTool` | **skip** |
| Yuanbao tools | `yuanbao_tools.py` | Yuanbao group/DM operations | `action`, `group_code`, `message` | JSON status | calls Yuanbao adapter | `com.azhukov.agent.tools.messaging.YuanbaoTool` | **skip** |
| `qrcode` | `qrcode_tool.py` | QR code generation | `text` | image | generates QR image | `com.azhukov.agent.tools.media.QrCodeTool` | **skip** |
| `clarify` | `clarify_tool.py` | asks user for clarification | `question` | JSON answer | blocks for user input | `com.azhukov.agent.tools.interaction.ClarifyTool` | **defer** |
| `mixture_of_agents` | `mixture_of_agents_tool.py` | parallel model ensemble | `prompt`, `models`, `aggregator` | JSON result | multiple LLM calls | `com.azhukov.agent.tools.delegate.MixtureOfAgentsTool` | **skip** |
| `managed_tool_gateway` | `managed_tool_gateway.py` | routes to managed Nous tool gateway | — | — | HTTP proxy to tool backend | `com.azhukov.agent.tools.gateway.ManagedToolGateway` | **skip** |
| `tool_backend_helpers` | `tool_backend_helpers.py` | resolves FAL/managed gateway config | — | — | reads env/config | `com.azhukov.agent.tools.gateway.ToolBackendConfig` | **defer** |

**MVP notes:**

- Media generation, transcription, IoT, and platform-specific messaging tools are out of MVP scope.
- `session_search` and `cronjob` are useful but not required for the first working Java agent.
- `clarify` (interactive user prompt) can be deferred until the Java MVP has a UI or gateway.

---

## 10. Model Tools

**Source files:** `model_tools.py`

| Tool name | File | Python handler / schema | Argument schema (key fields) | Return type | Side effects | Java package | Decision |
|-----------|------|------------------------|------------------------------|-------------|--------------|--------------|----------|
| `model_info` / `set_model` | `model_tools.py` | small dynamic model helpers | `provider`, `model` | JSON info | changes runtime model | `com.azhukov.agent.tools.model.ModelTool` | **defer** |

---

## 11. Summary Matrix

| Category | Tools to port now | Defer | Skip |
|----------|-------------------|-------|------|
| **Framework** | registry, loader, tool_error, process registry | dynamic schema overrides | — |
| **File** | read, write, patch, search, delete | file_exists, debug logging | create_artifact |
| **Terminal/Code** | terminal, read_terminal, process registry | execute_code | — |
| **Web** | web_search, fetch_url, url_safety, website_policy | — | — |
| **Browser** | — | browser, browser_cdp, browser_dialog | — |
| **Vision/Computer** | — | vision_analyze, video_analyze | computer_use |
| **Memory/Todo/Skills** | memory, todo, skills | skill_manager | kanban |
| **MCP/Delegate/Gateway** | — | MCP provider, delegate_task, send_message | — |
| **Media/Utility** | — | image_gen, cronjob, session_search, clarify | video_gen, tts, transcribe, x_search, HA, discord, Feishu, Yuanbao, qrcode, mixture_of_agents, managed gateway |

---

## 12. Recommended Java Package Structure

```text
com.azhukov.agent.tools
├── ToolRegistry.java
├── ToolLoader.java
├── ToolResult.java
├── ToolHandler.java
├── file
│   ├── FileReadTool.java
│   ├── FileWriteTool.java
│   ├── FilePatchTool.java
│   ├── FileSearchTool.java
│   ├── FileDeleteTool.java
│   └── FileOperations.java
├── terminal
│   ├── ShellTool.java
│   ├── ProcessMonitorTool.java
│   ├── CodeExecutionTool.java
│   └── ProcessRegistry.java
├── web
│   ├── WebSearchTool.java
│   ├── UrlFetchTool.java
│   ├── UrlSafety.java
│   └── WebsitePolicy.java
├── browser
│   └── BrowserTool.java         # deferred
├── vision
│   ├── VisionAnalyzeTool.java   # deferred
│   └── VideoAnalyzeTool.java    # deferred
├── memory
│   ├── MemoryTool.java
│   ├── TodoTool.java
│   ├── SkillsTool.java
│   ├── SessionSearchTool.java   # deferred
│   └── SkillManagerTool.java    # deferred
├── mcp
│   └── McpToolProvider.java     # deferred
├── delegate
│   └── DelegateTool.java        # deferred
├── messaging
│   └── SendMessageTool.java     # deferred
├── scheduler
│   └── CronjobTool.java         # deferred
├── media
│   └── ImageGenerationTool.java # deferred
└── model
    └── ModelTool.java           # deferred
```

---

## 13. Open Questions / Risks

1. **Async/sync handler model:** Python supports both `is_async=True/False`. The Java tool registry should expose a uniform asynchronous interface (e.g., `CompletableFuture<String>`) even for synchronous tools.
2. **Dynamic schema overrides:** `delegate_task` rebuilds part of its schema at `get_definitions()` time. Java should support optional schema transformers per tool.
3. **Tool availability gating:** Port `check_fn` behavior so that tools only appear in the model's function list when prerequisites are met (env vars, config, provider, gateway running, etc.).
4. **Toolset concept:** Java MVP should keep toolset grouping so the agent can enable/disable tool groups without listing individual tool names.
5. **Multimodal results:** `vision_analyze` can return a native image envelope for vision models. The Java layer needs a tool-result abstraction that can carry text or binary attachments.
6. **External binary dependencies:** `terminal`, `browser`, `computer_use`, and `execute_code` rely on external programs (`bash`, `agent-browser`, `cua-driver`, interpreters). Java MVP should assume these are pre-installed and shell out via `ProcessBuilder`.

---

**Generated:** 2026-07-26
