# План: Выделение Telegram-бота в отдельное приложение

> **Цель:** Вынести Telegram-бота из монолитного `java-agent` в отдельное Spring Boot приложение `telegram-bot`, которое работает как полноценный Telegram-гейт, перенимая фишки Telegram Gateway.

---

## 1. Текущее состояние

### 1.1 Что есть сейчас в `java-agent/backend`

| Компонент | Файл | Описание |
|-----------|------|----------|
| `TelegramAdapter` | `gateway/telegram/TelegramAdapter.java` | Адаптер: send/sendImage/sendDocument/sendTyping |
| `TelegramBotApiClient` | `gateway/telegram/TelegramBotApiClient.java` | HTTP-клиент Telegram Bot API |
| `TelegramLongPollingService` | `gateway/telegram/TelegramLongPollingService.java` | Long-polling через `getUpdates` |
| `TelegramWebhookController` | `gateway/telegram/TelegramWebhookController.java` | Webhook-режим |
| `TelegramRestClientFactory` | `gateway/telegram/TelegramRestClientFactory.java` | RestClient-фабрика |
| `TelegramConfig` | `config/TelegramConfig.java` | Bean-конфигурация |
| `GatewayRoutingService` | `gateway/GatewayRoutingService.java` | Роутинг сообщений |
| `InboundMessageProcessor` | `gateway/InboundMessageProcessor.java` | Обработка inbound → runTurn → send |
| `SessionResolver` | `gateway/SessionResolver.java` | Резолв/создание сессии |
| `MessagePersistenceService` | `persistence/MessagePersistenceService.java` | Сохранение истории |

### 1.2 Проблемы текущего подхода

1. **Монолит**: бот打包 внутрь core-агента, невозможно деплоить отдельно
2. **Нет slash-команд** оригинала: `/new`, `/reset`, `/model`, `/status`, `/stop`, `/help`, `/memory`, `/skills`, `/context`, `/usage`, `/title`, `/resume`, `/sessions`, `/yolo`, `/verbose`, `/compress`, `/fast`, `/reasoning`, `/profile`, `/whoami`
3. **Нет message formatting**: Markdown → Telegram MarkdownV2/HTML не конвертируется
4. **Нет long message splitting**: сообщения >4096 символов обрезаются
5. **Нет typing refresh loop**: typing-индикатор сбрасывается через 5с и не обновляется
6. **Нет media inbound**: бот не принимает фото/документы/голосовые/стикеры
7. **Нет inline keyboard / callback query**: нет интерактивных кнопок
8. **Нет session context vars**: нет изоляции контекста между сессиями
9. **Нет reconnect watcher**: при обрыве long-polling нет авто-восстановления
10. **Нет batch buffering**: нет debounce для серии быстрых сообщений
11. **Нет busy session handling**: если пользователь шлёт 2 сообщения подряд, второе не ставится в очередь
12. **Нет edit message / streaming**: ответ не стримится, нет edit-на-месте
13. **Нет authorization по chat-id групп**: только user-id
14. **Нет webhook secret validation**: webhook принимает без проверки

---

## 2. Архитектура `telegram-bot` приложения

### 2.1 Структура проекта

```
java-agent/
├── backend/              # Core-агент (API, tools, LLM, MCP) — без Telegram
├── telegram-bot/         # ← НОВОЕ: отдельное Spring Boot приложение
│   ├── build.gradle
│   ├── src/main/java/com/azhukov/agent/bot/
│   │   ├── BotApplication.java
│   │   ├── config/
│   │   │   ├── BotProperties.java           # bot.* свойства
│   │   │   ├── BotConfig.java               # Bean wiring
│   │   │   └── TelegramClientConfig.java     # RestClient, proxy, timeouts
│   │   ├── client/
│   │   │   ├── TelegramClient.java          # Bot API: sendMessage, sendPhoto, editMessage, etc.
│   │   │   ├── TelegramMultipartClient.java # Multipart uploads
│   │   │   └── TelegramResponse.java        # Typed API responses
│   │   ├── polling/
│   │   │   ├── LongPollingService.java      # getUpdates loop + reconnect
│   │   │   └── UpdateParser.java            # Parse update → internal event
│   │   ├── webhook/
│   │   │   ├── WebhookController.java       # POST /webhook/telegram
│   │   │   └── WebhookSecretValidator.java  # X-Telegram-Bot-Api-Secret-Token
│   │   ├── commands/
│   │   │   ├── CommandRegistry.java         # Регистрация slash-команд
│   │   │   ├── CommandHandler.java          # Interface
│   │   │   ├── impl/
│   │   │   │   ├── NewSessionCommand.java   # /new — очистка контекста
│   │   │   │   ├── ResetCommand.java        # /reset — полный сброс сессии
│   │   │   │   ├── StatusCommand.java       # /status — текущее состояние
│   │   │   │   ├── StopCommand.java         # /stop — прервать текущий turn
│   │   │   │   ├── HelpCommand.java         # /help — список команд
│   │   │   │   ├── ModelCommand.java        # /model — сменить модель
│   │   │   │   ├── MemoryCommand.java       # /memory — manage memory
│   │   │   │   ├── SkillsCommand.java       # /skills — список скиллов
│   │   │   │   ├── ContextCommand.java      # /context — контекст сессии
│   │   │   │   ├── UsageCommand.java        # /usage — токены/итерации
│   │   │   │   ├── TitleCommand.java        # /title — задать заголовок сессии
│   │   │   │   ├── SessionsCommand.java    # /sessions — список сессий
│   │   │   │   ├── YoloCommand.java         # /yolo — toggle approvals
│   │   │   │   ├── VerboseCommand.java      # /verbose — детальный вывод
│   │   │   │   ├── FastCommand.java         # /fast — быстрый режим
│   │   │   │   └── ReasoningCommand.java    # /reasoning — уровень reasoning
│   │   ├── formatting/
│   │   │   ├── MarkdownConverter.java       # Markdown → Telegram MarkdownV2
│   │   │   ├── MessageSplitter.java         # Split >4096 chars
│   │   │   └── HtmlFormatter.java           # HTML-форматирование (опция)
│   │   ├── media/
│   │   │   ├── InboundMediaHandler.java     # Фото/документ/голос → текст/tool
│   │   │   ├── MediaCache.java              # Кэш медиа-файлов
│   │   │   └── MediaDownloader.java         # getFile → download
│   │   ├── keyboard/
│   │   │   ├── InlineKeyboardBuilder.java   # Построение inline-кнопок
│   │   │   └── CallbackQueryHandler.java    # Обработка callback_query
│   │   ├── session/
│   │   │   ├── BotSessionStore.java         # Session management (DB-backed)
│   │   │   ├── BotSessionContext.java       # Per-session state (busy, queue)
│   │   │   └── SessionKeyBuilder.java       # Ключ сессии (platform + chatId + userId)
│   │   ├── auth/
│   │   │   ├── AuthorizationService.java    # User-id / username / chat-id проверка
│   │   │   └── PairingService.java          # Pairing codes (опционально)
│   │   ├── typing/
│   │   │   └── TypingManager.java           # Refresh typing каждые 4с до завершения turn
│   │   ├── streaming/
│   │   │   ├── StreamEditor.java            # Edit-message streaming (ответ растёт на месте)
│   │   │   └── StreamConsumer.java          # Потребляет LLM-stream и обновляет message
│   │   ├── core/
│   │   │   ├── BotMessageProcessor.java     # Inbound → agent-backend API → response → Telegram
│   │   │   ├── AgentBackendClient.java      # HTTP-клиент к backend /api/v1/agent/chat
│   │   │   └── BusySessionHandler.java      # Очередь сообщений для занятой сессии
│   │   └── lifecycle/
│   │       ├── BotLifecycleManager.java     # Startup, shutdown, reconnect
│   │       └── ReconnectWatcher.java        # Авто-восстановление polling
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/
│   │       └── V1__bot_schema.sql           # Таблицы бота (bot_sessions, bot_messages, bot_media)
│   └── src/test/java/...
├── settings.gradle                          # include 'backend', 'telegram-bot'
└── docker-compose.local.yml                 # + telegram-bot service
```

### 2.2 Связь с backend

```
Telegram User
     ↓
[telegram-bot] ←→ Telegram Bot API
     ↓ HTTP
[backend /api/v1/agent/chat]  ← LLM, tools, MCP, browser, vision
     ↓
PostgreSQL (shared)
```

**Два режима связи:**
1. **HTTP API** (по умолчанию): `telegram-bot` → `POST backend:8090/api/v1/agent/chat`
2. **Shared DB + in-process** (опционально): прямой доступ к `AgentRuntime` через shared библиотеку

Выбираем **HTTP API** — чистое разделение, независимый деплой.

### 2.3 Профили

| Профиль | Режим | Назначение |
|----------|-------|------------|
| `polling` | Long-polling | Dev/standalone, без публичного URL |
| `webhook` | Webhook | Prod, есть публичный HTTPS |
| `test` | H2 + mock | Тесты |

---

## 3. Детальный план доработок

### Этап 1: Базовая структура `telegram-bot` (Gradle, Spring Boot, config)

| # | Задача | Тесты |
|---|--------|-------|
| 1.1 | `settings.gradle` → `include 'backend', 'telegram-bot'` | — |
| 1.2 | `telegram-bot/build.gradle` — Spring Boot 4.1.0, Java 25, Groovy, Picocli, Flyway, SQLite/H2 | — |
| 1.3 | `BotApplication.java` — entry point, `@SpringBootApplication(scanBasePackages = "com.azhukov.agent.bot")` | `BotApplicationTest` — context loads |
| 1.4 | `BotProperties.java` — `bot.*` properties: token, polling-timeout, backend-url, allowed-user-ids, allowed-usernames, allow-by-default, max-message-length, typing-refresh-interval, stream-edit-interval | `BotPropertiesTest` — binding |
| 1.5 | `application.yml` — polling/webhook/test профили | — |
| 1.6 | `V1__bot_schema.sql` — `bot_sessions`, `bot_messages`, `bot_media_cache` | — |

### Этап 2: Telegram Bot API клиент

| # | Задача | Тесты |
|---|--------|-------|
| 2.1 | `TelegramClient.java` — typed-обёртка: `sendMessage`, `editMessageText`, `deleteMessage`, `sendChatAction`, `sendPhoto`, `sendDocument`, `sendVoice`, `getFile`, `answerCallbackQuery`, `setMyCommands` | `TelegramClientTest` — mock RestClient, проверка payload |
| 2.2 | `TelegramMultipartClient.java` — multipart upload для photo/document/voice | `TelegramMultipartClientTest` — multipart body verification |
| 2.3 | `TelegramResponse.java` — typed response: `ok`, `result`, `error_code`, `description` | `TelegramResponseTest` — parse success/error |
| 2.4 | `TelegramClientConfig.java` — `RestClient` bean с timeout, proxy, base-url override | `TelegramClientConfigTest` — bean creation |

### Этап 3: Long-polling + webhook

| # | Задача | Тесты |
|---|--------|-------|
| 3.1 | `LongPollingService.java` — getUpdates loop, offset tracking, auto-reconnect with exponential backoff, `@ConditionalOnProperty(bot.mode=polling)` | `LongPollingServiceTest` — mock HTTP, проверка offset, reconnect |
| 3.2 | `UpdateParser.java` — parse raw update JSON → typed `UpdateEvent` (text, command, callback_query, photo, document, voice, location) | `UpdateParserTest` — parse всех типов update |
| 3.3 | `WebhookController.java` — `POST /webhook/telegram`, `@ConditionalOnProperty(bot.mode=webhook)` | `WebhookControllerTest` — MockMvc |
| 3.4 | `WebhookSecretValidator.java` — проверка `X-Telegram-Bot-Api-Secret-Token` header, fail-closed | `WebhookSecretValidatorTest` — valid/invalid/missing |
| 3.5 | `ReconnectWatcher.java` — фон-поток, detects polling failure, re-launches with backoff | `ReconnectWatcherTest` — simulated failure |
| 3.6 | `BotLifecycleManager.java` — `@EventListener(ApplicationReadyEvent)`: delete stale webhook → start polling OR register webhook → setMyCommands | `BotLifecycleManagerTest` — startup sequence |

### Этап 4: Authorization

| # | Задача | Тесты |
|---|--------|-------|
| 4.1 | `AuthorizationService.java` — проверка user-id, username, chat-id; wildcard `*`; group chat allowlist; fail-closed by default | `AuthorizationServiceTest` — allow/deny scenarios |
| 4.2 | `PairingService.java` (опционально) — pairing codes для unknown users | `PairingServiceTest` — generate + approve |
| 4.3 | Интеграция в `BotMessageProcessor` — unauthorized → ignore/pair | `BotMessageProcessorAuthTest` |

### Этап 5: Slash-команды (перенос из оригинала)

| # | Команда | Описание | Тесты |
|---|---------|----------|-------|
| 5.1 | `/new` | Сброс контекста сессии (history → пусто), сохраняя session ID | `NewSessionCommandTest` |
| 5.2 | `/reset` | Полный сброс: новая сессия, новая история | `ResetCommandTest` |
| 5.3 | `/status` | Текущая модель, сессия, токены, активные задачи | `StatusCommandTest` |
| 5.4 | `/stop` | Прерывание текущего turn (interrupt) | `StopCommandTest` |
| 5.5 | `/help` | Список всех доступных команд | `HelpCommandTest` |
| 5.6 | `/model` | Список моделей / переключение: `/model kimi-k2.6` | `ModelCommandTest` |
| 5.7 | `/memory` | Показать/добавить/удалить факты памяти | `MemoryCommandTest` |
| 5.8 | `/skills` | Список загруженных скиллов | `SkillsCommandTest` |
| 5.9 | `/context` | Контекст сессии: сообщения, токены, инструменты | `ContextCommandTest` |
| 5.10 | `/usage` | Токены за сессию/день, итерации, cost estimate | `UsageCommandTest` |
| 5.11 | `/title` | Задать заголовок сессии: `/title My Task` | `TitleCommandTest` |
| 5.12 | `/sessions` | Список сохранённых сессий пользователя | `SessionsCommandTest` |
| 5.13 | `/yolo` | Toggle approval gate (skip подтверждения) | `YoloCommandTest` |
| 5.14 | `/verbose` | Toggle детального вывода (tool calls visible) | `VerboseCommandTest` |
| 5.15 | `/fast` | Быстрый режим (lower max-turns, меньше reasoning) | `FastCommandTest` |
| 5.16 | `/reasoning` | Уровень reasoning: low/medium/high | `ReasoningCommandTest` |
| 5.17 | `CommandRegistry.java` | Регистрация, поиск по имени, help generation | `CommandRegistryTest` |

### Этап 6: Formatting & splitting

| # | Задача | Тесты |
|---|--------|-------|
| 6.1 | `MarkdownConverter.java` — Markdown → Telegram MarkdownV2: `**bold**` → `**bold**`, `*italic*` → `_italic_`, `~~strike~~` → `~~strike~~`, `` `code` `` → `` `code` ``, `[text](url)` → `[text](url)`, код-блоки, таблицы (упрощённые) | `MarkdownConverterTest` — все элементы |
| 6.2 | `MessageSplitter.java` — split >4096 chars by paragraph/code-block boundaries; каждый chunk ≤4096 UTF-16 code units | `MessageSplitterTest` — long text, code blocks, edge cases |
| 6.3 | `HtmlFormatter.java` — альтернативный HTML-режим (`<b>`, `<i>`, `<code>`, `<pre>`) | `HtmlFormatterTest` |

### Этап 7: Typing refresh + streaming

| # | Задача | Тесты |
|---|--------|-------|
| 7.1 | `TypingManager.java` — запускает `sendChatAction(typing)` раз в 4 секунды до завершения turn; stop на success/error | `TypingManagerTest` — start/stop, refresh interval |
| 7.2 | `StreamEditor.java` — edit-message streaming: шлёт первый chunk как new message, затем edits каждые N сек (config: `bot.stream.edit-interval-ms=1500`); chunked до финального | `StreamEditorTest` — edit sequence, final message |
| 7.3 | `StreamConsumer.java` — потребляет SSE-stream от backend `/api/v1/agent/chat/stream`, передаёт в `StreamEditor` | `StreamConsumerTest` — mock SSE |

### Этап 8: Media inbound/outbound

| # | Задача | Тесты |
|---|--------|-------|
| 8.1 | `InboundMediaHandler.java` — обработка photo/document/voice: download via `getFile`, cache, формирует описание для LLM (фото → vision, документ → текст, голос → transcription stub) | `InboundMediaHandlerTest` |
| 8.2 | `MediaDownloader.java` — `getFile` → download file by `file_path` | `MediaDownloaderTest` |
| 8.3 | `MediaCache.java` — кэш: `file_id` → local path; cleanup по TTL | `MediaCacheTest` |
| 8.4 | Outbound: `sendPhoto` с `MEDIA:`-префиксом в ответе LLM (как в оригинале) — парсинг `MEDIA:/path` → photo/document | `MediaOutboundTest` |

### Этап 9: Inline keyboards & callback queries

| # | Задача | Тесты |
|---|--------|-------|
| 9.1 | `InlineKeyboardBuilder.java` — построение `InlineKeyboardMarkup` для команд (`/model` → список моделей, `/reasoning` → low/medium/high) | `InlineKeyboardBuilderTest` |
| 9.2 | `CallbackQueryHandler.java` — обработка `callback_query`: parse `data`, route к command, `answerCallbackQuery` | `CallbackQueryHandlerTest` |

### Этап 10: Session management

| # | Задача | Тесты |
|---|--------|-------|
| 10.1 | `BotSessionStore.java` — DB-backed: `bot_sessions` table (session_id, user_id, chat_id, title, created_at, updated_at, active, model_override, yolo_mode, verbose_mode, fast_mode, reasoning_level) | `BotSessionStoreTest` |
| 10.2 | `BotSessionContext.java` — in-memory per-session state: busy flag, message queue, current turn future (для `/stop`) | `BotSessionContextTest` |
| 10.3 | `SessionKeyBuilder.java` — ключ: `telegram:{chatId}:{userId}` (agent-style) | `SessionKeyBuilderTest` |
| 10.4 | `BusySessionHandler.java` — если сессия занята: queue message, либо interrupt (config: `bot.busy-mode=queue|interrupt`) | `BusySessionHandlerTest` |

### Этап 11: Backend integration

| # | Задача | Тесты |
|---|--------|-------|
| 11.1 | `AgentBackendClient.java` — HTTP-клиент к backend: `POST /api/v1/agent/chat` (sync) + `GET /api/v1/agent/chat/stream` (SSE) | `AgentBackendClientTest` — mock server |
| 11.2 | `BotMessageProcessor.java` — orchestration: auth → command-or-message → session → typing → backend-call → format → send | `BotMessageProcessorTest` — full flow mock |
| 11.3 | Backend: добавить endpoint `GET /api/v1/agent/sessions/{userId}` для `/sessions` | `SessionListEndpointTest` |
| 11.4 | Backend: добавить endpoint `POST /api/v1/agent/session/{sessionId}/reset` для `/reset` | `SessionResetEndpointTest` |
| 11.5 | Backend: вынести Telegram-код из `backend` в `telegram-bot` (удалить `gateway/telegram/*`, `TelegramConfig`, `TelegramLongPollingService` из backend) | `BackendNoTelegramTest` — backend стартует без Telegram |

### Этап 12: Docker & deployment

| # | Задача | Тесты |
|---|--------|-------|
| 12.1 | `telegram-bot/Dockerfile` | — |
| 12.2 | `docker-compose.local.yml` — + service `telegram-bot`, depends_on `agent`, env vars | — |
| 12.3 | `docker-compose.yml` — prod-версия | — |
| 12.4 | Health check endpoint в bot: `GET /bot/health` | `BotHealthTest` |

---

## 4. Сравнение фишек Оригинал vs текущих vs план

| Фишка | Текущий java-agent | План `telegram-bot` | Этап |
|--------------|--------------------|---------------------|------|
| Long-polling с reconnect | ✅ есть, но без reconnect | Reconnect watcher + backoff | 3 |
| Webhook с secret | ✅ есть, но без secret | Secret validation, fail-closed | 3 |
| Slash-команды (30+) | ❌ нет | 16 ключевых команд | 5 |
| Markdown → MarkdownV2 | ❌ нет | Конвертер + splitter | 6 |
| Long message splitting | ❌ нет | Split по 4096 chars | 6 |
| Typing refresh loop | ❌ нет (разовый) | Refresh каждые 4с | 7 |
| Streaming edit-message | ❌ нет | Edit на месте из SSE | 7 |
| Media inbound (photo/doc/voice) | ❌ нет | Download + cache + vision | 8 |
| Media outbound (MEDIA: prefix) | ❌ нет | Парсинг MEDIA: → sendPhoto | 8 |
| Inline keyboards | ❌ нет | Для /model, /reasoning | 9 |
| Callback queries | ❌ нет | answerCallbackQuery | 9 |
| Session store (DB) | ✅ есть (SessionEntity) | Bot-своя таблица + backend sync | 10 |
| Busy session handling | ❌ нет | Queue/interrupt mode | 10 |
| Authorization (user/chat/group) | ✅ частично (user-id, username) | + chat-id groups + wildcard | 4 |
| Pairing codes | ❌ нет | Опционально | 4 |
| setMyCommands при старте | ❌ нет | Регистрация команд | 3 |
| Text batch debounce | ❌ нет | Опционально (future) | — |
| Session context vars | ❌ нет | Per-session state | 10 |
| /stop interrupt | ❌ нет | CompletableFuture cancel | 5 |
| /yolo (skip approvals) | ❌ нет | Toggle в session | 5 |
| /verbose (tool calls visible) | ❌ нет | Toggle в session | 5 |
| /usage (token tracking) | ❌ нет | Backend API + display | 5 |
| /context (context breakdown) | ❌ нет | Backend API + display | 5 |
| /sessions (list sessions) | ❌ нет | Backend API + display | 5 |
| /title (session title) | ❌ нет | Backend API + display | 5 |
| /model (switch model) | ❌ нет | Backend API + keyboard | 5 |
| /fast (quick mode) | ❌ нет | Lower max-turns + reasoning | 5 |
| /reasoning (level) | ❌ нет | Low/medium/high | 5 |

---

## 5. Очередность реализации

```
Этап 1  →  Этап 2  →  Этап 3  →  Этап 4
         (база)      (клиент)    (polling)    (auth)

Этап 5  →  Этап 6  →  Этап 7
(команды)   (format)   (typing/stream)

Этап 8  →  Этап 9  →  Этап 10 →  Этап 11 →  Этап 12
(media)     (keyboard) (session)  (backend)  (docker)
```

**MVP (этапы 1–4 + 6 + 7.1)**: отдельный бот с long-polling, auth, formatting, typing refresh, basic message flow через backend API.

**Phase 2 (этапы 5 + 7.2–3 + 8)**: slash-команды, streaming, media.

**Phase 3 (этапы 9–12)**: keyboards, session management, backend cleanup, Docker.

---

## 6. Изменения в `backend`

| Что | Действие |
|-----|----------|
| `gateway/telegram/*` | Удалить из backend (перенесено в telegram-bot) |
| `config/TelegramConfig.java` | Удалить |
| `gateway/GatewayRoutingService.java` | Упростить — убрать Telegram adapter wiring |
| `gateway/InboundMessageProcessor.java` | Удалить — заменён на `BotMessageProcessor` в bot |
| `gateway/SessionResolver.java` | Оставить в backend для API Server adapter |
| `gateway/telegram/TelegramLongPollingService.java` | Удалить |
| `api/AgentController.java` | Оставить — используется bot-ом |
| `api/ChatCompletionsController.java` | Оставить — OpenAI-compatible endpoint |
| `config/AgentProperties.GatewayProperties.TelegramProperties` | Оставить, но unused в backend (bot использует свои `BotProperties`) |
| Новые endpoints | `GET /api/v1/agent/sessions/{userId}`, `POST /api/v1/agent/session/{id}/reset`, `GET /api/v1/agent/session/{id}/context` |
| Flyway миграции | Добавить `bot_sessions`, `bot_messages` таблицы (shared DB) |

---

## 7. Покрытие тестами

| Модуль | Минимум тестов | Coverage target |
|--------|----------------|-----------------|
| `client/` | 8 | LINE ≥90% |
| `polling/` | 5 | LINE ≥85% |
| `webhook/` | 4 | LINE ≥90% |
| `commands/` | 20 (по 1+ на команду) | LINE ≥90% |
| `formatting/` | 10 | LINE ≥95% |
| `media/` | 6 | LINE ≥80% |
| `keyboard/` | 4 | LINE ≥85% |
| `session/` | 6 | LINE ≥85% |
| `auth/` | 8 | LINE ≥95% |
| `typing/` | 3 | LINE ≥85% |
| `streaming/` | 4 | LINE ≥80% |
| `core/` | 8 | LINE ≥85% |
| **Итого** | ~86 | **LINE ≥88%** |

---

## 8. Риски и решения

| Риск | Решение |
|------|---------|
| Backend не имеет нужных endpoints | Добавить в рамках Этапа 11 |
| Двойное long-polling (bot + backend) | Backend: убрать `TelegramLongPollingService`, отключить `long-polling.enabled` |
| Shared DB: bot пишет в sessions table | Bot использует свои `bot_*` таблицы, backend — свои |
| MarkdownV2 escape errors | Fallback на plain text при ошибке send |
| Telegram rate limits (30 msg/s) | Rate limiter в `TelegramClient` |
| Webhook vs polling конфликт | `BotLifecycleManager` deletes stale webhook перед polling |

---

*Документ создан для планирования выделения Telegram-бота в отдельное приложение.*