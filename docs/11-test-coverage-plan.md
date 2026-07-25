# План полного покрытия тестами java-agent

Цель: у каждого production-класса в `backend/src/main/java` есть автоматизированный тест, который ломается при регрессии.
Разделение:
- **Unit** — без Spring-контекста, моки/стабы.
- **Integration** — `@SpringBootTest` / `@DataJpaTest` / `@WebMvcTest` / Testcontainers.
- **Live** — требуют внешнего сервиса (Ollama, Telegram, Chromium, интернет); помечены `@Tag("live")` и не гоняются в CI по умолчанию.

Текущее состояние: ~40 тестовых файлов, 80 тестов проходят. Многие слои покрыты фрагментарно.

## Легенда

| Приоритет | Смысл |
|-----------|-------|
| P0 | Без этого нельзя выпускать: ядро, безопасность, gateway, конфиг. Делать первыми. |
| P1 | Важные инструменты и API. Делать после P0. |
| P2 | Live/опциональные сценарии. Делать последними. |

| Статус | Смысл |
|--------|-------|
| ✅ есть | Тестовый файл уже в репозитории и проходит. |
| 🔄 partial | Есть тесты, но не все сценарии/граничные случаи. |
| ❌ нет | Нужно написать. |

---

## 1. Конфигурация и properties

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `AgentProperties` | binding из `application.yml`, дефолты, nested-объекты, `@Validated` (если добавить) | Unit / Integration | P0 | ❌ нет |
| `AgentConfig` | создание бинов: `ModelClient`, `GatewayRoutingService`, `ToolRegistry`, `InboundMessageProcessor`; проверка профилей `dev`/`noop`/`cli` | Integration | P0 | ❌ нет |
| `FlywayConfig` | миграции применяются, baseline работает | Integration | P0 | ❌ нет |
| `TelegramConfig` | токен подхватывается из env, webhook/long-polling включаются по property | Integration | P0 | ❌ нет |
| `ShutdownConfigTest` | `server.shutdown=immediate` подхватывается | Integration | P0 | ✅ есть |

### Рекомендуемые тестовые файлы
- `config/AgentPropertiesTest.java` — unit, `@EnableConfigurationProperties` + `ApplicationContextRunner`.
- `config/AgentConfigProfilesTest.java` — `@SpringBootTest` с профилями `noop`, `cli`, `dev`; проверяет, что нужные бины создаются/отсутствуют.
- `config/FlywayMigrationTest.java` — `@DataJpaTest` + `@AutoConfigureTestDatabase(replace=NONE)`, проверяет схему и seed-данные.

---

## 2. API слой (controllers)

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `HealthController` | `/health` возвращает кастомные поля, имя агента из конфига | Unit (`@WebMvcTest`) | P0 | 🔄 partial (есть integration, но нет изолированного) |
| `AgentController` | `/v1/agent/chat`, валидация `ChatRequest`, вызов `AgentRuntimeService`, формат `ChatResponseDto` | Unit (`@WebMvcTest`) + Integration | P0 | ✅ есть `AgentControllerIntegrationTest` |
| `AgentController` streaming | SSE-эндпоинт, порядок чанков, ошибки в потоке | Integration | P0 | ✅ есть `AgentControllerStreamingTest` |
| `ChatCompletionsController` | OpenAI-compatible `/v1/chat/completions`, JSON и streaming режимы, маппинг тулколлов | Unit + Integration | P1 | ❌ нет |
| `VisionController` | `/v1/vision`, валидация `VisionRequest`, вызов vision-клиента, обработка base64 | Unit (`@WebMvcTest`) | P1 | ❌ нет |
| `McpController` | `/mcp/...` (если используется), list tools, call tool | Unit + Integration | P1 | ❌ нет |
| `GlobalExceptionHandler` | 400 на валидации, 404 на unknown tool, 500 на model failure, структура ошибки | Unit (`@WebMvcTest`) | P0 | ❌ нет |
| `RateLimitFilter` | лимит по IP/key, 429, пропуск после сброса | Unit | P0 | ❌ нет |
| `api/health/*` | health indicators (`db`, `model`, `browser`) возвращают UP/DOWN | Integration | P1 | ❌ нет |

### Рекомендуемые тестовые файлы
- `api/AgentControllerUnitTest.java`
- `api/ChatCompletionsControllerTest.java`
- `api/VisionControllerTest.java`
- `api/GlobalExceptionHandlerTest.java`
- `api/filter/RateLimitFilterTest.java`
- `api/health/HealthIndicatorsTest.java`

---

## 3. Ядро агента (`core.agent`, `core.context`, `core.prompt`, `core.state`)

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `DefaultAgentRuntime` | happy path без тулов, один tool call, несколько tool calls, max turns, iteration budget exhausted, model failure, `run(List,List)` для OpenAI endpoint | Unit | P0 | 🔄 partial (`AgentRuntimeUnitTest` есть, но не все сценарии) |
| `DefaultPromptBuilder` | system prompt содержит имя агента, форматирование сессии, references | Unit | P0 | ❌ нет |
| `DefaultContextEngine` | truncation по токенам, сохранение последних сообщений, summary fallback, подсчёт токенов | Unit | P0 | ❌ нет |
| `DefaultContextCompressor` | compress укорачивает, не ломает структуру, edge case empty | Unit | P0 | ❌ нет |
| `DefaultContextReferenceService` | resolve/loadContent для file/web/session-search, invalid reference | Unit | P0 | 🔄 partial |
| `DefaultAgentState` / `AgentConstants` | константы, состояние сессии | Unit | P2 | ❌ нет |
| `DefaultIterationBudget` | startTurn, recordModelCall, recordToolExecution, isExhausted, лимиты по умолчанию | Unit | P0 | ✅ есть |

### Рекомендуемые тестовые файлы
- `core/agent/AgentRuntimeFullScenariosTest.java`
- `core/prompt/DefaultPromptBuilderTest.java`
- `core/context/DefaultContextEngineTest.java`
- `core/context/DefaultContextCompressorEdgeCasesTest.java`
- `core/context/DefaultContextReferenceServiceFullTest.java`
- `core/state/DefaultAgentStateTest.java`

---

## 4. Tool registry и execution

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `SpringToolRegistry` | сканирование `@AgentTool`, формирование `ToolDefinition`, фильтр по toolset, `execute`, `registerDynamic`, дубликаты | Unit | P0 | ❌ нет |
| `ToolExecutionService` | retry на RuntimeException, timeout, truncate output, ignore IllegalArgumentException, interrupt | Unit | P0 | 🔄 partial (`ToolExecutionServiceRetryTest` есть, но нет timeout/truncate) |
| `AgentTool` / `ToolParam` / `ToolHandler` | аннотации корректно читаются рефлексией | Unit | P1 | ❌ нет |

### Рекомендуемые тестовые файлы
- `core/tool/SpringToolRegistryTest.java`
- `core/tool/ToolExecutionServiceTimeoutTest.java`
- `core/tool/ToolExecutionServiceTruncateTest.java`
- `core/tool/ToolAnnotationReflectionTest.java`

---

## 5. Инструменты (`tools.*`)

### 5.1 Файловые инструменты

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `ReadFileTool` | чтение существующего, ограничение max-chars, несуществующий файл, директория, unsafe path | Unit + Integration (temp dir) | P0 | 🔄 partial (`FileToolsTest` есть) |
| `WriteFileTool` | запись, перезапись, unsafe path, ограничение | Unit + Integration | P0 | 🔄 partial |
| `PatchTool` | replace, patch mode, невалидный patch, file not found | Unit + Integration | P0 | ❌ нет |
| `SearchFilesTool` | поиск по content/files, regex, glob, limit | Unit + Integration | P1 | 🔄 partial |

### 5.2 Терминал и execute code

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `TerminalTool` | выполнение команды, timeout, вывод/ошибка, max-timeout, unsafe команды | Unit + Integration | P0 | 🔄 partial (`TerminalToolTest` есть) |
| `ProcessTool` | фоновый процесс, poll/log/kill | Integration | P1 | ❌ нет |
| `ExecuteCodeTool` | запуск Python-кода, output capture, timeout | Unit + Integration | P1 | ❌ нет |

### 5.3 Web

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `WebSearchTool` | DDG/SearXNG результаты, лимит, empty query | Live | P1 | ✅ есть `WebToolsIntegrationTest` |
| `WebExtractTool` | извлечение markdown из HTML, max-chars, timeout, PDF | Live | P1 | ✅ есть |
| `WebSearchTool` + `WebExtractTool` | mock HTTP клиент, без внешнего интернета | Unit | P1 | ❌ нет |

### 5.4 Браузер

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `CdpClient` | подключение к CDP, send команд, обработка событий, reconnect | Unit + Live | P0 | ❌ нет |
| `BrowserService` | navigate/click/type/snapshot/console/getImages/scroll/back/press/dialog | Live | P1 | 🔄 partial (`BrowserToolsLiveTest`, `BrowserAgentRuntimeLiveTest`) |
| `ChromiumLauncher` | запуск с args, headless, user-data-dir, ошибка запуска | Live | P2 | ✅ есть `ChromiumLauncherTest` |
| `ChromiumAutoStart` | auto-start по property, fallback на external CDP | Integration | P1 | ✅ есть `ChromiumAutoStartLiveTest` |
| `ChromiumDownloader` | download/unzip/verify, кеширование, retry | Unit + Live | P2 | ✅ есть `ChromiumDownloaderTest` |
| `ChromiumPlatform` / `ChromiumRevisionResolver` | определение OS/ARCH, выбор revision | Unit | P2 | ✅ есть |
| Все `Browser*Tool` | each tool definition, argument mapping, integration with `BrowserService` | Unit | P1 | ❌ нет (агрегатный тест) |

### 5.5 Vision

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `VisionAnalyzeTool` | base64 encoding, request to model client, parse response, error handling | Unit + Live | P1 | ✅ есть `VisionAnalyzeNoOpLiveTest` |
| `BrowserVisionTool` | screenshot via CDP + vision analyze | Live | P1 | ❌ нет |

### 5.6 Gateway-инструмент

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `SendMessageTool` | вызов gateway adapter, обработка результата | Unit | P0 | 🔄 partial (`SendMessageToolTest` есть) |

### 5.7 Memory / skills / todo

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `MemoryTool` | save/recall/forget, similarity search, H2/Postgres | Integration | P1 | ❌ нет |
| `SessionSearchTool` | поиск по сессиям, snippet | Integration | P1 | ❌ нет |
| `SkillViewTool` / `SkillsListTool` / `SkillManageTool` | загрузка skill, list, create/delete | Integration | P1 | ❌ нет |
| `TodoTool` | create/update/list, сессионная привязка | Integration | P1 | ❌ нет |
| `ClarifyTool` | форматирование clarify-запроса | Unit | P1 | ❌ нет |

### 5.8 MCP и делегация

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `McpTool` | вызов MCP tool, маппинг аргументов, ошибки | Integration | P1 | ❌ нет |
| `DelegateTaskTool` | создание sub-agent, таймаут, результат | Unit + Integration | P1 | ❌ нет |

---

## 6. Gateway и Telegram

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `GatewayRoutingService` | `send` для всех платформ, `dispatchInbound` вызывает processor | Unit | P0 | ✅ есть `GatewayRoutingServiceTest` |
| `BasePlatformAdapter` | default методы, форматирование | Unit | P2 | ❌ нет |
| `TelegramAdapter` | send/sendTyping, chatId extraction, error mapping | Unit | P0 | ✅ есть `TelegramAdapterTest` |
| `TelegramBotApiClient` | `sendMessage`, `sendChatAction`, retry, non-200, malformed JSON | Unit + Integration (MockServer) | P0 | ❌ нет |
| `TelegramRestClientFactory` | read/connect timeout для polling и обычного клиента | Unit | P0 | ❌ нет |
| `TelegramLongPollingService` | poll loop, offset advancement, process message, stop, auth filter, error recovery | Unit + Integration | P0 | 🔄 partial (`TelegramLongPollingServiceTest` есть, но без цикла) |
| `TelegramWebhookController` | auth по ID/username, allow-by-default, parse update, dispatch | Unit (`@WebMvcTest`) | P0 | ✅ есть `TelegramWebhookControllerAuthTest` |
| `InboundMessageProcessor` | resolve/create session, runTurn, send response, transactional isolation | Unit + Integration | P0 | ❌ нет |

### Рекомендуемые тестовые файлы
- `gateway/telegram/TelegramBotApiClientTest.java`
- `gateway/telegram/TelegramRestClientFactoryTest.java`
- `gateway/telegram/TelegramLongPollingLoopTest.java`
- `gateway/InboundMessageProcessorTest.java`

---

## 7. Безопасность и санитайзер

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `DefaultUrlSafety` | разрешённые схемы, blocked hosts, domain match, punycode | Unit | P0 | ✅ есть `DefaultUrlSafetyTest` |
| `DefaultFileSafety` | allowed paths, blocked commands, traversal | Unit | P0 | ✅ есть `DefaultFileSafetyTest` |
| `DefaultToolGuardrails` | approval tools, disabled approvals, always-require | Unit | P0 | ✅ есть `DefaultToolGuardrailsTest` |
| `DefaultRedactor` | маскирование токенов, паролей, env | Unit | P0 | ✅ есть `DefaultRedactorTest` |
| `DefaultMessageSanitizer` | удаление system injection, truncation | Unit | P0 | ✅ есть |
| `ApprovalGate` / `ApprovalQueue` | approve/reject/timeout, thread-safety | Unit + Integration | P1 | ❌ нет |

---

## 8. Persistence и репозитории

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `SessionRepository` | findByUserId, save, unique constraint | `@DataJpaTest` | P0 | ❌ нет |
| `MessageRepository` | save/findBySessionId, pagination | `@DataJpaTest` | P0 | ❌ нет |
| `MemoryRepository` | embedding search, save/recall | `@DataJpaTest` | P1 | ❌ нет |
| `SkillRepository` | findByName, enabled filter | `@DataJpaTest` | P1 | ❌ нет |
| `TodoRepository` | findBySessionId, status filter | `@DataJpaTest` | P1 | ❌ нет |
| `AuditLogRepository` | save/list | `@DataJpaTest` | P1 | ❌ нет |
| `CompressionLockRepository` | lock/unlock | `@DataJpaTest` | P2 | ❌ нет |

---

## 9. Модельные клиенты

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `NoOpModelClient` | echo, empty tools, streaming stub | Unit | P0 | ❌ нет |
| `LangChain4jModelClient` | request mapping, response parsing, tool calls, streaming, error handling | Unit + Live | P0 | ❌ нет |
| `StreamingResponseHandler` | обработка SSE-чанков, ошибки в потоке | Unit | P0 | ❌ нет |
| `McpLifecycleManager` | connect/disconnect, tool list, lifecycle events | Unit | P1 | ✅ есть `McpLifecycleManagerUnitTest` |
| `JacksonMcpJsonMapper` | сериализация/десериализация | Unit | P2 | ❌ нет |

---

## 10. Сервисы (`service.*`)

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `AgentRuntimeService` | chat endpoint business logic, session lookup, response formatting | Unit + Integration | P0 | ❌ нет |
| `AgentStreamingService` | SSE streaming, error propagation, completion event | Unit + Integration | P0 | ❌ нет |
| `SessionTitleService` | auto-title generation, fallback | Unit + Integration | P1 | ❌ нет |

---

## 11. CLI

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `AgentCliRunner` | запуск с профилем `cli,noop`, REPL loop, exit command, exception handling | Integration | P1 | ❌ нет |

---

## 12. Health и resilience

| Класс | Что проверять | Тип | Приоритет | Статус |
|-------|---------------|-----|-----------|--------|
| `ResilienceBeansExistTest` | retry/circuit breaker/rate limiter бины созданы | Integration | P0 | ✅ есть |
| `ModelHealthIndicator` | UP/DOWN по модели | Integration | P1 | ❌ нет |
| `BrowserHealthIndicator` | UP/DOWN по CDP | Integration | P1 | ❌ нет |
| `DatabaseHealthIndicator` | UP/DOWN по БД | Integration | P1 | ❌ нет |

---

## 13. Инфраструктура и Docker

| Что проверять | Тип | Приоритет | Статус |
|---------------|-----|-----------|--------|
| CI pipeline: `./gradlew test`, `./gradlew bootJar`, smoke slim Docker | CI | P0 | ✅ есть `.github/workflows/ci.yml` |
| `Dockerfile` / `Dockerfile.slim` | образ собирается, health UP | Integration | P1 | ✅ есть smoke test в CI |
| `.env.example` / документация | актуальность | Manual | P2 | ❌ нет |

---

## Приоритетная очерёдность

### Sprint 1 — P0 core (без внешних сервисов)
1. `AgentPropertiesTest`, `AgentConfigProfilesTest`
2. `SpringToolRegistryTest`, `ToolExecutionServiceTimeoutTest`, `ToolExecutionServiceTruncateTest`
3. `AgentRuntimeFullScenariosTest`
4. `DefaultPromptBuilderTest`, `DefaultContextEngineTest`
5. `TelegramBotApiClientTest`, `TelegramRestClientFactoryTest`, `TelegramLongPollingLoopTest`, `InboundMessageProcessorTest`
6. `GlobalExceptionHandlerTest`, `RateLimitFilterTest`
7. `SessionRepositoryTest`, `MessageRepositoryTest`

### Sprint 2 — P0 security + API + services
1. `ApprovalGateTest`, `ApprovalQueueTest`
2. `AgentControllerUnitTest`, `ChatCompletionsControllerTest`, `VisionControllerTest`
3. `AgentRuntimeServiceTest`, `AgentStreamingServiceTest`
4. `NoOpModelClientTest`, `LangChain4jModelClientUnitTest`

### Sprint 3 — P1 tools + persistence
1. `PatchToolTest`, `Browser*Tool` unit-агрегат
2. `MemoryToolTest`, `TodoToolTest`, `Skill*ToolTest`
3. `WebSearch/WebExtract` mock unit-тесты
4. `McpToolTest`, `DelegateTaskToolTest`
5. `CompressionLockRepositoryTest`, `AuditLogRepositoryTest`

### Sprint 4 — P2 live + infra
1. `AgentCliRunnerTest`
2. `BrowserVisionToolTest`
3. Актуализация live-тестов при изменении API инструментов
4. Документация покрытия

---

## Конвенции для новых тестов

- Unit-тесты: JUnit 5 + Mockito + AssertJ. Не поднимать Spring-контекст.
- Integration-тесты: `@SpringBootTest` с профилем `test` (H2 in-memory), `@AutoConfigureMockMvc`, Testcontainers для Postgres.
- Live-тесты: `@Tag("live")`, не запускать в `./gradlew test`; запускать `./gradlew test -PincludeLive` или отдельно.
- Именование: `<ClassUnderTest>Test.java` для unit, `<ClassUnderTest>IntegrationTest.java` для integration, `<ClassUnderTest>LiveTest.java` для live.
- Для тестов с рефлексией/аннотациями использовать вспомогательный `@AgentTool`-stub в `test/java/com/azhukov/agent/tools/fixtures/`.

---

## Как мерить прогресс

После каждого спринта запускать:

```bash
./gradlew test jacocoTestReport
```

Целевые метрики:
- line coverage ≥ 75%
- branch coverage ≥ 60%
- все P0-классы покрыты хотя бы одним тестом
- live-тесты не ломают CI
