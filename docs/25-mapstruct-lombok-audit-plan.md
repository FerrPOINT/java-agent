# План доработок: MapStruct + Lombok + README

Статус на основе аудита `/opt/dev/java-agent`.

## 1. Аудит MapStruct

### 1.1. Где используется сейчас
**MapStruct в проекте не используется.** Поиск по `mapstruct`, `MapStruct`, `@Mapper`, `@Mapping`, `Mappers.getMapper` во всём репозитории не дал результатов.

### 1.2. Где MapStruct нужен
Преобразования между слоями выполняются вручную (`toDto`, `toEntity`, inline mapping). Постоянные пары источник→цель:

| Source | Target | Где сейчас |
|--------|--------|------------|
| `MessageEntity` | `Message` / `MessageDto` | `AgentRuntimeService`, тесты |
| `SessionEntity` | `Session` / `SessionSummaryDto` | `AgentRuntimeService`, `SessionTitleService` |
| `UsageEntity` | `UsageDto` | `UsageTracker` |
| `MemoryEntity` | `MemoryDto` | `MemoryStore`, `MemoryTool`, `AgentRuntimeService` |
| `PendingMemoryEntity` | `PendingMemoryDto` | `BackgroundReviewService` |
| `SkillEntity` | `Skill` / DTO | `SkillManager`, `DatabaseSkillManager` |
| `AuditLogEntity` | `AuditLog` | `AuditLogRepository` / сервисы |
| `CheckpointEntity` | `Checkpoint` | `CheckpointManager` |
| `TodoEntity` | `TodoItem` | `TodoRepository`, `TodoCommand` |
| `CronJobEntity` | `CronJob` | `CronJobService` |
| `McpOAuthEntity` | `McpOAuth` | `McpOAuthRepository` |
| `CompressionLockEntity` | `CompressionLock` | `ContextCompressor` |
| OpenAI DTO (`OpenAiChatRequest` ↔ `OpenAiChatResponse`) | внутренние модели | `LangChain4jModelClient` |
| Bot entities (`BotMessageEntity`, `BotSessionEntity`) | domain | `BotSessionStore`, `BotMessageProcessor` |

### 1.3. Критичность
- **Medium-High.** Ручной mapping размножён по сервисам, легко расходится при изменении полей. MapStruct уберёт boilerplate и сделает mapping централизованным.

### 1.4. Блокеры внедрения
- Java 25 + Spring Boot 4.1: нужна MapStruct 1.6.x (поддержка Java 25).
- Annotation processor должен идти после Lombok processor (order: `lombok`, `mapstruct-processor`).
- Некоторые преобразования не pure 1:1 — есть flattening/nested logic. Их оставить вручную или в `@Mapper(uses = ...)`.

---

## 2. Аудит Lombok

### 2.1. Текущая статистика

| Модуль | Всего Java-файлов | С Lombok | % |
|--------|-------------------|----------|---|
| `backend` | 234 | 103 | 44% |
| `telegram-bot` | 109 | 40 | 36% |
| **Итого** | **343** | **143** | **41%** |

### 2.2. Где Lombok уже используется корректно
- Entity-классы JPA (`@Entity` + `@Data`) — `SessionEntity`, `MessageEntity` и др.
- Сервисы с зависимостями (`@RequiredArgsConstructor` + `@Slf4j`) — `AgentRuntimeService`, `CronJobService`, `UsageTracker` и др.
- Конфигурационные properties (`@Getter` + `@Setter`) — `AgentProperties`, `GuardrailConfig`.
- Bot: `BotMessageProcessor`, `AuthorizationService`, `MediaDownloader` и др.

### 2.3. Где Lombok отсутствует и должен быть внедрён

#### 2.3.1. Backend без Lombok (131 файл)

Категории:

| Категория | Количество | Примеры | Приоритет |
|-----------|------------|---------|-----------|
| JPA Repositories | 13 | `SessionRepository`, `MessageRepository` | **Low** — интерфейсы, Lombok не применим |
| DTO (records) | 0 — все DTO сейчас `class` | `ChatRequest`, `ChatResponseDto`, `UsageDto` | **High** — переписать на `record` или `@Data` |
| Core model | ~22 | `Message`, `ToolCall`, `ToolResult`, `ChatResponse`, `TurnResult` | **High** — чистые data classes |
| Gateway model | 6 | `SendResult`, `MessageEvent`, `PlatformConfig`, `SessionSource` | **High** — records/data |
| Gateway services | 3 | `GatewayRoutingService`, `BasePlatformAdapter`, `TelegramRestClientFactory` | **Medium** — `GatewayRoutingService` можно `@RequiredArgsConstructor` |
| Tool definitions | ~10 | `ToolDefinition`, `ToolResult`, `ToolContext` | **High** |
| Health indicators | 4 | `ModelHealthIndicator`, `McpHealthIndicator`, `BrowserHealthIndicator`, `ChromiumHealthIndicator` | **Medium** — `@Slf4j` + `@RequiredArgsConstructor` |
| Security | ~14 | `FileSafety`, `Redactor`, `UrlSafety`, `DefaultToolCallGuardrail` | **Medium** — интерфейсы и реализации с зависимостями |
| Config | 4 | `AgentConfig`, `FlywayConfig`, `TelegramConfig`, `AgentProperties` (уже Lombok) | **Low** — `@Configuration` классы не нуждаются |
| CLI | 1 | `AgentCliRunner` | **Low** |
| Persistence entities | 0 без Lombok | все 12 entity уже `@Data` | — |

#### 2.3.2. Telegram-bot без Lombok (69 файлов)

| Категория | Количество | Примеры | Приоритет |
|-----------|------------|---------|-----------|
| Command handlers | ~48 | `StartCommand`, `HelpCommand`, `ModelCommand` (Lombok) и т.д. | **Medium** — многие уже Lombok, оставшиеся однотипные |
| Repositories | 5 | `BotSessionRepository`, `PairingCodeRepository` | **Low** — интерфейсы |
| Formatting | 3 | `ResponseFilter`, `MessageSplitter`, `MarkdownConverter` | **Low** |
| Keyboard builders | 3 | `ModelKeyboardBuilder`, `ProviderKeyboardBuilder` | **Medium** — `@RequiredArgsConstructor` |
| Models/DTO | 3 | `TelegramResponse`, `UpdateEvent`, `KeyboardButton` | **High** — records/data |
| Webhook/health | 2 | `WebhookSecretValidator`, `BotHealthController` | **Medium** |

### 2.4. Что делать с records
- **DTO в `api/dto/`** (`ChatRequest`, `ChatResponseDto`, `UsageDto` и 21 другой) — это идеальные кандидаты на `record`. Переписать на records с backward-compatible constructors.
- **Core model** (`Message`, `ToolCall`, `ToolResult`, `ChatResponse`) — большинство уже могут быть records. Нужно проверить мутабельность и Jackson/JSON-сериализацию.
- **Gateway model** (`SendResult`, `MessageEvent`, `PlatformConfig`, `SessionSource`) — переписать на records, кроме `MessageEvent`, который может содержать builder/factory методы.

---

## 3. README

### 3.1. Чего не хватает
README не упоминает:
- **Lombok** (хотя это стековая зависимость и миграционный приоритет).
- **MapStruct** (отсутствует, но при планируемом внедрении должен быть описан).
- **E2E-тестирование** (`slowTest`, Docker Compose E2E) — добавлено в CI, но не документировано для разработчика.
- **CI/CD** workflow (`.github/workflows/ci.yml` обновлён).
- **Coverage** данные устарели: в README `725 тестов`, фактически `1414 unit + 60 slow`.

### 3.2. Рекомендуемые изменения README
1. Добавить Lombok 1.18.38 в таблицу стека.
2. Добавить раздел «Code style / Lombok convention».
3. Добавить MapStruct в стек после внедрения.
4. Обновить coverage/test counts.
5. Добавить раздел «E2E tests» с командами `slowTest` и `scripts/e2e-docker-compose-test.sh`.
6. Добавить раздел «CI pipeline».

---

## 4. Полный план доработок

### Phase 0. Подготовка (1 день)
- [ ] MAP-0.1: Добавить MapStruct 1.6.x в `backend/build.gradle` и `telegram-bot/build.gradle`.
- [ ] MAP-0.2: Настроить annotation processor order: `lombok` → `mapstruct-processor`.
- [ ] MAP-0.3: Создать базовый `MapStructConfig` с общими константами (`componentModel = "spring"`, `unmappedTargetPolicy = ERROR/WARN`).
- [ ] LOM-0.1: Обновить README: добавить Lombok в стек, coverage, E2E раздел.

### Phase 1. Lombok миграция — backend (2–3 дня)
- [ ] LOM-1.1: Перевести 23 DTO в `api/dto/` на `record` (или `@Data`/`@Value`, если требуется мутируемость).
- [ ] LOM-1.2: Перевести core model (`Message`, `ToolCall`, `ToolResult`, `ChatResponse`, `TurnResult`, `ToolDefinition`, `ToolContext`, `ContextReference`, `Session`, `Role`) на records/`@Value`.
- [ ] LOM-1.3: Перевести gateway model (`SendResult`, `PlatformConfig`, `SessionSource`) на records.
- [ ] LOM-1.4: Добавить `@Slf4j` + `@RequiredArgsConstructor` в health indicators (`ModelHealthIndicator`, `McpHealthIndicator`, `BrowserHealthIndicator`, `ChromiumHealthIndicator`).
- [ ] LOM-1.5: Добавить `@RequiredArgsConstructor` + `@Slf4j` в сервисы/компоненты с final-зависимостями: `GatewayRoutingService`, `DefaultToolCallGuardrail`, `DefaultFileSafety`, `DefaultRedactor`, `DefaultUrlSafety`, `DefaultIterationBudget`, `NoOpMemoryProvider`, `DefaultAgentState`, `DefaultAgentConstants`, `DefaultPromptBuilder`, `NoOpSkillManager`, `MemoryStore`, `MemoryThreatScanner`, `TurnStateManager`, `ToolResultClassifier`, `ExecuteCodeTool`, `ProcessTool`, `PatchTool`, `SearchFilesTool`, `WebSearchTool`, `WebExtractTool`, `BrowserService`, `ClarifyTool`, `MemoryTool`, `MessageSanitizer`, `SsrfSafeHttpClient`, `SecretRedactor`, `UserInputSanitizer`, `UrlSafetyHandler`, `FileSafetyValidator`, `CommandApprovalManager`, `TurnUsageCollector`, `ThinkScrubber`, `TranscriptionProvider`, `TtsProvider`, `ImageGenProvider`, `OpenAiImageGenProvider`, `OpenAiTtsProvider`, `EdgeTtsProvider`, `TranscriptionService`, `TtsService`, `AgentStreamingService`.
- [ ] LOM-1.6: Проверить и обновить unit-тесты после каждой миграции (особенно для `@Value`/record — equals/hashCode меняются).

### Phase 2. Lombok миграция — telegram-bot (1–2 дня)
- [ ] LOM-2.1: Добавить `@RequiredArgsConstructor` + `@Slf4j` в command handlers без Lombok (~48 файлов), где есть зависимости.
- [ ] LOM-2.2: Перевести DTO/model (`TelegramResponse`, `UpdateEvent`, `KeyboardButton`) на records/`@Value`.
- [ ] LOM-2.3: Добавить Lombok в keyboard builders (`ModelKeyboardBuilder`, `ProviderKeyboardBuilder`) и `WebhookSecretValidator`, `BotHealthController`.

### Phase 3. MapStruct внедрение — backend (3–4 дня)
- [ ] MAP-1.1: Создать mappers пакет `com.azhukov.agent.api.mapper` и `com.azhukov.agent.persistence.mapper`.
- [ ] MAP-1.2: Entity → Domain mappers: `MessageMapper`, `SessionMapper`, `UsageMapper`, `MemoryMapper`, `PendingMemoryMapper`, `SkillMapper`, `AuditLogMapper`, `CheckpointMapper`, `TodoMapper`, `CronJobMapper`, `McpOAuthMapper`, `CompressionLockMapper`.
- [ ] MAP-1.3: Domain → DTO mappers: `ChatResponseMapper`, `MemoryDtoMapper`, `UsageDtoMapper`, `SessionSummaryMapper`, `ContextInfoMapper`.
- [ ] MAP-1.4: OpenAI DTO ↔ Internal model mapper: `OpenAiMapper`.
- [ ] MAP-1.5: Заменить ручной mapping в сервисах на инжекцию мапперов.
- [ ] MAP-1.6: Добавить unit-тесты для мапперов (MapStruct + `unmappedTargetPolicy = ERROR` ловит regressions).

### Phase 4. MapStruct внедрение — telegram-bot (1 день)
- [ ] MAP-2.1: Mappers для bot entities → domain (`BotMessageMapper`, `BotSessionMapper`, `PairingCodeMapper`).
- [ ] MAP-2.2: Заменить ручной mapping в `BotSessionStore`, `BotMessageProcessor`.

### Phase 5. Документация и CI (1 день)
- [ ] DOC-1: Обновить README:
  - добавить Lombok и MapStruct в стек;
  - обновить coverage/test counts (1414 + 60 slow);
  - добавить раздел «E2E tests»;
  - добавить раздел «MapStruct mapping conventions».
- [ ] DOC-2: Создать/обновить `docs/24-lombok-mapstruct-conventions.md` с правилами:
  - когда использовать `@Data`, `@Value`, `@RequiredArgsConstructor`, `@Slf4j`, records;
  - порядок annotation processors;
  - как писать MapStruct mappers и тесты.
- [ ] CI-1: Убедиться, что CI выполняет `./gradlew slowTest` и `./scripts/e2e-docker-compose-test.sh` (уже добавлено, проверить после MapStruct/Lombok).

### Phase 6. Валидация (1 день)
- [ ] VAL-1: `./gradlew test` — 1414+ tests green.
- [ ] VAL-2: `./gradlew :backend:slowTest` — 60+ slow tests green.
- [ ] VAL-3: `./scripts/e2e-docker-compose-test.sh` green.
- [ ] VAL-4: `./gradlew bootJar` для backend и telegram-bot green.
- [ ] VAL-5: Проверить JaCoCo coverage gate не упал (LINE ≥ 80%).

---

## 5. Оценка трудозатрат

| Phase | Оценка | Основной риск |
|-------|--------|---------------|
| 0. Подготовка | 1 день | annotation processor order |
| 1. Lombok backend | 2–3 дня | records ломают Jackson/MapStruct mapping, нужны тесты |
| 2. Lombok telegram-bot | 1–2 дня | command handlers однотипные, массово |
| 3. MapStruct backend | 3–4 дня | сложные/nested mappings, циклические зависимости |
| 4. MapStruct telegram-bot | 1 день | мало entities |
| 5. Документация | 1 день | — |
| 6. Валидация | 1 день | долгий full test run |
| **Итого** | **10–13 дней** | |

---

## 6. Приоритеты по влиянию

1. **Lombok DTO + core model → records** — максимально уменьшает boilerplate, безопасно.
2. **MapStruct entity → domain mappers** — убирает дублированный ручной mapping в сервисах.
3. **README + conventions** — уменьшает технический долг и onboarding friction.
4. **Lombok в command handlers telegram-bot** — рутинная массовая задача, низкий риск.
5. **MapStruct OpenAI / bot mappers** — вторично после backend mappers.

---

## 7. Что НЕ надо делать

- Не внедрять MapStruct ради самого MapStruct — только для пар с регулярным 1:1/1:N mapping.
- Не переводить `@Configuration` классы, интерфейсы и enums на Lombok.
- Не использовать `@Data` на JPA `@Entity` без `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` — риск циклических lazy-прокси в equals/hashCode.
- Не трогать records — они уже являются идеальным Lombok-заменителем.
