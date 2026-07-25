# Hermes CLI / Boot / MCP → Java Porting Summary

**Scope:** Map the Hermes Python CLI bootstrap, entry points, CLI subcommands,
configuration flow, and MCP/ACP server surfaces to a Java implementation using
**Picocli** for CLI parsing, **JLine** for interactive REPL/TUI-like shells,
and **Spring Boot** for long-running server startup. This document lists key
files, entry points, commands, the configuration lifecycle, and a per-area
**port / skip / defer** recommendation.

**Source trees analyzed:**
- `/opt/dev/java-agent/prototype/hermes-agent` (canonical Python target)
- `/opt/dev/hermes-workspace/hermes-agent` (local working copy, used for exact CLI/bootstrap behavior)

---

## 1. Executive summary: port / skip / defer

| Area | Verdict | Rationale |
|------|---------|-----------|
| Console entry points (`hermes`, `hermes-agent`, `hermes-acp`) | **PORT** | Required to run the Java agent from a shell / IDE. Map to `picocli` `@Command` classes + `spring-boot-maven-plugin` executable JAR / `jpackage`. |
| Top-level CLI parser & global flags | **PORT** | Picocli directly replaces `argparse`. Keep the same flag names and precedence for compatibility. |
| Built-in subcommands (`chat`, `setup`, `model`, `gateway`, `mcp`, `sessions`, etc.) | **PORT core / high-frequency first** | There are ~40 subcommands. Port the ones on the critical path (chat, setup, model, gateway, mcp, sessions, config, status, logs, version, update) and defer niche ones. |
| Interactive classic REPL (`cli.py` / `cmd_chat`) | **PORT** | Use JLine3 (`LineReader`) for readline, history, completion. Keep `prompt_toolkit` semantics only where visible to users (slash commands, banner, busy spinner). |
| Modern TUI (`--tui`) | **DEFER** | The TUI is a Node/Electron app launched by the Python CLI. Replacing it is a large UI project; keep `--tui` as a deferred shell-out or skip in v1. |
| Shell completion generation | **PORT** | Picocli has built-in bash/zsh/fish completion generators; low cost. |
| Configuration loading (`config.yaml`, `.env`, profiles) | **PORT** | SnakeYAML + `spring-boot-starter-config` (or a thin `ConfigService`) can replace `hermes_cli.config`. Keep `HERMES_HOME` and profile resolution. |
| Hermes home / profile bootstrap | **PORT** | Small; port `hermes_constants.get_hermes_home()` and profile override ContextVar-equivalent. |
| Windows UTF-8 bootstrap (`hermes_bootstrap.py`) | **PORT** | On Windows set `PYTHONUTF8`/`PYTHONIOENCODING`. Java has no equivalent code-page problem, but document that packaged launches should set `-Dfile.encoding=UTF-8`. |
| Setup script (`setup-hermes.sh`) | **PORT (as Java installer / wrapper)** | Re-implement as a cross-platform bootstrap script/JAR or keep a shell wrapper that downloads the Java runtime. Not in Java code itself. |
| ACP adapter (`acp_adapter`) | **PORT** | ACP is a separate stdio JSON-RPC server. Port the adapter module to a Spring Boot CLI app with a stdio transport. |
| MCP server surface (`mcp_serve.py`) | **PORT** | Hermes exposes messaging conversations as MCP tools. Port to an MCP Java SDK or a small stdio JSON-RPC server. |
| Plugin CLI command discovery | **DEFER** | Python plugins can register CLI subcommands dynamically. Defer until the plugin SPI is ported. |

---

## 2. Entry points & packaging

### 2.1 Python entry points (from `pyproject.toml`)

```toml
[project.scripts]
hermes       = "hermes_cli.main:main"
hermes-agent = "run_agent:main"
hermes-acp   = "acp_adapter.entry:main"
```

### 2.2 Proposed Java entry points

| Binary | Java class | Framework |
|--------|------------|-----------|
| `hermes` | `com.azhukov.agent.cli.HermesCli` | Picocli top-level command + subcommands. For interactive mode, delegates to `ReplShell` (JLine). |
| `hermes-agent` | `com.azhukov.agent.runtime.AgentMain` | Plain `main()` or Spring Boot `CommandLineRunner` that runs a single turn / batch. |
| `hermes-acp` | `com.azhukov.agent.acp.AcpServerMain` | Spring Boot app (or plain `main`) that starts the ACP stdio server. |
| `hermes-mcp` (implied by `hermes mcp serve`) | `com.azhukov.agent.mcp.McpServerMain` | Spring Boot / standalone stdio MCP server. |

### 2.3 Packaging options

- **Executable JAR** via `spring-boot-maven-plugin` (one JAR per entry point, or a single fat JAR with multiple `Main-Class` manifests).
- **`jpackage`** for native `hermes` / `hermes-acp` binaries on Windows/macOS/Linux.
- **Wrapper script** `setup-hermes.sh` / `setup-hermes.ps1` downloads a JRE and the fat JAR, symlinks launch scripts into `~/.local/bin` (mirrors current Python `setup-hermes.sh`).

---

## 3. CLI parser & global flags

### 3.1 Python top-level flags (`hermes_cli/_parser.py`)

Core flags seen before subcommand dispatch:

| Flag | Type | Java mapping |
|------|------|--------------|
| `--version`, `-V` | boolean | `@Option(names={"-V","--version"}, versionHelp=true)` |
| `-z`, `--oneshot` | String | `@Option(names={"-z","--oneshot"}) String oneshot;` |
| `-m`, `--model` | String | `@Option` on top-level command and on `chat` subcommand. |
| `--provider` | String | `@Option` |
| `-t`, `--toolsets` | String (comma-separated) | `@Option` + splitter, or repeated `@Option` with `split=","`. |
| `-r`, `--resume` | String | `@Option` |
| `-c`, `--continue` | String (optional value) | `@Option(names={"-c","--continue"}, arity="0..1")` |
| `-w`, `--worktree` | boolean | `@Option` |
| `--accept-hooks` | boolean | `@Option` |
| `-s`, `--skills` | repeatable String | `@Option` with `arity="1..*"` or repeated. |
| `--yolo` | boolean | `@Option` |
| `--pass-session-id` | boolean | `@Option` |
| `--ignore-user-config` | boolean | `@Option` |
| `--ignore-rules` | boolean | `@Option` |
| `--safe-mode` | boolean | `@Option` |
| `--tui` | boolean | `@Option` (deferred / shell-out) |
| `--cli` | boolean | `@Option` (force classic REPL) |
| `--dev` / `--tui-dev` | boolean | `@Option` (deferred) |
| `-p`, `--profile` | String | **Pre-parser flag**: consumed before Picocli runs, sets `HERMES_HOME` and strips from `sys.argv` equivalent. |

### 3.2 Pre-parser inherited flags

`_parser.py` defines `PRE_ARGPARSE_INHERITED_FLAGS = [("--profile", True), ("-p", True)]`.
These are carried over when the CLI re-execs itself (e.g. after `sessions browse`
selects a session). In Java:

- Store inherited flag values in environment variables or a transient launch state file.
- `LaunchRelauncher` (port of `hermes_cli.relaunch`) records inherited flags before `exec`/`ProcessBuilder` restart.

---

## 4. Subcommand inventory

### 4.1 Built-in subcommands (`_BUILTIN_SUBCOMMANDS` in `main.py`)

```text
acp, auth, backup, bundles, checkpoints, claw, completion, computer-use,
config, cron, curator, dashboard, debug, doctor, dump, fallback, gateway,
hooks, import, insights, gui, desktop, kanban, login, logout, logs, lsp, mcp,
memory, migrate, model, pairing, plugins, portal, postinstall, profile, proxy,
prompt-size, send, sessions, setup, skills, slack, status, tools, uninstall,
update, version, webhook, whatsapp, whatsapp-cloud, chat, secrets, security
```

### 4.2 Java command tree (Picocli)

```java
@Command(
  name = "hermes",
  mixinStandardHelpOptions = true,
  version = "Hermes Agent v...",
  subcommands = {
    ChatCommand.class,
    SetupCommand.class,
    ModelCommand.class,
    GatewayCommand.class,
    McpCommand.class,
    SessionsCommand.class,
    ConfigCommand.class,
    StatusCommand.class,
    LogsCommand.class,
    VersionCommand.class,
    UpdateCommand.class,
    // ... deferred ones can be added later
  }
)
public class HermesCli implements Callable<Integer> { ... }
```

### 4.3 Subcommand port priority

| Priority | Subcommands | Verdict |
|----------|-------------|---------|
| P0 — must have | `chat`, `setup`, `model`, `gateway`, `mcp`, `sessions`, `config`, `status`, `logs`, `version`, `update`, `uninstall`, `completion`, `acp` | **PORT** |
| P1 — common | `auth`, `tools`, `skills`, `memory`, `cron`, `doctor`, `debug`, `backup`, `send`, `fallback`, `profile`, `portal`, `login`, `logout` | **PORT** |
| P2 — specialized | `kanban`, `checkpoints`, `claw`, `migrate`, `webhook`, `whatsapp`, `slack`, `pairing`, `plugins`, `desktop/gui`, `dashboard`, `prompt-size`, `lsp`, `secrets`, `security`, `computer-use`, `bundles`, `curator`, `import`, `insights`, `hooks`, `dump`, `proxy`, `postinstall` | **DEFER** (most depend on other subsystems) |

---

## 5. Configuration flow

### 5.1 Config files

| File | Purpose | Java handling |
|------|---------|---------------|
| `~/.hermes/config.yaml` | All behavioral settings | `ConfigService` using SnakeYAML. Keep a `DEFAULT_CONFIG` map equivalent. |
| `~/.hermes/.env` | API keys / secrets | `DotEnvLoader` loads into `System.getenv()` overlay or a `SecretsStore`. |
| `~/.hermes/SOUL.md` | Identity / personality | Seed on first run; read into system prompt. |
| `~/.hermes/active_profile` | Profile name | `ProfileResolver` reads it before `HERMES_HOME` resolution. |
| `~/.hermes/.install_method` | `git`/`pip`/`docker`/`nixos`/... | `InstallMethodDetector` (port of `detect_install_method`). |
| `~/.hermes/.managed` / `HERMES_MANAGED` | NixOS / Homebrew managed installs | `ManagedModeDetector`. |
| `~/.hermes/.container-mode` | Container exec routing | `ContainerModeRouter` reads file and uses `ProcessBuilder` to exec into container. |

### 5.2 Config load lifecycle

Python (`hermes_cli/config.py`):

1. Resolve `HERMES_HOME` (env > platform default).
2. `ensure_hermes_home()` creates subdirs (`cron`, `sessions`, `logs`, `memories`, `skills`, ...).
3. `load_config()` parses `config.yaml` with fallback to `DEFAULT_CONFIG`.
4. Corrupt YAML is backed up to `config.yaml.corrupt.<ts>.bak`.
5. `.env` is loaded into process env before any API call.

Java equivalent:

1. `HermesHomeResolver.resolve()` returns `Path`.
2. `HermesHomeInitializer.ensureHome()` creates directories with secure POSIX permissions (0700 / 0600), honoring `HERMES_UID`/`HERMES_GID` and managed-mode umask.
3. `ConfigService.load()` returns `Configuration` (map-like object backed by `DEFAULT_CONFIG` + user overrides).
4. `DotEnvLoader.load()` returns key/value map; secrets are kept separate from config.
5. Spring Boot users: expose `Configuration` as a `@Bean` and optionally bind a typed subset with `@ConfigurationProperties`.

### 5.3 Spring Boot startup wiring

```java
@SpringBootApplication
public class HermesApplication {
    public static void main(String[] args) {
        // 1. Bootstrap UTF-8 / process title equivalents before Spring starts.
        System.setProperty("file.encoding", "UTF-8");
        // 2. Resolve HERMES_HOME and load config.
        Path home = HermesHomeResolver.resolve();
        HermesHomeInitializer.ensureHome(home);
        Configuration config = ConfigService.load(home);
        // 3. Run Spring context, registering config bean.
        SpringApplication app = new SpringApplication(HermesApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE); // for CLI mode
        app.addInitializers(ctx -> ctx.getBeanFactory().registerSingleton("hermesConfig", config));
        app.run(args);
    }
}
```

For server modes (`gateway`, `mcp serve`, `acp`), use
`WebApplicationType.SERVLET` or `REACTIVE` only if an HTTP surface is needed.
The ACP/MCP servers use stdio, so they remain `NONE` and implement the JSON-RPC
loop directly.

---

## 6. Interactive REPL (`cli.py` / `cmd_chat`)

### 6.1 Python responsibilities

- `cli.py` builds the fixed-input-area TUI with `prompt_toolkit`:
  - Banner, status bar, busy spinner, markdown rendering, slash-command completion.
  - Bracketed-paste sanitizer, bracketed-paste timeout.
  - File drop / image attach handling.
  - Keyboard shortcuts (`/`, `Enter`, `Ctrl+Enter`, `Shift+Enter`, copy/paste, etc.).
- `hermes_cli/main.py:cmd_chat()` is the argparse dispatch entry; it imports `cli` lazily.

### 6.2 Java mapping

| Python component | Java library / class |
|------------------|----------------------|
| `prompt_toolkit` REPL | **JLine3** `LineReader` + `Terminal` |
| Banner rendering | `BannerPrinter` using ASCII art + color via Jansi or Picocli Ansi |
| Status bar | Custom `StatusBarWidget` (JLine `AttributedString`) |
| Busy spinner | `BusyIndicator` thread updating the status bar |
| Slash command completion | `SlashCommandCompleter` implementing JLine `Completer` backed by `CommandRegistry` |
| Auto-suggest | JLine `AutoSuggestion` or custom completer |
| History | JLine `LineReader` history file (`~/.hermes/.cli_history`) |
| Copy/paste / image attach | AWT `Clipboard` / `DataFlavor` or platform-specific helpers |
| Markdown rendering | Terminal markdown via `commonmark-java` or Picocli markup |

### 6.3 Verdict

- **PORT the classic REPL** with JLine. It is sufficient for v1.
- **DEFER the full fixed-input-area TUI** (the Node/Electron `tui_dist` experience). Keep `--tui` as a shell-out or a no-op with a warning until a real Java TUI (e.g. Lanterna or a separate JavaFX app) is justified.

---

## 7. MCP server surface

### 7.1 Python responsibilities (`mcp_serve.py`)

- Starts a stdio MCP server using `mcp.server.fastmcp.FastMCP`.
- Exposes 10 tools matching OpenClaw's channel bridge:
  - `conversations_list`, `conversation_get`, `messages_read`, `attachments_fetch`,
  - `events_poll`, `events_wait`, `messages_send`,
  - `permissions_list_open`, `permissions_respond`,
  - `channels_list` (Hermes extra).
- Reads from `~/.hermes/sessions/sessions.json`, `channel_directory.json`, and the SQLite `SessionDB`.
- `EventBridge` polls `SessionDB` on a background thread and maintains an in-memory queue with waiter support.

### 7.2 Java mapping

| Python | Java |
|--------|------|
| `FastMCP` server | Use an MCP Java SDK if available (e.g. `modelcontextprotocol` java-sdk or implement stdio JSON-RPC). |
| Stdio transport | Read/write JSON-RPC lines from `System.in` / `System.out`; route logging to `System.err`. |
| SessionDB poller | `EventBridge` thread using JDBC SQLite queries every `POLL_INTERVAL` (200 ms). |
| Tool handlers | `McpTool` interface; each tool maps to a method in `HermesMcpServer`. |
| Config / state access | Inject `SessionDatabase` + `ChannelDirectory` beans. |

### 7.3 Proposed tool classes

```java
package com.azhukov.agent.mcp;

public class HermesMcpServer {
    public ToolResult conversationsList(int limit, String source, String cursor) { ... }
    public ToolResult conversationGet(String sessionKey) { ... }
    public ToolResult messagesRead(String sessionKey, int limit, int offset) { ... }
    public ToolResult attachmentsFetch(String sessionKey, String messageId) { ... }
    public ToolResult eventsPoll(int afterCursor, String sessionKey, int limit) { ... }
    public ToolResult eventsWait(int afterCursor, String sessionKey, int timeoutMs) { ... }
    public ToolResult messagesSend(String sessionKey, String platform, String recipient, String text) { ... }
    public ToolResult permissionsListOpen() { ... }
    public ToolResult permissionsRespond(String requestId, boolean approved) { ... }
    public ToolResult channelsList() { ... }
}
```

**Verdict:** **PORT** the MCP server surface; it is small and has clear
separation from the core agent loop.

---

## 8. ACP adapter surface

### 8.1 Python responsibilities (`acp_adapter/entry.py`, `server.py`)

- `hermes-acp` entry point loads `.env`, configures logging to stderr, parses args, and runs `acp.run_agent(HermesACPAgent())` over stdio.
- `HermesACPAgent` implements ACP lifecycle methods:
  - `initialize`, `authenticate`, `list_sessions`, `load_session`, `new_session`,
    `fork_session`, `send_message`, `send_command`, `set_session_config`,
    `set_session_model`, `set_session_mode`, `get_session_info`, etc.
- Translates ACP resource links / blobs into OpenAI-style content parts.
- Uses a `ThreadPoolExecutor` to run the synchronous `AIAgent` inside asyncio.
- Per-session `SessionManager` holds state and toolsets.

### 8.2 Java mapping

| Python | Java |
|--------|------|
| `acp.run_agent` stdio loop | Spring Boot `CommandLineRunner` or plain `main` with an ACP Java SDK / custom JSON-RPC loop. |
| `HermesACPAgent` | `AcpAgentServer` implementing the ACP schema interfaces. |
| `SessionManager` | `AcpSessionManager` per connection/session. |
| ThreadPoolExecutor | `ExecutorService` or virtual threads (`Executors.newVirtualThreadPerTaskExecutor()` on JDK 21+). |
| Resource link conversion | `AcpResourceConverter` (file URI → Path, image → base64 data URL, text → inline). |
| Logging to stderr | Logback/Log4j2 appender on stderr; silence `httpx`/`openai` noisy libraries. |

**Verdict:** **PORT** ACP adapter after the core agent runtime is ported; it
reuses the same `AgentRuntime` and `SessionDatabase`.

---

## 9. Bootstrap / setup (`hermes_bootstrap.py`, `setup-hermes.sh`)

### 9.1 `hermes_bootstrap.py`

- Sets `PYTHONUTF8=1` and `PYTHONIOENCODING=utf-8` on Windows.
- Reconfigures `sys.stdout`/`stderr`/`stdin` to UTF-8.
- Idempotent; imported at the top of every entry point.

**Java equivalent:**
- No direct code-page issue, but document that packaged launchers set
  `-Dfile.encoding=UTF-8` and `sun.stdout.encoding=UTF-8` on Windows.
- A `Bootstrap` class can set these system properties before `main` continues.

### 9.2 `setup-hermes.sh`

- Detects Termux vs desktop/server.
- Installs/locates `uv`, provisions Python 3.11, creates venv, installs extras,
  seeds `.env`, symlinks `hermes` into `~/.local/bin` / `$PREFIX/bin`, runs setup wizard.

**Java equivalent:**
- Keep a cross-platform `setup-hermes.sh` / `setup-hermes.ps1` wrapper that:
  1. Checks for a JRE (download `jlink` runtime if missing).
  2. Downloads the Hermes Java fat JAR (or uses a local build).
  3. Creates `~/.hermes` and seeds `.env`.
  4. Symlinks/copies launch scripts.
  5. Runs `java -jar hermes-agent.jar setup`.
- Managed installs (NixOS, Homebrew) can skip the wrapper and ship the JAR directly.

**Verdict:** **PORT** the user-facing setup experience; the exact implementation
can remain a shell/PowerShell bootstrap plus a self-contained JAR.

---

## 10. Key Java packages / classes (proposed)

```
com.azhukov.agent.cli
├── HermesCli                    # Picocli top-level @Command
├── ReplShell                    # JLine-based interactive chat loop
├── CompletionCommand            # bash/zsh/fish completion generator
├── LaunchRelauncher             # inherits flags across process restart
└── subcommands
    ├── ChatCommand
    ├── SetupCommand
    ├── ModelCommand
    ├── GatewayCommand
    ├── McpCommand
    ├── SessionsCommand
    ├── ConfigCommand
    ├── StatusCommand
    ├── LogsCommand
    ├── VersionCommand
    ├── UpdateCommand
    ├── UninstallCommand
    └── ... (deferred)

com.azhukov.agent.cli.config
├── ConfigService                # load/save config.yaml, DEFAULT_CONFIG
├── DotEnvLoader                 # load ~/.hermes/.env
├── HermesHomeResolver           # HERMES_HOME / profile resolution
├── HermesHomeInitializer        # create dirs, secure permissions
├── ProfileResolver              # active_profile handling
├── InstallMethodDetector        # git/pip/docker/nixos/homebrew
└── ManagedModeDetector

com.azhukov.agent.cli.commands
├── SlashCommandRegistry         # central /command registry (port of commands.py)
└── SlashCommandCompleter        # JLine completer

com.azhukov.agent.mcp
├── McpServerMain                # Entry point: hermes mcp serve
├── HermesMcpServer              # Tool implementations
├── EventBridge                  # SessionDB polling + waiter queue
└── McpJsonRpcTransport          # stdio JSON-RPC

com.azhukov.agent.acp
├── AcpServerMain                # Entry point: hermes acp
├── AcpAgentServer               # ACP agent implementation
├── AcpSessionManager            # per-session state
└── AcpResourceConverter         # file/image/blob → content parts

com.azhukov.agent.bootstrap
└── Bootstrap                    # UTF-8 / logging / process-title setup
```

---

## 11. Critical invariants

1. **Precedence of `HERMES_HOME`**: env var → platform default; profile override is
   context-local, not process-global.
2. **Config fallback**: a missing or corrupt `config.yaml` must fall back to
   `DEFAULT_CONFIG` and never crash the CLI.
3. **UTF-8 everywhere**: all file I/O, stdio, and subprocess env must use UTF-8.
4. **Subcommand routing**: bare `hermes` defaults to `chat`; `--resume` / `--continue`
   shortcuts route to `chat`.
5. **Inherited flags**: `--profile`, `-p`, and Picocli-tagged `inherit_on_relaunch`
   flags survive a process restart.
6. **MCP/ACP stdio hygiene**: stdout is reserved for protocol JSON; logs go to stderr.
7. **Plugin CLI discovery is lazy**: do not load plugin classes unless the first
   positional argument is not a known built-in subcommand.
8. **Termux fast paths**: optional optimization; not required for correctness.

---

## 12. Migration path

1. Create `HermesCli` with top-level flags and a stub `ChatCommand` that runs a
   single query or the JLine REPL.
2. Port `HermesHomeResolver`, `HermesHomeInitializer`, `ConfigService`, and
   `DotEnvLoader`.
3. Implement P0 subcommands (`chat`, `setup`, `model`, `gateway`, `mcp`, `sessions`,
   `config`, `status`, `logs`, `version`, `update`, `completion`).
4. Add the MCP server module (`HermesMcpServer`) using a small stdio JSON-RPC loop.
5. Add the ACP adapter module once the core `AgentRuntime` is available.
6. Add the wrapper bootstrap script that installs/downloads the JRE + JAR.
7. Back-fill P1/P2 subcommands as the underlying subsystems (gateway, skills,
   kanban, etc.) are ported.

---

## 13. Notes / open questions

- Picocli supports subcommand aliases natively, so `whatsapp-cloud`, `prompt-size`,
  `desktop`/`gui` aliases map cleanly.
- The TUI (`--tui`) is the largest deferred item; decide whether to keep a Node
  TUI shell-out or build a Java TUI later.
- Windows process-title (`setproctitle` / `prctl`) is cosmetic; on Java use
  `ProcessHandle`/`jcmd` naming only where supported.
- Container-mode routing (`get_container_exec_info`) can be implemented in the
  shell wrapper or in `HermesCli` before Picocli parses args.
