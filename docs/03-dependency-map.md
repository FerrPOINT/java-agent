# 03 — Python → Java Dependency Map

Target stack: **Java 25 LTS + Spring Boot 4.1.0 + Gradle 9.6.1 (Groovy DSL) + Groovy 5.0.7 + PostgreSQL 16 + Ollama**.

Agent pins a large dependency tree. This document maps every *core* dependency to a Java alternative. Optional/provider-specific deps are listed but marked out-of-scope.

## 1. Core Dependencies

| Python library | Agent usage | Java replacement | Notes |
|----------------|--------------|--------------------|-------|
| `openai==2.24.0` | Chat completions, embeddings, Responses API | **LangChain4j** `open-ai` / `ollama` modules | First provider is local Ollama; OpenAI-compatible kept as fallback |
| `httpx[socks]==0.28.1` / `requests==2.33.0` | HTTP clients | **Java 11 `java.net.http.HttpClient`** + `OkHttp` for SOCKS/proxy | Virtual threads make HttpClient ergonomic |
| `pydantic==2.13.4` | Validation, serialization, JSON schemas | **Jackson** + **Jakarta Bean Validation** (`hibernate-validator`) | No exact Pydantic clone in Java |
| `jinja2==3.1.6` | System/skill prompt templates | **Pebble** (`io.pebbletemplates:pebble`) | Jinja-like syntax |
| `pyyaml==6.0.3` / `ruamel.yaml` | Config files, skill metadata | **SnakeYAML** | Standard |
| `prompt_toolkit==3.0.52` | Interactive CLI REPL | **JLine** (`org.jline:jline`) + **Picocli** | History, completion |
| `rich==14.3.3` | Colored terminal output | **Jansi** + **SLF4J/Logback** colorizers | Keep minimal |
| `tenacity==9.1.4` | Retry decorators | **Resilience4j** (`resilience4j-retry`) | Lightweight |
| `croniter==6.0.0` | Cron scheduling | **Quartz Scheduler** or Spring `@Scheduled` cron | Quartz for cron expressions |
| `packaging==26.0` | Version parsing | **Maven Artifact** or **JSemVer** | Only if needed |
| `Markdown==3.10.2` | Markdown rendering | **CommonMark** or **Flexmark** | For CLI output |
| `PyJWT[crypto]==2.13.0` | JWT for gateway/auth | **JJWT** or **Nimbus JOSE** | Standard |
| `cryptography==46.0.7` | Crypto ops | **BouncyCastle** (`bcprov-jdk18on`) | Only if needed beyond JJWT |
| `psutil==7.2.2` | Process/system info | **OSHI** or JDK `ProcessHandle` | Use ProcessHandle first |
| `websockets==15.0.1` | WebSocket clients/servers | `java.net.http.WebSocket` | For CDP browser client |
| `fastapi>=0.104.0` + `uvicorn` | API server, gateway webhooks | **Spring Boot 4.1.0** Web MVC + virtual threads | Replaces both |
| `Pillow==12.2.0` | Image processing | **Thumbnailator** or JavaFX | Optional; vision mostly base64 passthrough |
| Browser automation | agent-browser / Playwright | CDP WebSocket client + Selenium chrome-driver launcher | Avoid 200 MB Playwright deps |
| `pathspec==1.1.1` | Gitignore-style matching | `PathMatcher` + small util | Small utility |
| `faster-whisper==1.2.1` + `sounddevice` | Voice transcription | **Whisper.cpp Java bindings** / **Vosk** | Skip for prototype |
| `numpy` | Audio arrays | Java Sound API / TarsosDSP | Skip for prototype |
| `python-telegram-bot` | Telegram gateway | TelegramBots Java library | Defer; keep interface |

## 2. Provider / Optional Integrations (Out of Scope)

| Python library | Agent extra | Java replacement (if ever needed) |
|----------------|--------------|-----------------------------------|
| `anthropic` | `anthropic` | LangChain4j Anthropic module |
| `mistralai` | `mistral` | LangChain4j Mistral module |
| `boto3` | `bedrock` | AWS SDK v2 for Java |
| `google-auth`, `google-api-python-client` | `google` | Google API Client for Java |
| `azure-identity` | `azure-identity` | Azure Identity for Java |
| `honcho-ai` | `honcho` | REST wrapper or skip |
| `supermemory` | `supermemory` | REST wrapper or skip |
| `mem0ai` | `mem0` | REST wrapper or skip |
| `slack-bolt`, `discord.py`, `mautrix` | `messaging` | Slack SDK, Discord4J, Matrix — defer |
| `aiohttp` | `messaging`/`web` | Spring WebClient or `HttpClient` |
| `mcp==1.26.0` | `mcp` | **`io.modelcontextprotocol.sdk:mcp`** |
| `agent-client-protocol==0.9.0` | `acp` | ACP is new; custom or JetBrains lib when stable |
| `firecrawl-py` | `web` | Firecrawl REST API |
| `exa-py` | `exa` | Exa REST API |
| `fal-client` | `fal` | FAL REST API |
| `edge-tts` / `elevenlabs` | `tts-*` | ElevenLabs REST API; Edge TTS skip |
| `modal`, `daytona` | `modal`, `daytona` | Their respective Java/REST APIs |
| `hindsight-client` | `hindsight` | REST wrapper |

## 3. Build / Test / Dev Dependencies

| Python tool | Java replacement |
|-------------|------------------|
| `pytest` + `pytest-asyncio` | **JUnit 5** + **AssertJ** + **Awaitility** |
| `ruff` | **Spotless** + **Checkstyle** |
| `mypy` | **Checker Framework** or strict null checks in Java |
| `uv` / `pip` | **Gradle** |
| `setuptools` entry points | Spring Boot `spring.factories` / ServiceLoader |

## 4. Actual `build.gradle` (PostgreSQL + Ollama)

```groovy
plugins {
    id 'java'
    id 'groovy'
    id 'org.springframework.boot' version '4.1.0'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.azhukov'
version = '0.0.1-SNAPSHOT'

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    // Spring Boot
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // Groovy 5
    implementation 'org.apache.groovy:groovy:5.0.7'
    implementation 'org.apache.groovy:groovy-json:5.0.7'

    // LLM
    implementation 'dev.langchain4j:langchain4j:1.18.0'
    implementation 'dev.langchain4j:langchain4j-open-ai:1.18.0'
    implementation 'dev.langchain4j:langchain4j-ollama:1.18.0'

    // MCP
    implementation 'io.modelcontextprotocol.sdk:mcp:2.0.0'

    // DB / migrations
    runtimeOnly 'org.postgresql:postgresql'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'

    // Templating
    implementation 'io.pebbletemplates:pebble:4.1.2'

    // Retry
    implementation 'io.github.resilience4j:resilience4j-retry:2.4.0'

    // CLI
    implementation 'info.picocli:picocli:4.7.7'
    implementation 'org.jline:jline:4.3.1'

    // Logging
    implementation 'org.slf4j:slf4j-api:2.0.17'

    // Test
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.apache.groovy:groovy-test:5.0.7'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.testcontainers:junit-jupiter:1.21.4'
    testImplementation 'org.testcontainers:postgresql:1.21.4'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
    jvmArgs '-XX:+EnableDynamicAgentLoading'
}

tasks.withType(JavaCompile).configureEach {
    options.release = 25
}

springBoot {
    buildInfo()
}
```

## 5. Decision Notes

- **LangChain4j vs Spring AI:** LangChain4j is more mature and provider-agnostic. First provider is **Ollama** via `langchain4j-ollama` (local endpoint `http://localhost:11434`). OpenAI-compatible kept for future external endpoints.
- **WebFlux vs Virtual Threads:** Chose **Spring MVC + virtual threads** (`spring.threads.virtual.enabled=true`). Agent is I/O-bound but tools are mostly blocking (JDBC, CDP, shell); reactive types would infect the whole stack. WebFlux only if SSE/streaming needed later.
- **Validation:** Bean Validation + Jackson. Tool schemas generated via Jackson or manually.
- **HTTP client:** Start with `java.net.http.HttpClient`. Switch to OkHttp if proxy/SOCKS needs exceed JDK support.
- **Persistence:** PostgreSQL via JDBC + Flyway. Local dev uses the existing `project-workflow-db-1` container on port 5432 with database `java_agent`.
- **Agent name:** Configurable via `agent.name`; defaults to `Джава агент`.
