# План: Полный перенос Hermes в java-agent (剩余功能 + Lombok + application.yml)

> **Цель:** Перенести все оставшиеся функции Hermes, внедрить Lombok как приоритетный подход к коду, вынести ВСЕ настройки в application.yml.

---

## 0. Lombok migration (приоритет 0 — сначала инфраструктура)

### 0.1 Добавить Lombok в build.gradle

**backend/build.gradle:**
```groovy
dependencies {
    // Lombok
    compileOnly 'org.projectlombok:lombok:1.18.36'
    annotationProcessor 'org.projectlombok:lombok:1.18.36'
    testCompileOnly 'org.projectlombok:lombok:1.18.36'
    testAnnotationProcessor 'org.projectlombok:lombok:1.18.36'
}
```

**telegram-bot/build.gradle:** то же самое.

### 0.2 Рефакторинг существующих классов на Lombok

| Класс | Что заменить | Lombok-аннотации |
|-------|-------------|-----------------|
| `AgentProperties` + все 23 inner classes | Геттеры/сеттеры (400+ строк) | `@Data` или `@Getter @Setter` |
| `BotProperties` + inner classes | Геттеры/сеттеры | `@Getter @Setter` |
| `MemoryEntity` | Геттеры/сеттеры | `@Data` |
| `SessionEntity` | Геттеры/сеттеры | `@Data` |
| `MessageEntity` | Геттеры/сеттеры | `@Data` |
| `BotSessionEntity` | Геттеры/сеттеры | `@Data` |
| `BotMessageEntity` | Геттеры/сеттеры | `@Data` |
| `PendingMemoryEntity` | Геттеры/сеттеры | `@Data` |
| `StickerCacheEntity` | Геттеры/сеттеры | `@Data` |
| `PairingCodeEntity` | Геттеры/сеттеры | `@Data` |
| `SkillEntity` | Геттеры/сеттеры | `@Data` |
| `TodoEntity` | Геттеры/сеттеры | `@Data` |
| `CompressionLockEntity` | Геттеры/сеттеры | `@Data` |
| `AuditLogEntity` | Геттеры/сеттеры | `@Data` |
| Все DTO records | Уже records — не трогать | — |
| `TelegramResponse` | record — не трогать | — |
| `UpdateEvent` | record — не трогать | — |
| Все `*Tool` классы | Конструкторы → `@RequiredArgsConstructor` | `@RequiredArgsConstructor`, `@Slf4j` |
| Все `*Service` классы | Конструкторы → `@RequiredArgsConstructor` | `@RequiredArgsConstructor`, `@Slf4j` |
| Все `*Controller` классы | Конструкторы → `@RequiredArgsConstructor` | `@RequiredArgsConstructor`, `@Slf4j` |
| `DefaultAgentRuntime` | Конструктор → `@RequiredArgsConstructor` | `@RequiredArgsConstructor`, `@Slf4j` |
| `DefaultContextEngine` | Конструктор → `@RequiredArgsConstructor` | `@RequiredArgsConstructor`, `@Slf4j` |
| Логгеры: `private static final Logger log = ...` | Заменить на `@Slf4j` | `@Slf4j` |

**Оценка:** ~80 классов рефакторить, ~2000 строк boilerplate удалить.

### 0.3 Тесты

- `@Slf4j` в тестах где нужен логгер
- `@RequiredArgsConstructor` в тестовых конфигурациях
- Убедиться что все тесты проходят

---

## 1. Cron Jobs / Scheduled Tasks

### 1.1 Backend

| # | Компонент | Файл | Тесты |
|---|-----------|------|-------|
| 1.1 | `CronJobEntity` — id, name, schedule (cron expression), prompt, enabled, deliver-to, created-at, last-run, next-run | `persistence/entity/CronJobEntity.java` | — |
| 1.2 | `CronJobRepository` — findByEnabledTrue, findByName | `persistence/repository/CronJobRepository.java` | — |
| 1.3 | `CronJobService` — create, list, update, pause, resume, remove, runNow. Uses `cron-utils` for parsing. `ScheduledExecutorService` for scheduling. | `service/CronJobService.java` | `CronJobServiceTest` (8) |
| 1.4 | `CronJobTool` — LLM tool: `cronjob(action=create|list|update|pause|resume|remove|run, schedule, prompt, name)` | `tools/cron/CronJobTool.java` | `CronJobToolTest` (4) |
| 1.5 | `CronJobController` — REST API: POST/GET/PUT/DELETE /api/v1/agent/cron | `api/CronJobController.java` | `CronJobControllerTest` (4) |
| 1.6 | V7 migration: `CREATE TABLE cron_jobs` | `db/migration/V7__cron_jobs.sql` | — |
| 1.7 | Config: `agent.cron.enabled` (default false), `agent.cron.max-parallel-jobs` (default 10), `agent.cron.dispatch-interval-seconds` (default 60) | `AgentProperties.CronProperties` | — |

### 1.2 Telegram-bot

| # | Компонент | Файл | Тесты |
|---|-----------|------|-------|
| 1.8 | AgentBackendClient: `createCronJob`, `listCronJobs`, `updateCronJob`, `pauseCronJob`, `resumeCronJob`, `removeCronJob` | `core/AgentBackendClient.java` (patch) | — |
| 1.9 | BotMessageProcessor: deliver cron output to chat (like Hermes delivery) | `core/BotMessageProcessor.java` (patch) | — |

---

## 2. Prompt Caching

| # | Компонент | Файл | Тесты |
|---|-----------|------|-------|
| 2.1 | `PromptCacheTracker` — track cached prefix per session. `markCached(sessionId, prefixHash)`, `isCacheValid(sessionId, prefixHash)`, `invalidate(sessionId)`, `getCacheStats() → {hitRate, tokensSaved}` | `core/prompt/PromptCacheTracker.java` | `PromptCacheTrackerTest` (5) |
| 2.2 | Wire in `DefaultPromptBuilder`: track system prompt hash. If unchanged → mark cached. | `core/prompt/DefaultPromptBuilder.java` (patch) | — |
| 2.3 | Wire in `DefaultContextEngine`: before adding memory recall, check cache validity. If memory changed → invalidate. | `core/context/DefaultContextEngine.java` (patch) | — |
| 2.4 | Config: `agent.prompt-caching.enabled` (default true), `agent.prompt-caching.track-stats` (default false) | `AgentProperties.PromptCachingProperties` | — |

---

## 3. Conversation Compression (full /compress)

| # | Компонент | Файл | Тесты |
|---|-----------|------|-------|
| 3.1 | `ConversationCompressor` — full compress: LLM summary of entire history with optional focus topic. Partial compress: keep last N exchanges verbatim, summarize rest. | `core/context/ConversationCompressor.java` | `ConversationCompressorTest` (5) |
| 3.2 | `CompressRequest` DTO — `sessionId`, `focusTopic`, `keepLastN` (for partial) | `api/dto/CompressRequest.java` | — |
| 3.3 | Backend endpoint: `POST /api/v1/agent/session/{sessionId}/compress` (полная версия — сейчас заглушка) | `api/AgentController.java` (patch) | — |
| 3.4 | `/compress` command: update to pass `focusTopic` and `keepLastN` args | `commands/impl/CompressCommand.java` (patch) | `CompressCommandTest` (update) |
| 3.5 | Config: `agent.compression.enabled` (default true), `agent.compression.summary-chunk-tokens` (default 2000), `agent.compression.abort-on-summary-failure` (default false) | `AgentProperties.CompressionProperties` | — |

---

## 4. Checkpoint Manager (/rollback)

| # | Компонент | Файл | Тесты |
|---|-----------|------|-------|
| 4.1 | `CheckpointManager` — snapshot filesystem state before dangerous operations. `snapshot(description) → id`, `list() → List<Checkpoint>`, `restore(id)`, `prune(maxSnapshots)` | `core/checkpoint/CheckpointManager.java` | `CheckpointManagerTest` (5) |
| 4.2 | `CheckpointEntity` — id, description, timestamp, fileCount, totalSize | `persistence/entity/CheckpointEntity.java` | — |
| 4.3 | `CheckpointRepository` | `persistence/repository/CheckpointRepository.java` | — |
| 4.4 | V8 migration: `CREATE TABLE checkpoints` | `db/migration/V8__checkpoints.sql` | — |
| 4.5 | Wire in `TerminalTool`: before dangerous commands, auto-snapshot | `tools/terminal/TerminalTool.java` (patch) | — |
| 4.6 | `/rollback` command: update from stub to real: `/rollback list`, `/rollback restore <id>` | `commands/impl/RollbackCommand.java` (rewrite) | `RollbackCommandTest` (3) |
| 4.7 | Config: `agent.checkpoints.enabled` (default true), `agent.checkpoints.max-snapshots` (default 20), `agent.checkpoints.max-size-mb` (default 500) | `AgentProperties.CheckpointProperties` | — |

---

## 5. Credits / Usage Tracker

| # | Компонент | Файл | Тесты |
|---|-----------|------|-------|
| 5.1 | `UsageTracker` — track tokens per turn/session/day. `recordTurn(sessionId, promptTokens, completionTokens, model)`, `getSessionUsage(sessionId)`, `getDailyUsage(date)`, `getInsights(userId)` | `core/usage/UsageTracker.java` | `UsageTrackerTest` (5) |
| 5.2 | `UsageEntity` — id, sessionId, userId, model, promptTokens, completionTokens, totalTokens, cost, createdAt | `persistence/entity/UsageEntity.java` | — |
| 5.3 | `UsageRepository` — findBySessionId, findByUserIdAndDateBetween | `persistence/repository/UsageRepository.java` | — |
| 5.4 | V9 migration: `CREATE TABLE usage_log` | `db/migration/V9__usage_log.sql` | — |
| 5.5 | Wire in `AgentRuntimeService.runTurn()`: record tokens after LLM call | `service/AgentRuntimeService.java` (patch) | — |
| 5.6 | `/insights` command: update from stub to real data from UsageTracker | `commands/impl/InsightsCommand.java` (rewrite) | `InsightsCommandTest` (update) |
| 5.7 | Config: `agent.usage.track-enabled` (default true), `agent.usage.show-cost` (default false), `agent.usage.show-token-analytics` (default false) | `AgentProperties.UsageProperties` | — |

---

## 6. Image Generation

| # | Компонент | Файлы | Тесты |
|---|-----------|------|-------|
| 6.1 | `ImageGenProvider` interface — `generate(prompt, aspectRatio) → ImageResult` | `core/image/ImageGenProvider.java` | — |
| 6.2 | `FalImageGenProvider` — FAL API implementation | `core/image/FalImageGenProvider.java` | `FalImageGenProviderTest` (3) |
| 6.3 | `ImageGenTool` — LLM tool: `image_generate(prompt, aspect_ratio)` | `tools/image/ImageGenTool.java` | `ImageGenToolTest` (3) |
| 6.4 | Config: `agent.image-gen.enabled` (default false), `agent.image-gen.provider` (fal/openai), `agent.image-gen.api-key`, `agent.image-gen.model` | `AgentProperties.ImageGenProperties` | — |
| 6.5 | Wire in `AgentConfig`: conditional bean creation | `config/AgentConfig.java` (patch) | — |
| 6.6 | BotMessageProcessor: detect `![image](url)` in response → send as photo | `core/BotMessageProcessor.java` (patch) | — |

---

## 7. TTS / Voice

| # | Компонент | Файлы | Тесты |
|---|-----------|------|-------|
| 7.1 | `TtsProvider` interface — `synthesize(text, voice) → byte[]` | `core/tts/TtsProvider.java` | — |
| 7.2 | `EdgeTtsProvider` — Edge TTS (free, offline) | `core/tts/EdgeTtsProvider.java` | `EdgeTtsProviderTest` (3) |
| 7.3 | `OpenAiTtsProvider` — OpenAI TTS | `core/tts/OpenAiTtsProvider.java` | `OpenAiTtsProviderTest` (2) |
| 7.4 | `TtsTool` — LLM tool: `text_to_speech(text)` → MEDIA path | `tools/tts/TtsTool.java` | `TtsToolTest` (3) |
| 7.5 | `/voice` command: rewrite from stub — on/off/tts/status | `commands/impl/VoiceCommand.java` (rewrite) | `VoiceCommandTest` (update) |
| 7.6 | Config: `agent.tts.enabled` (default false), `agent.tts.provider` (edge/openai), `agent.tts.api-key`, `agent.tts.voice` (default "alloy"), `agent.tts.auto-tts` (default false) | `AgentProperties.TtsProperties` | — |

---

## 8. Transcription (voice → text)

| # | Компонент | Файлы | Тесты |
|---|-----------|------|-------|
| 8.1 | `TranscriptionProvider` interface — `transcribe(audioFile) → String` | `core/transcription/TranscriptionProvider.java` | — |
| 8.2 | `OpenAiTranscriptionProvider` — Whisper API | `core/transcription/OpenAiTranscriptionProvider.java` | `OpenAiTranscriptionProviderTest` (3) |
| 8.3 | Wire in `InboundMediaHandler`: for VOICE type → transcribe → use as message text | `media/InboundMediaHandler.java` (patch) | — |
| 8.4 | Config: `agent.transcription.enabled` (default false), `agent.transcription.provider` (openai), `agent.transcription.api-key`, `agent.transcription.model` (whisper-1) | `AgentProperties.TranscriptionProperties` | — |

---

## 9. MCP OAuth

| # | Компонент | Файлы | Тесты |
|---|-----------|------|-------|
| 9.1 | `McpOAuthManager` — manage OAuth tokens for MCP servers. `getToken(serverName)`, `refreshToken(serverName)`, `storeToken(serverName, token)` | `core/mcp/McpOAuthManager.java` | `McpOAuthManagerTest` (4) |
| 9.2 | `McpOAuthEntity` — id, serverName, accessToken, refreshToken, expiresAt | `persistence/entity/McpOAuthEntity.java` | — |
| 9.3 | `McpOAuthRepository` | `persistence/repository/McpOAuthRepository.java` | — |
| 9.4 | V10 migration: `CREATE TABLE mcp_oauth_tokens` | `db/migration/V10__mcp_oauth.sql` | — |
| 9.5 | Wire in `McpTool`: before MCP call, check if server needs OAuth → get token → include in headers | `tools/mcp/McpTool.java` (patch) | — |
| 9.6 | Config: `agent.mcp.oauth-enabled` (default false), `agent.mcp.oauth-token-store` (db) | `AgentProperties.McpProperties` (patch) | — |

---

## 10. Managed Tool Gateway (service-gated tools)

| # | Компонент | Файлы | Тесты |
|---|-----------|------|-------|
| 10.1 | `ManagedToolGateway` — tools behind `check_fn`: conditionally enabled based on config/runtime state. `isEnabled(toolName) → bool`, `registerTool(toolName, checkFn)` | `core/tools/ManagedToolGateway.java` | `ManagedToolGatewayTest` (4) |
| 10.2 | Wire in `SpringToolRegistry`: filter tools through ManagedToolGateway before registration | `tools/SpringToolRegistry.java` (patch) | — |
| 10.3 | Config: per-tool `agent.tools.<name>.enabled` (default true), `agent.tools.<name>.check-fn` | `AgentProperties.ToolProperties` | — |

---

## 11. Think Scrubber

| # | Компонент | Файлы | Тесты |
|---|-----------|------|-------|
| 11.1 | `ThinkScrubber` — strip `<think>...</think>` blocks from streaming output before delivery | `core/stream/ThinkScrubber.java` | `ThinkScrubberTest` (3) |
| 11.2 | Wire in `AgentStreamingService`: scrub stream chunks before sending | `service/AgentStreamingService.java` (patch) | — |
| 11.3 | Config: `agent.streaming.scrub-think-blocks` (default true) | `AgentProperties.StreamingProperties` | — |

---

## 12. Error Classifier

| # | Компонент | Файлы | Тесты |
|---|-----------|------|-------|
| 12.1 | `ErrorClassifier` — classify exceptions: RETRYABLE (network, timeout, 429), PERMANENT (400, auth), RATE_LIMIT (429 with Retry-After) | `core/error/ErrorClassifier.java` | `ErrorClassifierTest` (5) |
| 12.2 | Wire in `LangChain4jModelClient`: classify errors → retry RETRYABLE, fail PERMANENT | `core/model/LangChain4jModelClient.java` (patch) | — |
| 12.3 | Config: `agent.error.retry-attempts` (default 3), `agent.error.retry-delay-ms` (default 1000), `agent.error.backoff-multiplier` (default 2) | `AgentProperties.ErrorProperties` | — |

---

## 13. Rate Limit Tracker

| # | Компонент | Файлы | Тесты |
|---|-----------|------|-------|
| 13.1 | `RateLimitTracker` — track API rate limits from response headers. `getRemaining()`, `getResetTime()`, `shouldBackoff() → bool` | `core/model/RateLimitTracker.java` | `RateLimitTrackerTest` (3) |
| 13.2 | Wire in `LangChain4jModelClient`: parse rate limit headers, back off when approaching | `core/model/LangChain4jModelClient.java` (patch) | — |

---

## 14. Skill Bundles

| # | Компонент | Файлы | Тесты |
|---|-----------|------|-------|
| 14.1 | `SkillBundleService` — install/list/uninstall skill bundles. Bundle = ZIP with SKILL.md + references/ | `core/skill/SkillBundleService.java` | `SkillBundleServiceTest` (4) |
| 14.2 | `/bundles` command: update from stub — list installed bundles, install/uninstall | `commands/impl/BundlesCommand.java` (rewrite) | `BundlesCommandTest` (update) |
| 14.3 | Config: `agent.skills.bundles-enabled` (default true), `agent.skills.bundles-dir` (default ~/.java-agent/bundles) | `AgentProperties.SkillsProperties` (patch) | — |

---

## 15. Coding Context Detection

| # | Компонент | Файлы | Тесты |
|---|-----------|------|-------|
| 15.1 | `CodingContextDetector` — detect git repo, language, framework, build tool from working directory | `core/context/CodingContextDetector.java` | `CodingContextDetectorTest` (4) |
| 15.2 | Wire in `DefaultPromptBuilder`: inject coding context into system prompt | `core/prompt/DefaultPromptBuilder.java` (patch) | — |
| 15.3 | Config: `agent.coding-context.enabled` (default true), `agent.coding-context.min-score` (default 0.5) | `AgentProperties.CodingContextProperties` | — |

---

## 16. Interrupt / Cancellation

| # | Компонент | Файлы | Тесты |
|---|-----------|------|-------|
| 16.1 | `InterruptToken` — cooperative cancellation token per session. `isCancelled(sessionId) → bool`, `cancel(sessionId)`, `reset(sessionId)` | `core/agent/InterruptToken.java` | `InterruptTokenTest` (3) |
| 16.2 | Wire in `DefaultAgentRuntime`: check token in tool execution loop. `/stop` → `cancel(sessionId)` | `core/agent/DefaultAgentRuntime.java` (patch) | — |
| 16.3 | Wire in `BotMessageProcessor.handleTextOrMedia()`: already has interrupt via BusySessionHandler, connect to InterruptToken | `core/BotMessageProcessor.java` (patch) | — |

---

## 17. Tool Result Classification

| # | Компонент | Файлы | Тесты |
|---|-----------|------|-------|
| 17.1 | `ToolResultClassifier` — classify tool results: SUCCESS, FAILURE, PARTIAL, SILENT (`***`) | `core/tools/ToolResultClassifier.java` | `ToolResultClassifierTest` (4) |
| 17.2 | Wire in `ToolExecutionService`: classify results → affect agent loop decisions | `tools/ToolExecutionService.java` (patch) | — |

---

## 18. Turn Finalizer

| # | Компонент | Файлы | Тесты |
|---|-----------|------|-------|
| 18.1 | `TurnFinalizer` — cleanup after turn: persist messages, update session timestamp, evict cache, notify hooks | `core/agent/TurnFinalizer.java` | `TurnFinalizerTest` (3) |
| 18.2 | Wire in `DefaultAgentRuntime.runTurn()`: call after turn completes (success or error) | `core/agent/DefaultAgentRuntime.java` (patch) | — |

---

## 19. Tool Output Limits

| # | Компонент | Файлы | Тесты |
|---|-----------|------|-------|
| 19.1 | `ToolOutputLimiter` — limit tool output size. `truncate(content, maxChars) → String` with truncation warning | `core/tools/ToolOutputLimiter.java` | `ToolOutputLimiterTest` (3) |
| 19.2 | Wire in `ToolExecutionService`: apply limiter to all tool results | `tools/ToolExecutionService.java` (patch) | — |
| 19.3 | Config: already exists `agent.tool-output.max-chars`, `agent.tool-output.truncate-warning-chars` | — | — |

---

## 20. Full application.yml (все настройки)

### 20.1 Backend application.yml — полная версия

Все настройки в одном месте, с env var overrides:

```yaml
agent:
  name: ${AGENT_NAME:Джава агент}
  
  # ─── Model ───
  model:
    provider: ${AGENT_MODEL_PROVIDER:openai-compatible}
    base-url: ${AGENT_MODEL_BASE_URL:http://localhost:11434/v1}
    api-key: ${AGENT_MODEL_API_KEY:}
    model-name: ${AGENT_MODEL_NAME:kimi-k2.6}
    timeout-seconds: ${AGENT_MODEL_TIMEOUT_SECONDS:600}
    max-retries: ${AGENT_MODEL_MAX_RETRIES:3}
    max-tokens: ${AGENT_MODEL_MAX_TOKENS:4096}
    temperature: ${AGENT_MODEL_TEMPERATURE:0.7}
    headers: {}
  
  # ─── Auxiliary model ───
  auxiliary:
    enabled: ${AGENT_AUXILIARY_ENABLED:false}
    provider: ${AGENT_AUXILIARY_PROVIDER:openai-compatible}
    base-url: ${AGENT_AUXILIARY_BASE_URL:}
    api-key: ${AGENT_AUXILIARY_API_KEY:}
    model-name: ${AGENT_AUXILIARY_MODEL_NAME:}
    timeout-seconds: ${AGENT_AUXILIARY_TIMEOUT_SECONDS:600}
    max-retries: ${AGENT_AUXILIARY_MAX_RETRIES:3}
  
  # ─── Vision ───
  vision:
    provider: ${AGENT_VISION_PROVIDER:}
    base-url: ${AGENT_VISION_BASE_URL:}
    api-key: ${AGENT_VISION_API_KEY:}
    model-name: ${AGENT_VISION_MODEL_NAME:}
    timeout-seconds: ${AGENT_VISION_TIMEOUT_SECONDS:600}
    max-retries: ${AGENT_VISION_MAX_RETRIES:3}
    use-auxiliary-first: ${AGENT_VISION_USE_AUXILIARY_FIRST:true}
  
  # ─── Browser ───
  browser:
    cdp-url: ${AGENT_BROWSER_CDP_URL:http://localhost:9222}
    default-timeout-ms: ${AGENT_BROWSER_DEFAULT_TIMEOUT_MS:120000}
    page-load-timeout-ms: ${AGENT_BROWSER_PAGE_LOAD_TIMEOUT_MS:120000}
    max-tabs: ${AGENT_BROWSER_MAX_TABS:5}
    headless: ${AGENT_BROWSER_HEADLESS:true}
    executable-path: ${AGENT_BROWSER_EXECUTABLE_PATH:}
  
  # ─── Chromium ───
  chromium:
    auto-start: ${AGENT_CHROMIUM_AUTO_START:false}
    auto-install: ${AGENT_CHROMIUM_AUTO_INSTALL:true}
    download-url: ${AGENT_CHROMIUM_DOWNLOAD_URL:https://storage.googleapis.com/chromium-browser-snapshots}
    revision: ${AGENT_CHROMIUM_REVISION:}
    launch-timeout-seconds: ${AGENT_CHROMIUM_LAUNCH_TIMEOUT_SECONDS:120}
    headless: ${AGENT_CHROMIUM_HEADLESS:true}
    executable-path: ${AGENT_CHROMIUM_EXECUTABLE_PATH:}
    user-data-dir: ${AGENT_CHROMIUM_USER_DATA_DIR:}
    extra-args: ${AGENT_CHROMIUM_EXTRA_ARGS:}
  
  # ─── Web ───
  web:
    search-results: ${AGENT_WEB_SEARCH_RESULTS:5}
    extract-timeout-seconds: ${AGENT_WEB_EXTRACT_TIMEOUT_SECONDS:120}
    extract-max-chars: ${AGENT_WEB_EXTRACT_MAX_CHARS:100000}
    search-provider: ${AGENT_WEB_SEARCH_PROVIDER:ddg}
  
  # ─── Terminal ───
  terminal:
    default-timeout-seconds: ${AGENT_TERMINAL_DEFAULT_TIMEOUT_SECONDS:300}
    max-timeout-seconds: ${AGENT_TERMINAL_MAX_TIMEOUT_SECONDS:1800}
    docker-enabled: ${AGENT_TERMINAL_DOCKER_ENABLED:false}
    docker-image: ${AGENT_TERMINAL_DOCKER_IMAGE:ubuntu:25.04}
    docker-mount-cwd: ${AGENT_TERMINAL_DOCKER_MOUNT_CWD:false}
    docker-volumes: []
    inline-shell: ${AGENT_TERMINAL_INLINE_SHELL:true}
    inline-shell-timeout: ${AGENT_TERMINAL_INLINE_SHELL_TIMEOUT:30}
  
  # ─── File ───
  file:
    read-max-chars: ${AGENT_FILE_READ_MAX_CHARS:100000}
    write-max-chars: ${AGENT_FILE_WRITE_MAX_CHARS:100000}
  
  # ─── Memory ───
  memory:
    enabled: ${AGENT_MEMORY_ENABLED:true}
    write-approval: ${AGENT_MEMORY_WRITE_APPROVAL:false}
    memory-char-limit: ${AGENT_MEMORY_CHAR_LIMIT:2200}
    user-char-limit: ${AGENT_MEMORY_USER_CHAR_LIMIT:1375}
    max-facts-per-user: ${AGENT_MEMORY_MAX_FACTS_PER_USER:1000}
    max-facts-per-query: ${AGENT_MEMORY_MAX_FACTS_PER_QUERY:10}
    similarity-threshold: ${AGENT_MEMORY_SIMILARITY_THRESHOLD:0.75}
    background-review:
      enabled: ${AGENT_MEMORY_BACKGROUND_REVIEW_ENABLED:true}
      delay-ms: ${AGENT_MEMORY_BACKGROUND_REVIEW_DELAY_MS:2000}
      review-memory: true
      review-skills: false
  
  # ─── Skills ───
  skills:
    enabled: ${AGENT_SKILLS_ENABLED:true}
    max-skills-in-prompt: ${AGENT_SKILLS_MAX_SKILLS_IN_PROMPT:20}
    max-chars-per-skill: ${AGENT_SKILLS_MAX_CHARS_PER_SKILL:4000}
    bundles-enabled: ${AGENT_SKILLS_BUNDLES_ENABLED:true}
    bundles-dir: ${AGENT_SKILLS_BUNDLES_DIR:~/.java-agent/bundles}
    default-toolsets:
      - web
      - file
      - browser
      - terminal
      - coding
      - memory
      - skills
      - core
      - delegate
  
  # ─── Session search ───
  session-search:
    max-results: ${AGENT_SESSION_SEARCH_MAX_RESULTS:10}
    snippet-chars: ${AGENT_SESSION_SEARCH_SNIPPET_CHARS:200}
  
  # ─── Tool output ───
  tool-output:
    max-chars: ${AGENT_TOOL_OUTPUT_MAX_CHARS:16000}
    truncate-warning-chars: ${AGENT_TOOL_OUTPUT_TRUNCATE_WARNING_CHARS:12000}
    timeout-seconds: ${AGENT_TOOL_OUTPUT_TIMEOUT_SECONDS:300}
    include-timestamps: ${AGENT_TOOL_OUTPUT_INCLUDE_TIMESTAMPS:true}
  
  # ─── Context ───
  context:
    max-tokens: ${AGENT_CONTEXT_MAX_TOKENS:16000}
    target-tokens: ${AGENT_CONTEXT_TARGET_TOKENS:12000}
    summary-chunk-tokens: ${AGENT_CONTEXT_SUMMARY_CHUNK_TOKENS:2000}
    max-context-messages: ${AGENT_CONTEXT_MAX_CONTEXT_MESSAGES:50}
  
  # ─── Compression ───
  compression:
    enabled: ${AGENT_COMPRESSION_ENABLED:true}
    summary-chunk-tokens: ${AGENT_COMPRESSION_SUMMARY_CHUNK_TOKENS:2000}
    abort-on-summary-failure: ${AGENT_COMPRESSION_ABORT_ON_SUMMARY_FAILURE:false}
  
  # ─── Delegation ───
  delegation:
    enabled: ${AGENT_DELEGATION_ENABLED:true}
    max-depth: ${AGENT_DELEGATION_MAX_DEPTH:3}
    default-timeout-seconds: ${AGENT_DELEGATION_DEFAULT_TIMEOUT_SECONDS:1800}
    max-concurrent-children: ${AGENT_DELEGATION_MAX_CONCURRENT_CHILDREN:10}
  
  # ─── MCP ───
  mcp:
    enabled: ${AGENT_MCP_ENABLED:false}
    oauth-enabled: ${AGENT_MCP_OAUTH_ENABLED:false}
    servers: []
  
  # ─── Gateway ───
  gateway:
    telegram:
      bot-token: ${TELEGRAM_BOT_TOKEN:}
      webhook-url: ${AGENT_GATEWAY_TELEGRAM_WEBHOOK_URL:}
      timeout-seconds: ${AGENT_GATEWAY_TELEGRAM_TIMEOUT_SECONDS:30}
      allowed-user-ids: ${AGENT_GATEWAY_TELEGRAM_ALLOWED_USER_IDS:}
      allowed-usernames: ${AGENT_GATEWAY_TELEGRAM_ALLOWED_USERNAMES:}
      allow-by-default: ${AGENT_GATEWAY_TELEGRAM_ALLOW_BY_DEFAULT:false}
      webhook:
        enabled: ${AGENT_GATEWAY_TELEGRAM_WEBHOOK_ENABLED:false}
      long-polling:
        enabled: ${AGENT_GATEWAY_TELEGRAM_LONG_POLLING_ENABLED:false}
  
  # ─── Security ───
  security:
    approvals-enabled: ${AGENT_SECURITY_APPROVALS_ENABLED:true}
    file-safety-enabled: ${AGENT_SECURITY_FILE_SAFETY_ENABLED:true}
    url-safety-enabled: ${AGENT_SECURITY_URL_SAFETY_ENABLED:true}
    redact-enabled: ${AGENT_SECURITY_REDACT_ENABLED:true}
    redact-secrets: ${AGENT_SECURITY_REDACT_SECRETS:true}
    redact-pii: ${AGENT_SECURITY_REDACT_PII:false}
  
  # ─── Core ───
  core:
    max-turns: ${AGENT_CORE_MAX_TURNS:90}
    tool-use-enforcement: ${AGENT_CORE_TOOL_USE_ENFORCEMENT:auto}
    task-completion-guidance: ${AGENT_CORE_TASK_COMPLETION_GUIDANCE:true}
    parallel-tool-call-guidance: ${AGENT_CORE_PARALLEL_TOOL_CALL_GUIDANCE:true}
    auto-title-session: ${AGENT_CORE_AUTO_TITLE_SESSION:true}
    reasoning-config: ${AGENT_CORE_REASONING_CONFIG:medium}
    default-system-prompt: ${AGENT_CORE_DEFAULT_SYSTEM_PROMPT:You are ${agent.name}. Use available tools when needed. Be concise. Return plain text unless JSON is requested.}
    http-client-timeout-seconds: ${AGENT_CORE_HTTP_CLIENT_TIMEOUT_SECONDS:30}
    max-reference-file-bytes: ${AGENT_CORE_MAX_REFERENCE_FILE_BYTES:100000}
    working-directory: ${AGENT_CORE_WORKING_DIRECTORY:}
    http-user-agent: ${AGENT_CORE_HTTP_USER_AGENT:AzhukovAgent/1.0}
  
  # ─── Budget ───
  budget:
    enabled: ${AGENT_BUDGET_ENABLED:true}
    max-model-calls-per-turn: ${AGENT_BUDGET_MAX_MODEL_CALLS_PER_TURN:5}
    max-tool-executions-per-turn: ${AGENT_BUDGET_MAX_TOOL_EXECUTIONS_PER_TURN:20}
    max-tokens-per-turn: ${AGENT_BUDGET_MAX_TOKENS_PER_TURN:200000}
    max-tool-duration-ms-per-turn: ${AGENT_BUDGET_MAX_TOOL_DURATION_MS_PER_TURN:600000}
  
  # ─── Prompt caching ───
  prompt-caching:
    enabled: ${AGENT_PROMPT_CACHING_ENABLED:true}
    track-stats: ${AGENT_PROMPT_CACHING_TRACK_STATS:false}
  
  # ─── Checkpoints ───
  checkpoints:
    enabled: ${AGENT_CHECKPOINTS_ENABLED:true}
    max-snapshots: ${AGENT_CHECKPOINTS_MAX_SNAPSHOTS:20}
    max-size-mb: ${AGENT_CHECKPOINTS_MAX_SIZE_MB:500}
  
  # ─── Usage tracking ───
  usage:
    track-enabled: ${AGENT_USAGE_TRACK_ENABLED:true}
    show-cost: ${AGENT_USAGE_SHOW_COST:false}
    show-token-analytics: ${AGENT_USAGE_SHOW_TOKEN_ANALYTICS:false}
  
  # ─── Image generation ───
  image-gen:
    enabled: ${AGENT_IMAGE_GEN_ENABLED:false}
    provider: ${AGENT_IMAGE_GEN_PROVIDER:fal}
    api-key: ${AGENT_IMAGE_GEN_API_KEY:}
    model: ${AGENT_IMAGE_GEN_MODEL:}
  
  # ─── TTS ───
  tts:
    enabled: ${AGENT_TTS_ENABLED:false}
    provider: ${AGENT_TTS_PROVIDER:edge}
    api-key: ${AGENT_TTS_API_KEY:}
    voice: ${AGENT_TTS_VOICE:alloy}
    auto-tts: ${AGENT_TTS_AUTO_TTS:false}
  
  # ─── Transcription ───
  transcription:
    enabled: ${AGENT_TRANSCRIPTION_ENABLED:false}
    provider: ${AGENT_TRANSCRIPTION_PROVIDER:openai}
    api-key: ${AGENT_TRANSCRIPTION_API_KEY:}
    model: ${AGENT_TRANSCRIPTION_MODEL:whisper-1}
  
  # ─── Cron ───
  cron:
    enabled: ${AGENT_CRON_ENABLED:false}
    max-parallel-jobs: ${AGENT_CRON_MAX_PARALLEL_JOBS:10}
    dispatch-interval-seconds: ${AGENT_CRON_DISPATCH_INTERVAL_SECONDS:60}
  
  # ─── Streaming ───
  streaming:
    scrub-think-blocks: ${AGENT_STREAMING_SCRUB_THINK_BLOCKS:true}
    edit-interval-ms: ${AGENT_STREAMING_EDIT_INTERVAL_MS:1500}
  
  # ─── Error handling ───
  error:
    retry-attempts: ${AGENT_ERROR_RETRY_ATTEMPTS:3}
    retry-delay-ms: ${AGENT_ERROR_RETRY_DELAY_MS:1000}
    backoff-multiplier: ${AGENT_ERROR_BACKOFF_MULTIPLIER:2}
  
  # ─── Coding context ───
  coding-context:
    enabled: ${AGENT_CODING_CONTEXT_ENABLED:true}
    min-score: ${AGENT_CODING_CONTEXT_MIN_SCORE:0.5}
  
  # ─── Tool gating ───
  tools:
    managed-gateway-enabled: ${AGENT_TOOLS_MANAGED_GATEWAY_ENABLED:false}
```

### 20.2 Telegram-bot application.yml — полная версия (уже частично готова)

Обновить с всеми новыми настройками.

---

## 21. Очередность реализации

```
Phase A: Lombok + application.yml (этап 0 + 20)
  → рефакторинг всех классов на Lombok
  → полная application.yml

Phase B: Core features (этапы 1-4)
  → Cron jobs, prompt caching, compression, checkpoints

Phase C: Media & Voice (этапы 6-8)
  → Image gen, TTS, transcription

Phase D: Utilities (этапы 2, 9-19)
  → MCP OAuth, managed tools, think scrubber, error classifier, rate limit,
     skill bundles, coding context, interrupt, tool result classification,
     turn finalizer, tool output limits

Phase E: Final wiring + tests
```

### Сводка

| Phase | Этапов | Компонентов | Тестов | Новых файлов | Миграций |
|-------|--------|-------------|--------|-------------|----------|
| A: Lombok + YAML | 2 | 0 новых + 80 рефактор | 0 новых (существующие проходят) | 0 | 0 |
| B: Core features | 4 | 12 | ~27 | 12 | 3 (V7-V9) |
| C: Media & Voice | 3 | 8 | ~14 | 8 | 0 |
| D: Utilities | 11 | 14 | ~39 | 14 | 1 (V10) |
| E: Wiring | 1 | 0 | 0 | 0 | 0 |
| **Итого** | **21** | **34** | **~80** | **34** | **4** |

**Финальное состояние:**
- Lombok во всех классах (@Data, @Slf4j, @RequiredArgsConstructor)
- Все настройки в application.yml с env var overrides
- Cron jobs, prompt caching, compression, checkpoints
- Image gen, TTS, transcription
- MCP OAuth, error classifier, rate limit tracker, think scrubber
- Coding context, interrupt tokens, tool result classification
- ~1,450 тестов (1,373 существующих + ~80 новых)
- 4 новые миграции (V7-V10)
- ~330 Java-файлов (296 + 34 новых)

---

## 22. Риски

| Риск | Решение |
|------|---------|
| Lombok + Spring Boot 4.1 совместимость | Lombok 1.18.36+ поддерживает Java 25 |
| Lombok + record classes | Records не нуждаются в Lombok — не трогать |
| Cron expression parsing | `cron-utils` уже в зависимостях |
| FAL API для image gen | Опционально, default disabled |
| Edge TTS без API key | Бесплатный, работает offline |
| Whisper API для transcription | Опционально, default disabled |
| Prompt cache invalidation | Frozen snapshot + hash comparison |
| Checkpoint storage размер | max-snapshots + max-size-mb лимиты |
| MCP OAuth token refresh | Background refresh thread |

---

*Документ создан для планирования полного переноса Hermes в java-agent.*