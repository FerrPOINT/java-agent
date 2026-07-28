# План: Доработка Telegram-бота до паритета с оригиналом Gateway

> **Цель:** Довести `telegram-bot` до полного паритета с оригиналом Telegram Gateway — добавить недостающие slash-команды, adapter-level фишки и структурные компоненты.

---

## 1. Текущее состояние

### 1.1 Что есть (32 базовых компонента ✅)

| Компонент | Статус |
|-----------|--------|
| Long-polling + reconnect watcher | ✅ |
| Webhook + secret validation (fail-closed) | ✅ |
| Authorization (user-id/username/chat-id/wildcard `*`) | ✅ |
| Typing refresh loop (4с) | ✅ |
| Markdown → MarkdownV2 конвертер | ✅ |
| Message splitting >4096 UTF-16 | ✅ |
| Streaming edit-message (SSE → StreamEditor) | ✅ |
| Inbound media (photo/doc/voice/sticker/animation) | ✅ |
| MEDIA: outbound (парсинг `MEDIA:/path` → sendPhoto) | ✅ |
| Inline keyboards + callback queries | ✅ |
| Busy session (queue/interrupt) | ✅ |
| Session store (DB-backed, BotSessionEntity) | ✅ |
| Rate limiter (Semaphore, 25 req/s) | ✅ |
| setMyCommands при старте (16 команд) | ✅ |
| BotProperties: agentName, workingDirectory, defaultModel | ✅ |
| 16 slash-команд с сохранением state в БД | ✅ |
| /status: agent name · model · context% · working dir | ✅ |
| Backend API: chat, chat/stream, reset, context, usage, sessions, memory, skills | ✅ |
| 221 тест, 0 failures | ✅ |

### 1.2 Чего не хватает

**A. Slash-команды: 30 из 45 отсутствуют**

**B. Adapter-level фишки: 27 отсутствуют**

**C. Структурные компоненты: 5 отсутствуют**

---

## 2. Категории доработок

### Категория A — Slash-команды (30 шт)

#### A1. Высокий приоритет (12 команд)

| # | Команда | Описание | Backend endpoint | Тесты |
|---|---------|----------|------------------|-------|
| A1.1 | `/footer` | Toggle runtime footer (model · context% · cwd в конце ответа). `on`/`off`/`status`. Сохраняется в `bot_sessions.footer_enabled` | — | `FooterCommandTest` (4) |
| A1.2 | `/resume` | Список предыдущих сессий + переключение: `/resume <name>`, `/resume list`. Inline keyboard для выбора | `GET /api/v1/agent/sessions/{userId}` (уже есть) | `ResumeCommandTest` (4) |
| A1.3 | `/version` | Версия агента, build info, Git commit. Из `BuildProperties` (Spring Boot actuator) | `GET /actuator/info` | `VersionCommandTest` (2) |
| A1.4 | `/whoami` | Информация о текущем пользователе: user-id, username, chat-id, авторизован ли, slash-access level | — | `WhoamiCommandTest` (3) |
| A1.5 | `/commands` | Полный список доступных команд (alias `/help`) с описанием. Отличие от `/help`: показывает также plugin-команды | — | `CommandsCommandTest` (2) |
| A1.6 | `/compress` | Ручное сжатие контекста сессии. Опциональный focus-topic: `/compress <topic>`. Backend суммаризует историю | `POST /api/v1/agent/session/{sessionId}/compress` (новый) | `CompressCommandTest` (3) |
| A1.7 | `/undo` | Откат N последних ходов (default 1): `/undo 3`. Soft-delete сообщений, evict agent cache | `POST /api/v1/agent/session/{sessionId}/undo?turns=N` (новый) | `UndoCommandTest` (3) |
| A1.8 | `/retry` | Повторить последний user-message: берёт последний user-input из истории, повторно отправляет | — (локально из истории) | `RetryCommandTest` (2) |
| A1.9 | `/approve` | Одобрить pending dangerous command. `/approve all` — все. `/approve session` — запомнить для сессии (связь с YOLO) | `POST /api/v1/agent/approve` (новый) | `ApproveCommandTest` (3) |
| A1.10 | `/deny` | Отклонить pending dangerous command. `/deny all` — все | `POST /api/v1/agent/deny` (новый) | `DenyCommandTest` (3) |
| A1.11 | `/agents` | Список активных агентов и задач. Backend отдаёт список running turns | `GET /api/v1/agent/agents` (новый) | `AgentsCommandTest` (2) |
| A1.12 | `/insights` | Usage insights — токены за день/неделю, top models, cost estimate | `GET /api/v1/agent/insights` (новый) | `InsightsCommandTest` (2) |

#### A2. Средний приоритет (10 команд)

| # | Команда | Описание | Backend endpoint | Тесты |
|---|---------|----------|------------------|-------|
| A2.1 | `/profile` | Активный профиль, home directory. Из `BotProperties` | — | `ProfileCommandTest` (2) |
| A2.2 | `/platform` | `list`/`pause`/`resume` — управление платформами. Только `list` для бота | — | `PlatformCommandTest` (2) |
| A2.3 | `/restart` | Drain active work → restart. Отправляет уведомление перед restart | `POST /api/v1/agent/restart` (новый) | `RestartCommandTest` (2) |
| A2.4 | `/reload_mcp` | Перезагрузить MCP-серверы | `POST /api/v1/agent/reload-mcp` (новый) | `ReloadMcpCommandTest` (2) |
| A2.5 | `/reload_skills` | Перезагрузить skills | `POST /api/v1/agent/reload-skills` (новый) | `ReloadSkillsCommandTest` (2) |
| A2.6 | `/bundles` | Список установленных skill bundles | `GET /api/v1/agent/bundles` (новый) | `BundlesCommandTest` (2) |
| A2.7 | `/branch` | Fork текущей сессии в независимую копию: `/branch [name]` | `POST /api/v1/agent/session/{sessionId}/branch` (новый) | `BranchCommandTest` (3) |
| A2.8 | `/background` | Запустить prompt в фоновой сессии: `/background <prompt>` | `POST /api/v1/agent/background` (новый) | `BackgroundCommandTest` (2) |
| A2.9 | `/topic` | Управление DM topic-сессиями (create/list/switch). Telegram forum-топики | — (локально) | `TopicCommandTest` (3) |
| A2.10 | `/set_home` | Установить текущий chat как home channel для платформы | — (сохранить в BotProperties) | `SetHomeCommandTest` (2) |

#### A3. Низкий приоритет (8 команд)

| # | Команда | Описание | Тесты |
|---|---------|----------|-------|
| A3.1 | `/voice` | Voice mode: on/off/tts/channel/leave/status. **Out of scope** — заглушка "Voice not supported" | `VoiceCommandTest` (1) |
| A3.2 | `/rollback` | Filesystem checkpoints: list/restore. Требует integration с terminal tool | `RollbackCommandTest` (1) |
| A3.3 | `/credits` | Nous credit balance. **Out of scope** — заглушка | `CreditsCommandTest` (1) |
| A3.4 | `/update` | Update the original agent. Для Java — `System.exit(1)` + restart script | `UpdateCommandTest` (1) |
| A3.5 | `/debug` | Upload debug report (логи + config summary). Отправляет файл | `DebugCommandTest` (2) |
| A3.6 | `/codex_runtime` | **Out of scope** — Codex-specific. Заглушка | `CodexRuntimeCommandTest` (1) |
| A3.7 | `/personality` | List/set personality. Заглока (нет personality system) | `PersonalityCommandTest` (1) |
| A3.8 | `/kanban` | Kanban CLI delegation. Заглока (нет kanban в боте) | `KanbanCommandTest` (1) |
| A3.9 | `/goal` | Goal management. Заглока (нет goal system) | `GoalCommandTest` (1) |
| A3.10 | `/subgoal` | Subgoal management. Заглока | `SubgoalCommandTest` (1) |

---

### Категория B — Adapter-level фишки (27 шт)

#### B1. Высокий приоритет (9 фишек)

| # | Фишка | Описание | Файлы | Тесты |
|---|--------|----------|-------|-------|
| B1.1 | **Runtime footer** | `model · context% · cwd` в конце каждого ответа. Config: `bot.footer.enabled`, `bot.footer.fields`. Парсится из backend response metadata | `RuntimeFooter.java` (новый), `BotMessageProcessor.java` (patch), `BotProperties.java` (patch) | `RuntimeFooterTest` (5) |
| B1.2 | **Message reactions** (👀/👍/👎) | `setMessageReaction` при начале обработки (👀), при успехе (👍), при ошибке (👎), при cancel (clear). Config: `bot.reactions.enabled` | `ReactionManager.java` (новый), `TelegramClient.java` (patch: `setMessageReaction`), `BotMessageProcessor.java` (patch) | `ReactionManagerTest` (4) |
| B1.3 | **Text batch/debounce** | Склейка быстрых сообщений (Telegram шлёт длинные сообщения частями). Adaptive delay: 180ms (short), 500ms (medium), 1200ms (near 4096 split). Config: `bot.text-batch.delay-ms`, `bot.text-batch.split-delay-ms` | `TextBatchDebouncer.java` (новый), `BotMessageProcessor.java` (patch) | `TextBatchDebouncerTest` (5) |
| B1.4 | **Photo batch/album** | Склейка фото-альбомов (media_group_id). Debounce 500ms, merge в одно событие | `PhotoBatchDebouncer.java` (новый), `BotMessageProcessor.java` (patch) | `PhotoBatchDebouncerTest` (4) |
| B1.5 | **Media group events** | Обработка альбомов как одного логического события. `_queue_media_group_event` → merge media_urls + media_types | `MediaGroupHandler.java` (новый), `UpdateEvent.java` (patch: `mediaGroupId` field) | `MediaGroupHandlerTest` (3) |
| B1.6 | **Thread reply mode** | `off`/`all`/`first` — reply_to_message_id в ответах. Config: `bot.reply-to-mode`. `first` (default) — только первый chunk ответа reply-to user message | `BotMessageProcessor.java` (patch: `sendFormatted` → `replyToMessageId`), `BotProperties.java` (patch) | `ThreadReplyTest` (3) |
| B1.7 | **Thread fallback** | Retry без `message_thread_id` при ошибке "Message thread not found". Для DM-topic chains | `TelegramClient.java` (patch: `sendMessage` → retry без thread_id) | `ThreadFallbackTest` (2) |
| B1.8 | **Group mention requirement** | В групповых чатах бот отвечает только при @mention. Config: `bot.group.require-mention`. Парсинг `@botname` в тексте | `GroupMessageFilter.java` (новый), `BotMessageProcessor.java` (patch) | `GroupMessageFilterTest` (4) |
| B1.9 | **Guest mode** | Незнакомые группы могут триггерить бота через @mention. Config: `bot.group.guest-mode` | `GroupMessageFilter.java` (patch) | (в GroupMessageFilterTest) |

#### B2. Средний приоритет (10 фишек)

| # | Фишка | Описание | Файлы | Тесты |
|---|--------|----------|-------|-------|
| B2.1 | **Sticker cache** | Кэш описаний стикеров по `file_unique_id`. Vision analysis → кэш → повторно не анализируется. `bot_sticker_cache` table | `StickerCache.java` (новый), `V1__bot_schema.sql` (patch: +table), `InboundMediaHandler.java` (patch) | `StickerCacheTest` (3) |
| B2.2 | **Model keyboard (paginated)** | Inline-клавиатура для `/model`: список моделей с пагинацией (8 на страницу). Callback: `mp:<model>` → set model | `ModelKeyboardBuilder.java` (новый), `ModelCommand.java` (patch: keyboard вместо текста) | `ModelKeyboardBuilderTest` (3) |
| B2.3 | **Provider keyboard** | Inline-клавиатура для выбора провайдера: `provider → model` drill-down. Callback: `pp:<slug>` → list models | `ProviderKeyboardBuilder.java` (новый), `CallbackQueryHandler.java` (patch) | `ProviderKeyboardBuilderTest` (2) |
| B2.4 | **Slash access policy** | Per-chat slash-command access control: `allow_admin_from` (admin), `user_allowed_commands` (non-admin). `/whoami` показывает access level. Config: `bot.auth.admin-user-ids`, `bot.auth.user-allowed-commands` | `SlashAccessPolicy.java` (новый), `BotMessageProcessor.java` (patch: gate commands) | `SlashAccessPolicyTest` (4) |
| B2.5 | **Pairing codes** | Code-based auth для unknown users: 8-char code, 1h expiry, max 3 pending. `/approve` от owner. Config: `bot.auth.pairing.enabled` | `PairingService.java` (новый), `AuthorizationService.java` (patch: pairing fallback) | `PairingServiceTest` (5) |
| B2.6 | **Observe unmentioned group msgs** | В режиме `require_mention`, неразмеченные сообщения сохраняются в контекст сессии (не триггерят ответ). Config: `bot.group.observe-unmentioned` | `GroupMessageFilter.java` (patch), `BotSessionStore.java` (patch: `appendContext`) | (в GroupMessageFilterTest) |
| B2.7 | **Forum commands in topics** | Регистрация команд в форум-топиках: `setMyCommands` с `scope` (chat, message_thread_id) | `BotLifecycleManager.java` (patch: scoped setMyCommands) | `ForumCommandsTest` (2) |
| B2.8 | **Response filters** | Фильтрация "silent" ответов (`***` — intentional silence). Не отправлять empty/silent responses | `ResponseFilter.java` (новый), `BotMessageProcessor.java` (patch) | `ResponseFilterTest` (2) |
| B2.9 | **Display config** | Per-platform display overrides: `display.platforms.telegram.tool-progress`, `display.platforms.telegram.preview-length`. Config: `bot.display.*` | `DisplayConfig.java` (новый), `BotProperties.java` (patch: `DisplayProperties`) | `DisplayConfigTest` (2) |
| B2.10 | **Channel prompt observation** | Обработка сообщений из connected channels (channel posts forwarded to group). Контекст без ответа | `GroupMessageFilter.java` (patch) | (в GroupMessageFilterTest) |

#### B3. Низкий приоритет (8 фишек)

| # | Фишка | Описание | Тесты |
|---|--------|----------|-------|
| B3.1 | DM topic sessions | Топики в DM для раздельных сессий. `ensure_dm_topic`, кэш topic→session | `DmTopicManagerTest` (3) |
| B3.2 | Allowed topics | Whitelist топиков в форумах. Config: `bot.group.allowed-topics` | `AllowedTopicsTest` (2) |
| B3.3 | Ignored threads | Blacklist thread_id. Config: `bot.group.ignored-threads` | `IgnoredThreadsTest` (2) |
| B3.4 | Free response chats | Чаты где бот отвечает без mention (override require_mention). Config: `bot.group.free-response-chats` | (в GroupMessageFilterTest) |
| B3.5 | Exclusive bot mentions | Только @botname триггерит (не any mention). Config: `bot.group.exclusive-bot-mentions` | (в GroupMessageFilterTest) |
| B3.6 | Location messages | Обработка координат: `lat,lon` → текст для LLM | `LocationHandlerTest` (2) |
| B3.7 | Link preview options | Отключение preview в ссылках: `disable_web_page_preview`. Config: `bot.link-preview` | `LinkPreviewTest` (1) |
| B3.8 | Polling fallback IPs | Резервные IP Telegram при DNS-блокировке. `discover_fallback_ips`, `parse_fallback_ip_env` | `FallbackIpTest` (2) |

---

### Категория C — Структурные компоненты (5 шт)

| # | Компонент | Описание | Файлы | Тесты |
|---|-----------|----------|-------|-------|
| C1 | **SlashAccessPolicy** | Per-platform slash command gating: admin vs user commands. `_ALWAYS_ALLOWED` = {help, whoami}. `can_run(userId, command)` проверка | `SlashAccessPolicy.java` (новый) | `SlashAccessPolicyTest` (4) |
| C2 | **PairingService** | Code-based auth: generate 8-char code, validate, approve. `bot_pairing_codes` table | `PairingService.java` (новый) | `PairingServiceTest` (5) |
| C3 | **RuntimeFooter** | Footer builder: `format(model, contextTokens, contextLength, cwd) → "kimi-k2.6 · 23% · ~/work"`. Config-driven fields | `RuntimeFooter.java` (новый) | `RuntimeFooterTest` (5) |
| C4 | **StickerCache** | Vision-described sticker descriptions: `get(fileUniqueId) → Optional<String>`, `put(fileUniqueId, description)`. `bot_sticker_cache` table | `StickerCache.java` (новый) | `StickerCacheTest` (3) |
| C5 | **DisplayConfig** | Per-platform display overrides resolver: `resolve(platform, key) → value`. Resolution order: platform override → global → default | `DisplayConfig.java` (новый) | `DisplayConfigTest` (2) |

---

## 3. Очередность реализации

```
Phase 1 (MVP+):  B1.1  B1.2  B1.3  B1.4  B1.5  B1.6  B1.7  B1.8  B1.9
                 A1.1  A1.2  A1.3  A1.4  A1.5  A1.6  A1.7  A1.8  A1.9  A1.10  A1.11  A1.12
                 C1    C3

Phase 2:         B2.1  B2.2  B2.3  B2.4  B2.5  B2.6  B2.7  B2.8  B2.9  B2.10
                 A2.1  A2.2  A2.3  A2.4  A2.5  A2.6  A2.7  A2.8  A2.9  A2.10
                 C2    C4    C5

Phase 3:         B3.1  B3.2  B3.3  B3.4  B3.5  B3.6  B3.7  B3.8
                 A3.1–A3.10
```

### Phase 1 — MVP+ (высокий приоритет)

**Adapter-level (9 фишек):**

| Этап | Фишка | Зависимости | Backend | Тестов |
|------|-------|-------------|---------|--------|
| 1.1 | Runtime footer (B1.1) | `BotProperties.footer` | `GET /api/v1/agent/session/{id}/context` (есть) | 5 |
| 1.2 | Message reactions (B1.2) | `TelegramClient.setMessageReaction` | — | 4 |
| 1.3 | Text batch/debounce (B1.3) | `BotProperties.textBatch` | — | 5 |
| 1.4 | Photo batch/album (B1.4) | B1.3 (shared debounce infra) | — | 4 |
| 1.5 | Media group events (B1.5) | `UpdateEvent.mediaGroupId` | — | 3 |
| 1.6 | Thread reply mode (B1.6) | `BotProperties.replyToMode` | — | 3 |
| 1.7 | Thread fallback (B1.7) | B1.6 | — | 2 |
| 1.8 | Group mention requirement (B1.8) | `BotProperties.group.requireMention` | — | 4 |
| 1.9 | Guest mode (B1.9) | B1.8 | — | (в 1.8) |

**Slash-команды (12 команд):**

| Этап | Команда | Зависимости | Backend | Тестов |
|------|---------|-------------|---------|--------|
| 1.10 | `/footer` (A1.1) | B1.1 (RuntimeFooter) | — | 4 |
| 1.11 | `/resume` (A1.2) | `BotSessionStore.listByUserId` (есть) | `GET /sessions/{userId}` (есть) | 4 |
| 1.12 | `/version` (A1.3) | `BuildProperties` (Spring Boot) | — | 2 |
| 1.13 | `/whoami` (A1.4) | C1 (SlashAccessPolicy) | — | 3 |
| 1.14 | `/commands` (A1.5) | `CommandRegistry` (есть) | — | 2 |
| 1.15 | `/compress` (A1.6) | — | `POST /session/{id}/compress` (новый) | 3 |
| 1.16 | `/undo` (A1.7) | — | `POST /session/{id}/undo?turns=N` (новый) | 3 |
| 1.17 | `/retry` (A1.8) | `BotMessageRepository` | — | 2 |
| 1.18 | `/approve` (A1.9) | — | `POST /agent/approve` (новый) | 3 |
| 1.19 | `/deny` (A1.10) | — | `POST /agent/deny` (новый) | 3 |
| 1.20 | `/agents` (A1.11) | — | `GET /agent/agents` (новый) | 2 |
| 1.21 | `/insights` (A1.12) | — | `GET /agent/insights` (новый) | 2 |

**Структурные (2 компонента):**

| Этап | Компонент | Тестов |
|------|-----------|--------|
| 1.22 | SlashAccessPolicy (C1) | 4 |
| 1.23 | RuntimeFooter (C3) | 5 |

**Итого Phase 1:** 23 этапа, ~75 тестов, 6 новых backend endpoints

### Phase 2 — Расширенный (средний приоритет)

**Adapter-level (10 фишек):**

| Этап | Фишка | Тестов |
|------|-------|--------|
| 2.1 | Sticker cache (B2.1) | 3 |
| 2.2 | Model keyboard paginated (B2.2) | 3 |
| 2.3 | Provider keyboard (B2.3) | 2 |
| 2.4 | Slash access policy (B2.4) | 4 |
| 2.5 | Pairing codes (B2.5) | 5 |
| 2.6 | Observe unmentioned group msgs (B2.6) | (в 1.8) |
| 2.7 | Forum commands (B2.7) | 2 |
| 2.8 | Response filters (B2.8) | 2 |
| 2.9 | Display config (B2.9) | 2 |
| 2.10 | Channel prompt observation (B2.10) | (в 1.8) |

**Slash-команды (10 команд):**

| Этап | Команда | Тестов |
|------|---------|--------|
| 2.11 | `/profile` (A2.1) | 2 |
| 2.12 | `/platform` (A2.2) | 2 |
| 2.13 | `/restart` (A2.3) | 2 |
| 2.14 | `/reload_mcp` (A2.4) | 2 |
| 2.15 | `/reload_skills` (A2.5) | 2 |
| 2.16 | `/bundles` (A2.6) | 2 |
| 2.17 | `/branch` (A2.7) | 3 |
| 2.18 | `/background` (A2.8) | 2 |
| 2.19 | `/topic` (A2.9) | 3 |
| 2.20 | `/set_home` (A2.10) | 2 |

**Структурные (3 компонента):**

| Этап | Компонент | Тестов |
|------|-----------|--------|
| 2.21 | PairingService (C2) | 5 |
| 2.22 | StickerCache (C4) | 3 |
| 2.23 | DisplayConfig (C5) | 2 |

**Итого Phase 2:** 23 этапа, ~55 тестов, 4 новых backend endpoints

### Phase 3 — Низкий приоритет

| Этап | Фишка/Команда | Тестов |
|------|---------------|--------|
| 3.1 | DM topic sessions (B3.1) | 3 |
| 3.2 | Allowed topics (B3.2) | 2 |
| 3.3 | Ignored threads (B3.3) | 2 |
| 3.4 | Free response chats (B3.4) | (в 1.8) |
| 3.5 | Exclusive bot mentions (B3.5) | (в 1.8) |
| 3.6 | Location messages (B3.6) | 2 |
| 3.7 | Link preview options (B3.7) | 1 |
| 3.8 | Polling fallback IPs (B3.8) | 2 |
| 3.9 | `/voice` (A3.1) — stub | 1 |
| 3.10 | `/rollback` (A3.2) | 1 |
| 3.11 | `/credits` (A3.3) — stub | 1 |
| 3.12 | `/update` (A3.4) | 1 |
| 3.13 | `/debug` (A3.5) | 2 |
| 3.14 | `/codex_runtime` (A3.6) — stub | 1 |
| 3.15 | `/personality` (A3.7) — stub | 1 |
| 3.16 | `/kanban` (A3.8) — stub | 1 |
| 3.17 | `/goal` (A3.9) — stub | 1 |
| 3.18 | `/subgoal` (A3.10) — stub | 1 |

**Итого Phase 3:** 18 этапов, ~23 теста

---

## 4. Изменения в backend

### 4.1 Новые endpoints (10 шт)

| Endpoint | Метод | Описание | Этап |
|----------|-------|----------|------|
| `/api/v1/agent/session/{sessionId}/compress` | POST | Сжатие контекста сессии | 1.15 |
| `/api/v1/agent/session/{sessionId}/undo?turns=N` | POST | Откат N ходов | 1.16 |
| `/api/v1/agent/approve` | POST | Одобрить pending command | 1.18 |
| `/api/v1/agent/deny` | POST | Отклонить pending command | 1.19 |
| `/api/v1/agent/agents` | GET | Список активных агентов | 1.20 |
| `/api/v1/agent/insights` | GET | Usage insights | 1.21 |
| `/api/v1/agent/session/{sessionId}/branch` | POST | Fork сессии | 2.17 |
| `/api/v1/agent/background` | POST | Фоновая сессия | 2.18 |
| `/api/v1/agent/reload-mcp` | POST | Перезагрузка MCP | 2.14 |
| `/api/v1/agent/reload-skills` | POST | Перезагрузка skills | 2.15 |

### 4.2 Новые таблицы (2 шт)

| Таблица | Назначение | Этап |
|---------|-----------|------|
| `bot_sticker_cache` | Кэш описаний стикеров (file_unique_id → description) | 2.1 |
| `bot_pairing_codes` | Pairing codes для unknown users | 2.5 |

### 4.3 Изменения в AgentRuntimeService (5 методов)

| Метод | Описание | Этап |
|-------|----------|------|
| `compressSession(UUID sessionId, String focus)` | Сжатие истории через LLM summary | 1.15 |
| `undoTurns(UUID sessionId, int turns)` | Soft-delete последних N ходов | 1.16 |
| `listActiveAgents()` | Список running turns | 1.20 |
| `getInsights(String userId)` | Usage analytics | 1.21 |
| `branchSession(UUID sessionId, String name)` | Fork сессии | 2.17 |

---

## 5. Изменения в BotProperties

| Свойство | Default | Описание | Этап |
|----------|---------|----------|------|
| `bot.footer.enabled` | `false` | Runtime footer on/off | 1.1 |
| `bot.footer.fields` | `[model, context_pct, cwd]` | Поля footer | 1.1 |
| `bot.reactions.enabled` | `false` | Message reactions | 1.2 |
| `bot.text-batch.delay-ms` | `500` | Text debounce delay | 1.3 |
| `bot.text-batch.split-delay-ms` | `1200` | Split-near-4096 delay | 1.3 |
| `bot.text-batch.fast-delay-ms` | `180` | Short message delay | 1.3 |
| `bot.reply-to-mode` | `first` | Thread reply: off/all/first | 1.6 |
| `bot.group.require-mention` | `false` | @bot required in groups | 1.8 |
| `bot.group.guest-mode` | `false` | Unknown groups via @mention | 1.9 |
| `bot.group.observe-unmentioned` | `false` | Observe unmentioned msgs | 2.6 |
| `bot.group.exclusive-bot-mentions` | `false` | Only @botname triggers | 3.5 |
| `bot.group.free-response-chats` | `[]` | Chats without mention requirement | 3.4 |
| `bot.group.allowed-topics` | `[]` | Forum topic whitelist | 3.2 |
| `bot.group.ignored-threads` | `[]` | Thread blacklist | 3.3 |
| `bot.auth.admin-user-ids` | `[]` | Slash command admins | 2.4 |
| `bot.auth.user-allowed-commands` | `[]` | Non-admin allowed commands | 2.4 |
| `bot.auth.pairing.enabled` | `false` | Pairing code auth | 2.5 |
| `bot.display.tool-progress` | `compact` | Tool progress display | 2.9 |
| `bot.display.preview-length` | `200` | Tool preview length | 2.9 |
| `bot.link-preview` | `true` | Link preview in messages | 3.7 |

---

## 6. Сводка по объёму

| Фаза | Этапов | Новых тестов | Новых backend endpoints | Новых таблиц | Новых Java-классов |
|------|--------|-------------|------------------------|-------------|-------------------|
| Phase 1 | 23 | ~75 | 6 | 0 | ~15 |
| Phase 2 | 23 | ~55 | 4 | 2 | ~12 |
| Phase 3 | 18 | ~23 | 0 | 0 | ~5 |
| **Итого** | **64** | **~153** | **10** | **2** | **~32** |

**Финальное состояние:**
- 45 slash-команд (паритет с оригиналом)
- 32 adapter-level фишки (паритет с оригиналом)
- ~374 теста (221 существующих + ~153 новых)
- 10 новых backend endpoints
- 2 новые таблицы

---

## 7. Риски и решения

| Риск | Решение |
|------|---------|
| Telegram API rate limits при reactions | `setMessageReaction` через rate limiter (Semaphore) |
| Text batch задерживает ответ | Adaptive delay tiers: 180ms для коротких, 1200ms для split-near-4096 |
| Media group debounce теряет order | Сохранять `message_id` в порядке получения |
| Slash access policy ломает существующие команды | Backward compat: если `admin-user-ids` пуст → gating disabled (как в оригинале) |
| Pairing codes безопасность | 8-char alphabet (без 0/O/1/I), 1h expiry, max 3 pending, rate limit 1/30s |
| Backend compress/undo атомарность | Транзакция + optimistic locking на session version |
| Sticker cache рост | TTL 30 дней, cleanup по cron |
| Forum commands scope | `setMyCommands` с `BotCommandScopeChat` — per-chat команды |

---

*Документ создан для планирования доработки telegram-bot до паритета с оригиналом Telegram Gateway.*