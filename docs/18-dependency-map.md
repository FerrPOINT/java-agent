# the original agent — Python → Java/Maven Dependency/Technology Mapping

> Scope: core runtime dependencies declared in `prototype/python-agent/pyproject.toml`
> and the technology categories used by the agent loop, model transports, browser,
> terminal, web tools, resilience, SSE, and configuration layers.

This table maps the Python packages and libraries used by the original agent to
commonly-used Java/Maven equivalents. It focuses on the stack areas called out in
the porting brief: model providers (OpenAI-compatible, Ollama, Anthropic),
browser CDP control, web fetching/HTML parsing, terminal/PTY handling,
resilience/retry, server-sent streaming, and config/env loading.

Where no single de-facto equivalent exists, the table lists the closest options
with notes on the trade-off.

## 1. Model provider clients / AI SDKs

| Python original package | Purpose in the original | Java/Maven equivalent(s) | Notes |
|---|---|---|---|
| `openai==2.24.0` | OpenAI Chat Completions and Responses API client; used for OpenAI-compatible endpoints (OpenRouter, Ollama Cloud, DeepSeek, xAI, Kimi, NVIDIA, etc.) via transport `chat_completions` and `codex_responses`. | **OpenAI Java client** `com.openai:openai-java` (official); **OpenAI4J**; or generic `okhttp` / `java.net.http` + Jackson. | The Python SDK is a thin typed wrapper around REST/JSON. A Java port can use the official client for OpenAI endpoints, or a generic HTTP+JSON layer for OpenAI-compatible providers. |
| `anthropic==0.87.0` (extra `[anthropic]`, lazy-installed) | Native Anthropic Messages API (`api_mode=anthropic_messages`). | **Anthropic Java SDK** — currently not official; use `okhttp` / Apache HttpClient + Jackson with Anthropic's REST spec (`/v1/messages`). | Build request/response DTOs manually or generate from Anthropic's OpenAPI spec. |
| Custom/Ollama provider (`plugins/model-providers/custom`, `plugins/model-providers/ollama-cloud`) | OpenAI-compatible `/v1/chat/completions` plus Ollama-specific extras (`num_ctx`, `reasoning_effort`, `think`). | **Ollama Java client** `io.github.ollama4j:ollama4j` (community); or generic HTTP client hitting `/api/chat` and `/v1/chat/completions`. | Ollama exposes an OpenAI-compatible layer, so the OpenAI Java client can target it with a custom `baseUrl`. |
| `boto3==1.42.89` (extra `[bedrock]`) | AWS Bedrock runtime and credential handling. | **AWS SDK for Java v2** `software.amazon.awssdk:bedrockruntime` + `auth`. | Official SDK covers streaming and inference profiles. |
| `google-auth==2.55.1` / `google-api-python-client` (extra `[vertex]`/`[google]`) | Google Vertex AI and Workspace OAuth2/API calls. | **Google Auth Library for Java** `com.google.auth:google-auth-library-oauth2-http`; **Google API Client Library for Java**. | Use `GoogleCredentials` + `HttpTransport`. |
| `azure-identity==1.25.3` (extra `[azure-identity]`) | Azure Entra ID / token credential acquisition. | **Azure Identity for Java** `com.azure:azure-identity`. | Provides `DefaultAzureCredential`, `ClientSecretCredential`, etc. |
| `mistralai==2.4.8` (extra `[mistral]`, lazy) | Mistral AI API (STT/TTS and chat). | **Mistral AI Java client** (community) or generic HTTP + Jackson. | As of this writing there is no first-party Java SDK; hand-roll DTOs. |

## 2. Transports / normalization / message schemas

| Python original | Purpose | Java/Maven equivalent(s) | Notes |
|---|---|---|---|
| `pydantic==2.13.4` | Request/response validation, typed models, JSON serialization in transports, tool schemas, provider profiles. | **Jackson** `com.fasterxml.jackson.core:jackson-databind` + **Bean Validation (Jakarta)** `jakarta.validation:jakarta.validation-api` / `org.hibernate.validator:hibernate-validator`. | Replace Pydantic models with POJOs/records + Jackson annotations. Validation rules map to Jakarta Bean Validation. |
| `jinja2==3.1.6` | Prompt templating, system prompt rendering, skill markdown generation. | **Pebble** `io.pebbletemplates:pebble`; **FreeMarker** `org.freemarker:freemarker`; **Thymeleaf** `org.thymeleaf:thymeleaf`. | Pebble has Django/Jinja-like syntax and is the closest drop-in. |
| `packaging==26.0` | Version parsing/comparison for provider/model minimum-version checks. | **Maven Artifact** `org.apache.maven:maven-artifact` provides `DefaultArtifactVersion` / `VersionRange`; or **Gradle** internals if porting to Gradle. | Only needed if The original keeps runtime version-constraint logic. |

## 3. Browser / CDP

| Python original | Purpose | Java/Maven equivalent(s) | Notes |
|---|---|---|---|
| `websockets==15.0.1` | Persistent WebSocket to the Chrome DevTools Protocol (CDP) endpoint; used by `tools/browser_supervisor.py` for dialog/frame detection. | **Java-WebSocket** `org.java-websocket:Java-WebSocket`; **Tyrus** (JSR 356); **Reactor Netty** `io.projectreactor.netty:reactor-netty` (WebSocket client); or built-in JDK HTTP client WebSocket. | Java-WebSocket is the simplest Tyrus/Reactor Netty integrate with Jakarta/WebFlux stacks. |
| CDP protocol itself | Page/Runtime/Target/Fetch domain calls to control Chromium. | **CDP4J** `io.webfolder:cdp4j`; **Selenium Chrome DevTools** `org.seleniumhq.selenium:selenium-devtools`; or hand-rolled JSON-RPC over WebSocket. | The original does *not* use Playwright/Selenium for control — it talks CDP directly. CDP4J or Selenium DevTools preserve that design. |

## 4. Web fetching / HTML parsing

| Python original | Purpose | Java/Maven equivalent(s) | Notes |
|---|---|---|---|
| `httpx[socks]==0.28.1` | Modern async/sync HTTP client with SOCKS proxy support; used across gateway, auxiliary client, provider probes, web tools. | **OkHttp** `com.squareup.okhttp3:okhttp`; **Apache HttpClient 5** `org.apache.httpcomponents.client5:httpclient5`; **Java 11+ `java.net.http.HttpClient`**. | OkHttp is closest in ergonomics to `httpx` (connection pooling, async, interceptors). Apache HttpClient 5 supports SOCKS proxies well. |
| `requests==2.33.0` | Legacy/secondary sync HTTP client. | **OkHttp**, **Apache HttpClient 5**, or **Spring `RestTemplate`/`WebClient`**. | Prefer consolidating on OkHttp or `HttpClient` rather than carrying two clients. |
| `firecrawl-py==4.17.0` (extra `[firecrawl]`) | Web search/scrape via Firecrawl. | Use Firecrawl REST API directly with OkHttp/Apache HttpClient + Jackson. | No first-party Java SDK; DTOs are small. |
| `exa-py==2.10.2` (extra `[exa]`) | Exa AI web search API. | Call Exa REST API directly with OkHttp/HttpClient + Jackson. | |
| `parallel-web==0.4.2` (extra `[parallel-web]`) | Parallel web search API. | Call Parallel REST API directly. | |
| *No `bs4`/`lxml` in core* | the original delegates HTML-to-markdown extraction to Firecrawl/Parallel/Exa/Tavily backends. | If a Java port needs local parsing: **Jsoup** `org.jsoup:jsoup`. | Jsoup is the canonical Java HTML parser and aligns with the task brief's mention of `jsoup`. |

## 5. Terminal / PTY / process management

| Python original | Purpose | Java/Maven equivalent(s) | Notes |
|---|---|---|---|
| `ptyprocess>=0.7.0` (POSIX) | Spawn and drive pseudo-terminal subprocesses for the terminal tool. | **JNA/JTerm** or **Apache Commons Exec** `org.apache.commons:commons-exec`; for true PTY on Unix use **JNA** `net.java.dev.jna:jna` to call `posix_openpt`/`forkpty`. | Full PTY semantics require native calls; a simpler port can use `ProcessBuilder` + stream pumps for non-interactive commands. |
| `pywinpty>=2.0.0` (Windows) | Windows pseudo-terminal support. | **JNA** to call Windows `CreatePseudoConsole` / `ConPTY` APIs; or use **WinPty** JNI bindings (rare). | Windows PTY is the hardest part of a Java port. Consider starting with `ProcessBuilder` and documenting PTY as a later enhancement. |
| `psutil==7.2.2` | Cross-platform process/PID status, tree walking, alive checks. | **OSHI** `com.github.oshi:oshi-core`; **Apache Commons IO / Lang** for basics; **Java Process API** (`ProcessHandle`, `RuntimeMXBean`). | OSHI covers most `psutil` use cases (CPU, memory, processes, disks). |
| `pywin32>=306` (Windows) | Win32 security/file APIs for desktop SSH runtime. | **JNA Platform** `net.java.dev.jna:jna-platform`. | |

## 6. Resilience / retry / circuit-breaker

| Python original | Purpose | Java/Maven equivalent(s) | Notes |
|---|---|---|---|
| `tenacity==9.1.4` | Declarative retry, backoff, jitter, timeout/retry predicates. | **Resilience4j** `io.github.resilience4j:resilience4j-retry` (+ `resilience4j-circuitbreaker`, `resilience4j-ratelimiter`); **Failsafe** `dev.failsafe:failsafe`; **Spring Retry**. | Resilience4j is the de-facto standard for Java. The original uses custom retry logic in `agent_runtime_helpers.py` and `conversation_loop.py` around provider calls, so Resilience4j Retry + CircuitBreaker map cleanly. |
| Custom retry loops (`conversation_loop`, `agent_runtime_helpers`) | Provider failover, rate-limit handling with `Retry-After`, credential rotation, stale-call circuit breaker. | Resilience4j Retry + CircuitBreaker + RateLimiter; or hand-rolled `ScheduledExecutorService` with backoff. | The *logic* (fallback chain, credential exhaustion, cooldowns) must be reimplemented; the library only supplies the primitives. |

## 7. Streaming / SSE / async runtime

| Python original | Purpose | Java/Maven equivalent(s) | Notes |
|---|---|---|---|
| `asyncio` (stdlib) + `asyncio.Queue` | Concurrent agent loop, gateway SSE pumps, CDP supervisor event loop. | **Project Reactor** `io.projectreactor:reactor-core`; **RxJava** `io.reactivex.rxjava3:rxjava`; **Java `CompletableFuture`** + `java.util.concurrent.Flow`; or **Vert.x**. | The original is heavily async. Reactor or Vert.x are the closest idiomatic Java equivalents. |
| Custom SSE handlers (`gateway/platforms/api_server.py`, `tools/mcp_tool.py`) | Server-sent events for API server streaming and MCP SSE transport. | **Spring WebFlux** `spring-webflux` + `MediaType.TEXT_EVENT_STREAM`; **Reactor Netty** manual `text/event-stream` framing; **JAX-RS SSE** `SseEventSink`. | The original currently builds SSE frames manually (`data: ...\n\n`). Spring/Reactor Netty preserve that or use higher-level SSE annotations. |
| `sse-starlette` (transitive via `mcp==1.26.0`) | MCP server SSE transport. | **MCP Java SDK** (when available) or use Spring WebFlux/Reactor Netty to expose SSE endpoints and an HTTP+JSON MCP client. | The Java MCP ecosystem is evolving; plan for both a Java MCP SDK and a fallback HTTP client implementation. |

## 8. Config / env / secrets

| Python original | Purpose | Java/Maven equivalent(s) | Notes |
|---|---|---|---|
| `pyyaml==6.0.3` + `ruamel.yaml==0.18.17` | Load `config.yaml`, write config atomically while preserving comments (`ruamel`), fast safe loading (`pyyaml`). | **SnakeYAML** `org.yaml:snakeyaml` for load/dump. For comment-preserving round-trip edits: **SnakeYAML Engine** with its low-level event API, or accept that comment preservation is hard and rewrite the file from a loaded model. | The original relies on atomic, comment-preserving edits in `cli/config.py`. A Java port should either use SnakeYAML's Emitter/Composer events or document that comments may be lost on programmatic edits. |
| `python-dotenv==1.2.2` | Load `.env` secrets with override/fallback order. | **dotenv-java** `io.github.cdimascio:dotenv-java`; or hand-rolled parser in ~30 lines. | The original has its own layered loader (`cli/env_loader.py`) that calls `python-dotenv`; dotenv-java is a close fit. |
| Custom `config.yaml` loader (`cli/config.py`, `utils.py`) | Layered config with defaults, user overrides, env substitution, corrupt-config backup. | **Typesafe Config (Lightbend)** `com.typesafe:config` for HOCON-style layering; or Spring Boot `application.yml`/`application.properties` with profiles. | If the Java port keeps YAML, SnakeYAML + a small defaults/merge layer is enough. If moving to HOCON, Typesafe Config gives the merge semantics for free. |

## 9. Web server / gateway / API surface

| Python original | Purpose | Java/Maven equivalent(s) | Notes |
|---|---|---|---|
| `fastapi>=0.104.0` + `uvicorn[standard]` | Dashboard / web API server (`agent dashboard`). | **Spring Boot** `spring-boot-starter-web` (Tomcat) or `spring-boot-starter-webflux` (Netty); **Quarkus** `quarkus-resteasy-reactive`; **Javalin** `io.javalin:javalin`; **Micronaut**. | FastAPI maps most naturally to Spring Boot WebFlux or Javalin for small surface area. |
| `python-multipart>=0.0.9` | Multipart uploads for dashboard file manager. | Included in Spring/Jakarta servlet containers; or **Apache Commons FileUpload** `commons-fileupload`. | |
| `aiohttp==3.14.1` (messaging extra) | Gateway webhook/platform servers and HTTP client. | **Spring WebFlux** / **Reactor Netty**; **Vert.x Web**; **Javalin**. | aiohttp is used for the gateway's Telegram/Discord/Slack/etc. platform adapters and the API server in non-web builds. |
| `starlette==1.0.1` (mcp/web/dev extras) | ASI app framework used by FastAPI and MCP SSE. | **Spring WebFlux**; **Reactor Netty**; **Javalin**; **Quarkus**. | |

## 10. Security / crypto / JWT

| Python original | Purpose | Java/Maven equivalent(s) | Notes |
|---|---|---|---|
| `PyJWT[crypto]==2.13.0` | GitHub App JWT auth for Skills Hub. | **Java JWT (Auth0)** `com.auth0:java-jwt`; **Nimbus JOSE+JWT** `com.nimbusds:nimbus-jose-jwt`; **jjwt** `io.jsonwebtoken:jjwt-api/impl/jackson`. | Nimbus is the most complete for JWS/JWE/EC. |
| `cryptography==46.0.7` | Crypto primitives for WeCom/Weixin and JWT signing. | **BouncyCastle** `org.bouncycastle:bcprov-jdk18on` + `bcpkix-jdk18on`; **Java JCA** built-ins. | |
| `certifi==2026.5.20` | CA bundle for TLS certificate validation. | Use the JVM default truststore (`cacerts`) or embed **Mozilla CA bundle** via `org.mozilla:jss` / small custom loader. | Java already has a truststore; `certifi` mainly matters for the Python stack. |

## 11. CLI / TUI / misc

| Python original | Purpose | Java/Maven equivalent(s) | Notes |
|---|---|---|---|
| `prompt_toolkit==3.0.52` | Interactive CLI input, completion, key bindings, history. | **JLine** `org.jline:jline`; **picocli** `info.picocli:picocli` for the CLI framework. | picocli handles commands/options; JLine handles interactive terminal input. |
| `rich==14.3.3` | Colored console output, tables, progress, Markdown rendering. | **picocli** styles; **Jansi** `org.fusesource.jansi:jansi`; **TextIO**/`JLine` for advanced TUI. | For rich terminal UI, consider **Lanterna** `com.googlecode.lanterna:lanterna` (text GUI) or a small web/Electron shell. |
| `fire==0.7.1` | Auto-generated CLI from functions (used in scripts/one-offs). | **picocli** is the closest equivalent for generating commands from methods. | |
| `croniter==6.0.0` | Cron/interval expression parsing for scheduled jobs. | **Quartz Scheduler** `org.quartz-scheduler:quartz`; **cron-utils** `com.cronutils:cron-utils`. | cron-utils parses *nix cron expressions; Quartz handles scheduling. |
| `Markdown==3.10.2` | Markdown → HTML for Matrix/formatted messages. | **CommonMark Java** `org.commonmark:commonmark`; **Flexmark** `com.vladsch.flexmark:flexmark-all`. | |
| `Pillow==12.2.0` | Image resize/codec prep for vision tools. | **TwelveMonkeys ImageIO** `com.twelvemonkeys.imageio:imageio-core` + format plugins; **Imgscalr** `org.imgscalr:imgscalr-lib` for resizing; or **Java 2D** built-ins. | Java 2D handles basic resize/encode for JPEG/PNG; add TwelveMonkeys for WebP/HEIF support. |
| `pathspec==1.1.1` | `.gitignore`-style path matching for desktop build stamping. | **JGit** `org.eclipse.jgit:org.eclipse.jgit` (has ignore parsing); or implement a tiny glob/regex matcher. | |
| `concurrent-log-handler==0.9.29` (Windows) | Cross-process log rotation with file locking. | **Logback** `ch.qos.logback:logback-classic` with `RollingFileAppender` + `prudent` mode; **Log4j2** `org.apache.logging.log4j:log4j-core` with file locking. | |

## 12. Notable architectural takeaways for the Java port

1. **Provider model** — The original splits provider declarations (`ProviderProfile`)
   from transport format conversion (`ProviderTransport`) from client lifecycle.
   A Java port should preserve that separation: provider YAML/plugin JSON,
   transport POJOs, and a thin SDK wrapper or generic HTTP client.

2. **OpenAI compatibility is the default path** — most providers (Ollama Cloud,
   OpenRouter, DeepSeek, xAI, Kimi, NVIDIA, etc.) use `api_mode=chat_completions`.
   Prioritize the official OpenAI Java client or a generic HTTP layer that can
   target arbitrary `baseUrl`s.

3. **Anthropic and Codex are special transports** — `anthropic_messages` and
   `codex_responses` need dedicated request/response normalizers. These are
   currently in `agent/transports/anthropic.py`, `agent/anthropic_adapter.py`,
   `agent/transports/codex.py`, and `agent/codex_responses_adapter.py`.

4. **Browser control is CDP-first, not Selenium-first** — the Java equivalent
   should talk CDP over WebSocket (CDP4J or Selenium DevTools), not drive a
   browser through Selenium's high-level API, in order to keep the dialog/frame
   bridge semantics.

5. **Terminal is environment-aware** — local, Docker, SSH, Modal, Daytona, etc.
   A Java port needs an abstraction for execution environment plus a process
   driver. True PTY support requires JNA on both Unix and Windows; an MVP can
   start with `ProcessBuilder`.

6. **Resilience is mostly custom** — The original retry/failover logic lives in the
   conversation loop and `agent_runtime_helpers.py`. Use Resilience4j as the
   library substrate but expect to port the policy code itself.

7. **Config loader is load-bearing** — `cli/config.py` and `utils.py`
   implement atomic writes, comment preservation, env substitution, and corrupt
   config backup. Do not simply call `Yaml.load`; reproduce the safety layers.

8. **Async/queue/SSE patterns are pervasive** — Gateway streaming, CDP event
   loop, and API server SSE all use `asyncio.Queue` + manual event framing. A
   Java port benefits from Project Reactor or Vert.x rather than thread-per-request.

---

*Generated from `prototype/python-agent/pyproject.toml` and source-code analysis
of the provider transports, browser supervisor, terminal tool, web tools,
resilience logic, and config/env layers.*
