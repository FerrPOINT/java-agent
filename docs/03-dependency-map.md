# 03 — Python → Java Dependency Map

Agent pins a large dependency tree. This document maps every *core* dependency to a Java alternative. Optional/provider-specific deps are listed but marked out-of-scope.

## 1. Core Dependencies

| Python library | Agent usage | Java replacement | Notes |
|----------------|--------------|--------------------|-------|
| `openai==2.24.0` | Chat completions, embeddings, Responses API | **LangChain4j** `open-ai` module or **Spring AI** `openai` starter; raw alternative: `com.openai:openai-java` beta | LangChain4j has its own OpenAI REST client and works with Spring/Quarkus |
| `httpx[socks]==0.28.1` / `requests==2.33.0` | HTTP clients | **Java 11 `java.net.http.HttpClient`** + `OkHttp` for SOCKS/proxy | Virtual threads make HttpClient ergonomic |
| `pydantic==2.13.4` | Validation, serialization, JSON schemas | **Jackson** + **Jakarta Bean Validation** (`hibernate-validator`); JSON Schema: `mbknor-jackson-jsonschema` or `jsonschema-generator` | No exact Pydantic clone in Java; combine Jackson + Bean Validation |
| `jinja2==3.1.6` | System/skill prompt templates | **Pebble** (`io.pebbletemplates:pebble`) or **Carrot** | Pebble is Jinja-like |
| `pyyaml==6.0.3` / `ruamel.yaml` | Config files, skill metadata | **SnakeYAML** (`org.yaml:snakeyaml`) | Standard |
| `prompt_toolkit==3.0.52` | Interactive CLI REPL | **JLine** (`org.jline:jline`) or picocli | JLine gives history, completion |
| `rich==14.3.3` | Colored terminal output | **Jansi** + **SLF4J/Logback** with colorizers, or **Picocli** | Keep minimal |
| `tenacity==9.1.4` | Retry decorators | **Resilience4j** (`resilience4j-retry`) or Spring Retry | Resilience4j is lightweight |
| `croniter==6.0.0` | Cron scheduling | **Quartz Scheduler** or Spring `@Scheduled` cron | Quartz for cron expressions |
| `packaging==26.0` | Version parsing | **Maven Artifact** or **JSemVer** | Only needed for version checks |
| `Markdown==3.10.2` | Markdown rendering | **CommonMark** (`org.commonmark:commonmark`) or **Flexmark** | For CLI output |
| `PyJWT[crypto]==2.13.0` | JWT for gateway/auth | **JJWT** (`io.jsonwebtoken:jjwt`) or **Nimbus JOSE** | Standard |
| `cryptography==46.0.7` | Crypto ops | **BouncyCastle** (`bcprov-jdk18on`) | Only if needed beyond JJWT |
| `psutil==7.2.2` | Process/system info | **OSHI** (`com.github.oshi:oshi-core`) or JDK `ProcessHandle` | Use ProcessHandle first |
| `websockets==15.0.1` | WebSocket clients/servers | **Tyrus** (JSR 356) or `java.net.http.WebSocket` | Java 11 client works for clients |
| `fastapi>=0.104.0` + `uvicorn` | API server, gateway webhooks | **Spring Boot 4.1.0** with WebFlux or Web MVC | Replaces both |
| `Pillow==12.2.0` | Image processing | **JavaFX** or **Thumbnailator** | Optional; vision mostly uses base64 passthrough |
| Browser automation | agent-browser / Playwright | Lightweight CDP client (`java.net.http.WebSocket`) + optional Selenium chrome-driver launcher | Avoid 200 MB Playwright deps in prototype |
| `pathspec==1.1.1` | Gitignore-style matching | **`com.github.pathspec`** or implement with `PathMatcher` | Small utility |
| `faster-whisper==1.2.1` + `sounddevice` | Voice transcription | **Whisper.cpp Java bindings** or **Vosk** | Skip for prototype |
| `numpy` | Audio arrays | **Java Sound API** or **TarsosDSP** | Skip for prototype |
| `python-telegram-bot` | Telegram gateway | **TelegramBots Java library** (`org.telegram:telegrambots`) | Defer; keep interface |

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
| `slack-bolt`, `discord.py`, `mautrix` | `messaging` | Slack SDK, Discord4J, Matrix Android SDK — defer |
| `aiohttp` | `messaging`/`web` | Spring WebClient |
| `mcp==1.26.0` | `mcp` | **`io.modelcontextprotocol.sdk:mcp`** |
| `agent-client-protocol==0.9.0` | `acp` | ACP is new; likely custom or JetBrains library when stable |
| `firecrawl-py` | `web` | Firecrawl REST API |
| `exa-py` | `exa` | Exa REST API |
| `fal-client` | `fal` | FAL REST API |
| `edge-tts` / `elevenlabs` | `tts-*` | Edge TTS is hard on Java; ElevenLabs has REST API |
| `modal`, `daytona` | `modal`, `daytona` | Their respective Java/REST APIs |
| `hindsight-client` | `hindsight` | REST wrapper |

## 3. Build / Test / Dev Dependencies

| Python tool | Java replacement |
|-------------|------------------|
| `pytest` + `pytest-asyncio` | **JUnit 5** + **AssertJ** + **Awaitility** |
| `ruff` | **Spotless** + **Checkstyle** |
| `mypy` | **Checker Framework** or strict null checks in Java |
| `uv` / `pip` | **Maven** or **Gradle** |
| `setuptools` entry points | Spring Boot `spring.factories` / ServiceLoader |

## 4. Recommended Starter `build.gradle`

```groovy
plugins {
    id 'java'
    id 'groovy'
    id 'org.springframework.boot' version '4.1.0'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.ferrpoint'
version = '0.0.1-SNAPSHOT'

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    // Spring Boot (versions from Spring Boot 4.1 BOM)
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-websocket'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // Groovy
    implementation 'org.apache.groovy:groovy:5.0.7'

    // LLM
    implementation 'dev.langchain4j:langchain4j:1.18.0'
    implementation 'dev.langchain4j:langchain4j-open-ai:1.18.0'

    // MCP
    implementation 'io.modelcontextprotocol.sdk:mcp:2.0.0'

    // DB
    runtimeOnly 'org.xerial:sqlite-jdbc'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.hibernate.orm:hibernate-community-dialects'

    // Templating
    implementation 'io.pebbletemplates:pebble:4.1.2'

    // Retry
    implementation 'io.github.resilience4j:resilience4j-retry:2.4.0'

    // CLI
    implementation 'info.picocli:picocli:4.7.7'
    implementation 'org.jline:jline:4.3.1'

    // Test
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}

tasks.withType(JavaCompile).configureEach {
    options.release = 25
}
```

## 5. Decision Notes

- **LangChain4j vs Spring AI:** LangChain4j is more mature and provider-agnostic; Spring AI is better if the whole stack is Spring. Recommend LangChain4j for the core `ModelClient`, Spring Boot for HTTP/websocket/gateway.
- **WebFlux vs Virtual Threads:** For an I/O-bound agent (LLM calls, CDP, shell, web) virtual threads give the same concurrency benefits as WebFlux without forcing reactive code through the whole stack. Keep WebFlux only if you need SSE or extreme long-lived connection counts.
- **Validation:** Avoid heavy POJO-to-JSON-schema libs at first. Generate tool schemas manually or with Jackson modules; add Bean Validation constraints.
- **HTTP client:** Start with `java.net.http.HttpClient` for simplicity. Switch to OkHttp if SOCKS/proxy requirements exceed JDK support.
- **Persistence:** SQLite via JDBC is sufficient for prototype. Add PostgreSQL support later.
- **Agent name:** Configurable via `agent.name`; defaults to `Джава агент`.