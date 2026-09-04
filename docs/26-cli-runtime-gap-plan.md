# План доработок java-agent (CLI + runtime)

> Составлен: 2026-07-31
> Репозиторий: `/opt/dev/java-agent`
> Текущее состояние: backend 1803 теста, cli 201, telegram-bot 637, все 0 failures
> CLI bootJar собирается, но `java -jar` падает на старте.

---

## P0 — критично (блокирует CLI и тестирование вручную)

### 1. CLI не стартует из jar: `BackendClient` — "No default constructor found"

**Что:** Spring Boot 4.1/Spring 7 не может выбрать конструктор у `BackendClient`, потому что их два и ни один не помечен `@Autowired`. `BackendProperties` + `@ConfigurationProperties` не включены через `@EnableConfigurationProperties`.
**Где:** `cli/src/main/java/com/azhukov/agent/cli/BackendClient.java`, `CliConfig.java`
**Чинить:**

- Добавить `@Autowired` на основной конструктор `BackendClient`.
- Добавить `@EnableConfigurationProperties(BackendProperties.class)` на `CliConfig`.
- Пересобрать `cli:bootJar` и проверить `java -jar ... --help`.
**Тесты:** новый `CliStartupTest` с `@SpringBootTest(webEnvironment = NONE)` или `ApplicationContextRunner`.

### 2. Несоответствие дефолтов итераций: `max-turns=90`, `max-model-calls-per-turn=5`

**Требование:** по умолчанию `100 на 100` в `application.yml` (или CLI).
**Где:** `backend/src/main/resources/application.yml`, `AgentProperties.CoreProperties`, `AgentProperties.BudgetProperties`.
**Чинить:**

- `core.max-turns: ${AGENT_CORE_MAX_TURNS:100}`
- `budget.max-model-calls-per-turn: ${AGENT_BUDGET_MAX_MODEL_CALLS_PER_TURN:100}`
- Поправить дефолты в `AgentProperties.CoreProperties` (90 → 100) и `BudgetProperties` (5 → 100).
- Добавить `AgentPropertiesDefaultsTest` на проверку дефолтов.

### 3. Backend endpoints, к которым обращается CLI, отсутствуют (13 штук)

**Список:**

- `/api/v1/agent/reasoning` — установить reasoning effort
- `/api/v1/agent/fast-mode` — toggle fast mode
- `/api/v1/agent/voice-mode` — toggle voice mode
- `/api/v1/agent/personality` — set personality
- `/api/v1/agent/plugins` — list plugins
- `/api/v1/agent/browser/connect` — CDP connect
- `/api/v1/agent/tools` — list tools
- `/api/v1/agent/tools/toggle` — enable/disable tool
- `/api/v1/agent/session/title` — set session title
- `/api/v1/agent/snapshot` — create state snapshot
- `/api/v1/agent/subgoal` — add subgoal criteria
- `/api/v1/agent/queue` — queue prompt for next turn
- `/api/v1/health` (telegram-bot `AgentBackendClient.health()` зовёт `/api/v1/agent/health`, которого нет)

**Чинить:** для каждого endpoint — `AgentController` mapping + `AgentRuntimeService` метод + реализация в runtime (или временно `501 Not Implemented` с честным сообщением). Предпочтительно: не возвращать 404, а либо реализовать, либо убрать команду из CLI.

### 4. CLI state (reasoning, fast, voice, personality, tools) не передаётся backend при chat

**Что:** `CliState` хранится только локально; `BackendClient.chatStream` не добавляет reasoning effort / fast mode / personality / tool states в тело запроса. Backend их не видит.
**Где:** `ChatRequest`, `AgentController.chat/streamChat`, `AgentRuntimeService.runTurn`, `LangChain4jModelClient`.
**Чинить:**

- Расширить `ChatRequest` полями: `reasoningEffort`, `fastMode`, `voiceMode`, `personality`, `disabledTools`, `enabledTools`.
- Передать из CLI в `BackendClient.chatStream`.
- Учесть в `DefaultAgentRuntime`/`LangChain4jModelClient` при формировании запроса к LLM.
- Покрыть тестами `ChatRequestDeserializationTest`, `AgentRuntimeConfigOverrideTest`.

---

## P1 — высокий (функциональные пробелы)

### 5. Reasoning effort / fast mode / voice mode не влияют на модель

**Что:** `LangChain4jModelClient.complete()` не передаёт `reasoningEffort`, `maxCompletionTokens`, `priority`, `voice`. `DefaultPromptBuilder` не добавляет reasoning config в system prompt.
**Где:** `LangChain4jModelClient.java`, `DefaultPromptBuilder.java`, `ModelProperties`.
**Чинить:**

- Добавить в `ModelProperties`: `reasoningEffort`, `fastMode`, `voiceMode`, `maxCompletionTokens`.
- Передать через `OpenAiChatRequestParameters.builder()` (или аналогичный провайдер-специфичный параметр).
- Добавить в system prompt блок `## Reasoning` с текущим уровнем.
- Тесты: `LangChain4jModelClientReasoningTest`, `DefaultPromptBuilderReasoningTest`.

### 6. SkillBundle install/uninstall бросает `UnsupportedOperationException`

**Где:** `SkillBundleService.java` (методы `install`/`uninstall`).
**Чинить:**

- Реализовать установку/удаление bundle (распаковка zip/jar, регистрация skills, миграция БД).
- Либо убрать endpoint из `AgentController` и команду `/install`/`/uninstall`, чтобы не обманывать пользователя.
- Тесты: `SkillBundleServiceTest`.

### 7. Memory approval toggle: `isMemoryApprovalEnabled()` hardcoded `false`

**Где:** `BackendClient.java` (CLI) и backend memory controller.
**Чинить:**

- Добавить endpoint `GET /api/v1/agent/memory/approval`.
- Хранить флаг в `Session` или глобальном конфиге.
- Обновить CLI.
- Тесты: `MemoryApprovalToggleTest`.

### 8. Cron jobs не исполняются по расписанию

**Что:** `CronJobController` есть, `CronJobService.create` есть, но нет `@Scheduled` задачи/executor, которая дергает `runBackground` по cron-выражению.
**Где:** `CronJobService.java`.
**Чинить:**

- Добавить `TaskScheduler` + `@Scheduled(fixedDelay=...)`, который читает активные cron jobs и запускает подходящие.
- Либо использовать `ThreadPoolTaskScheduler.schedule(...)` при создании job.
- Тесты: `CronJobServiceSchedulingTest`.

### 9. CLI команды — заглушки и отсутствующие

**Заглушки (stub):** `/goal`, `/subgoal`, `/personality`, `/credits`, `/kanban`, `/codex_runtime`.
**Отсутствуют:** `/queue`, `/steer`, `/diff`, `/cron`, `/curator`, `/suggestions`, `/blueprint`, `/reload`, `/start`, плюс 12 алиасов (`/q`, `/bp`, `/suggest`, `/sethome`, `/set-home`, `/fork`, `/gateway`, `/platforms`, `/tasks`, `/codex-runtime`, `/reload-mcp`, `/reload-skills`).
**Чинить:**

- Этап A (быстрые): `/start`, `/cron`, `/diff`, `/reload`, `/queue`, `/steer`, алиасы — ~13 тестов, 1 день.
- Этап B: `/credits`, `/personality`, `/curator` — ~9 тестов, 2–3 дня.
- Этап C: `/goal`, `/subgoal`, `/kanban`, `/codex_runtime` — ~19 тестов, 3–5 дней.

### 10. Telegram-bot `AgentBackendClient.health()` зовёт `/api/v1/agent/health`, endpoint — `/api/v1/health`

**Где:** `telegram-bot/.../AgentBackendClient.java`.
**Чинить:** изменить URL на `/api/v1/health` или `/actuator/health/readiness`.

---

## P2 — средний (техдолг, UX, архитектура)

### 11. Lombok миграция: 122 класса без Lombok

**Где:** в основном bot commands (42), backend core (~35), config/health/cli (~15).
**Чинить:**

- Партиями по 20–30 файлов: `@Slf4j` + `@RequiredArgsConstructor`/`@Data`.
- После каждой партии `./gradlew test`.
- Оценка: 1–2 дня.

### 12. CLI UX parity vs Hermes CLI

**Что:**

- Мульти-строчный ввод Alt+Enter есть, но не на всех терминалах.
- История ввода не сохраняется в файл (JLine `History` не настроен).
- Нет табличного рендера (`MarkdownRenderer` умеет tables, но streaming path не всегда).
- Нет confirmation для деструктивных команд (`DestructiveCommandConfirmation` есть, но не интегрирован в `ReplLoop`).
- `/save` сохраняет только `sessionId`, а не всю беседу.
**Чинить:** по приоритетам, см. `P1CliFixesTest`.

### 13. Architecture docs

**Требование:** ADR, C4 model, Mermaid sequence/component/ERD diagrams, design patterns catalog.
**Где:** `docs/architecture/`
**Чинить:**

- ADR-001: выбор Spring Boot + LangChain4j.
- C4 Level 1 (System) / Level 2 (Container) / Level 3 (Component) для backend/cli/bot.
- ERD диаграмма БД.
- Patterns: SessionResolver @Transactional fix (self-invocation), SteerBuffer, PromptCacheTracker.

---

## Рекомендуемая очерёдность

| Шаг | Задача | Тестов | Оценка |
|-----|--------|--------|--------|
| 1 | P0-1: CLI jar startup fix | 2 | 0.5 дня |
| 2 | P0-2: defaults 100×100 | 2 | 0.5 дня |
| 3 | P0-3: missing backend endpoints (13) — либо реализация, либо удаление команд | ~26 | 3–4 дня |
| 4 | P0-4: CLI state → backend chat request | ~8 | 2 дня |
| 5 | P1-5: reasoning/fast/voice в model client + prompt builder | ~10 | 2 дня |
| 6 | P1-6: SkillBundle install/uninstall | ~5 | 1–2 дня |
| 7 | P1-7: memory approval toggle | ~3 | 0.5 дня |
| 8 | P1-8: cron scheduling executor | ~5 | 1 дня |
| 9 | P1-9: telegram-bot health URL | ~2 | 0.25 дня |
| 10 | P1-10: CLI commands (A+B+C) | ~52 | 6–9 дней |
| 11 | P2-11: Lombok migration | — | 1–2 дня |
| 12 | P2-12: CLI UX parity | ~15 | 2–3 дня |
| 13 | P2-13: architecture docs | — | 1–2 дня |
| **Итого** | | **~128 тестов** | **~20–27 дней** |

---

## Критические замечания

- **CLI jar не работает сейчас** — нельзя начинать ручное тестирование без исправления P0-1.
- **Команды `/reasoning`, `/fast`, `/voice`, `/tools`, `/plugins`, `/title`, `/retry`, `/queue`, `/snapshot`, `/personality`, `/subgoal` есть в CLI, но бэкенд для них не реализован** — пользователь получит 404/ошибку.
- **Дефолтные 90×5 не соответствуют требованию 100×100** — нужно поменять и в YAML, и в Java-коде.
- **Reasoning effort настраивается только локально в CLI** — backend и модель не получают это значение.
