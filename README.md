# Java Agent

Spring Boot 4.1 + Java 25 + Gradle 9.6.1 (Groovy DSL) + Groovy 5 — Java-агент с поддержкой LLM, инструментов, Telegram-шлюза, MCP и CLI REPL.

## Стек

| Компонент | Версия |
|-----------|--------|
| Java | 25 LTS |
| Gradle | 9.6.1 |
| Groovy | 5.0.7 |
| Spring Boot | 4.1.0 |
| Spring Framework | 7.0.8 |
| PostgreSQL | 16 (dev) |
| LangChain4j | 1.18.0 |
| MCP Java SDK | 2.0.0 |
| Flyway | 12.4.0 (Spring Boot BOM) |
| PostgreSQL JDBC | 42.7.11 (Spring Boot BOM) |
| Hibernate ORM | 7.4.1.Final (Spring Boot BOM) |
| Jackson | 3.1.4 (Spring Boot BOM) |
| Resilience4j | 2.4.0 |
| Picocli | 4.7.7 |
| JLine | 4.3.1 |
| Pebble | 4.1.2 |
| Lombok | 1.18.38 |
| MapStruct | 1.6.3 |
| Testcontainers | 2.0.5 |

## Структура проекта

```
java-agent/
├── backend/                    # Backend: REST API, LLM client, tools, persistence
│   └── src/main/java/com/azhukov/agent/
│       ├── api/                # REST controllers (18) + DTO + mappers
│       ├── cli/                # Picocli / JLine REPL
│       ├── client/             # LLM clients (LangChain4j, NoOp) + MCP client
│       ├── config/             # AgentProperties, MapStructConfig, beans
│       ├── core/               # domain layer (AgentRuntime, tools, context, memory, skills, state, security)
│       ├── gateway/            # Telegram/webhook adapters + routing + steer
│       ├── persistence/        # JPA entities + repositories + mappers + Flyway
│       ├── security/           # SSRF-safe HTTP client, safety validators
│       ├── service/            # AgentRuntimeService, AgentStreamingService, TTS, transcription
│       └── tools/              # @AgentTool implementations (file, terminal, web, browser, memory, delegate, etc.)
├── telegram-bot/               # Telegram bot: 56 commands, streaming, polling, media
│   └── src/main/java/com/azhukov/agent/bot/
│       ├── api/                # Bot API DTOs
│       ├── auth/               # Authorization, pairing
│       ├── batch/              # Text/photo batch debouncers
│       ├── client/             # TelegramClient, RestClient config
│       ├── commands/           # 56 command handlers + CommandRegistry (10 aliases)
│       ├── config/             # BotProperties, BotConfig
│       ├── core/               # BotMessageProcessor, AgentBackendClient
│       ├── formatting/         # Markdown converter, response filter
│       ├── group/              # Group message filter
│       ├── keyboard/           # Inline keyboards (model/provider selection)
│       ├── lifecycle/          # Bot lifecycle
│       ├── media/              # Media cache, inbound media, MediaDeliveryService, location handler
│       ├── polling/            # Long polling, reconnect watcher, fallback IP resolver
│       ├── session/            # BotSessionEntity, BotSessionStore
│       ├── streaming/          # StreamEditor (edit-message + native draft streaming)
│       ├── typing/             # TypingManager
│       └── webhook/            # Webhook secret validator
├── cli/                        # CLI: standalone REPL, REST client to backend
│   └── src/main/java/com/azhukov/agent/cli/
│       ├── BackendClient.java  # REST methods to backend
│       ├── ReplLoop.java       # JLine interactive REPL with SSE streaming
│       ├── SlashCommandRegistry.java  # 92 slash commands
│       └── MarkdownRenderer.java # ANSI color markdown
├── docs/                       # Architecture docs
└── docker-compose.yml          # Production deployment
```

## Возможности

### LLM и инструменты

- OpenAI-compatible API (streaming, tool calls, reasoning effort)
- 36+ встроенных инструментов (file, terminal с PTY mode, web, browser, memory, skills, cron, delegation, TTS, image-gen, и др.)
- MCP client + server (MCP Java SDK 2.0.0)
- LangChain4j integration с fallback model support
- Context compression (tool dedup, sanitize, auto-focus, protect)
- Session checkpoints + rollback + undo
- Curator (skill consolidation, dry-run)

### Self-improvement (замкнутый цикл, 0.1.16–0.1.19)

Полный контур «ответ → фоновый ревью → память → следующий ход»:

- Счётчики нуджей работают на ОБОИХ путях хода (sync runtime + streaming/bot)
- User-сообщение персистится немедленно в начале хода (crash-persist)
- После каждого ответа — асинхронный background review (memory + skills), с задержкой, не блокирует ответ
- Ревью подавлен для subagent-сессий и cron-сессий (skip_background_review — parity с Hermes scheduler)
- Извлечённые факты пишутся в память (target=memory / target=user)
- **Оба блока подкладываются в системный промпт следующего хода** в формате Hermes: `USER PROFILE (who the user is)` + `MEMORY (your personal notes)`, разделители `═`×46, индикатор [N% — cur/limit chars], записи через `§`, лимиты 1375/2200 символов
- /reset полностью очищает runtime-состояние сессии (счётчики, локи, замороженный memory-префикс)

### Telegram bot

- 56 команд + 10 aliases
- Streaming (edit-message + native draft streaming)
- /model — клавиатура выбора модели + per-request override до LLM (request → bot_sessions.model_override → ChatRequest.model → per-request params)
- MEDIA: tags — автоматическая доставка файлов (images, video, audio, documents)
- Steer mode — инъекция сообщений в активный run
- Busy-ack — подтверждение при занятом агенте
- Commentary — промежуточные сообщения при tool execution
- Group chat filtering, inline keyboards, media cache

### CLI REPL

92 slash commands. Ключевые:

| Категория | Команды |
|-----------|---------|
| Сессии | `/new`, `/sessions`, `/resume`, `/branch`, `/checkpoint`, `/checkpoints`, `/rollback`, `/undo`, `/delete-checkpoint`, `/save`, `/export`, `/import`, `/replay`, `/history` |
| Контекст | `/compress`, `/context`, `/diff`, `/snapshot`, `/clear`, `/sweep` |
| Память | `/memory`, `/memory-all`, `/memory-pending`, `/memory-approve`, `/memory-reject`, `/memory-delete` |
| Skills | `/skills`, `/install`, `/uninstall`, `/bundles`, `/reload-skills` |
| Модель | `/model`, `/reasoning`, `/fast`, `/voice`, `/verbose`, `/yolo`, `/profile`, `/toolsets`, `/platforms` |
| Cron | `/cron`, `/cron-create`, `/cron-delete`, `/cron-pause`, `/cron-resume` |
| Delegation | `/agents`, `/handoff`, `/goal`, `/subgoal`, `/steer`, `/stop`, `/queue`, `/busy` |
| Инструменты | `/tools`, `/approve-tool`, `/deny-tool`, `/approvals`, `/browser` |
| Отладка | `/debug`, `/plan`, `/doctor`, `/health`, `/status`, `/statusbar`, `/usage`, `/gquota`, `/credits`, `/insights` |
| Аннотации | `/annotate`, `/suggestions`, `/redraw`, `/image`, `/editor` |
| Прочее | `/config`, `/personality`, `/title`, `/context`, `/insights`, `/curator`, `/codex_runtime`, `/plugins`, `/reload`, `/reload-mcp`, `/restart`, `/retry`, `/version`, `/whoami`, `/help`, `/exit`, `/quit` |

### REST API

| Endpoint | Method | Назначение |
|----------|--------|------------|
| `/api/v1/agent/chat` | POST | Chat (sync) |
| `/api/v1/agent/chat/stream` | POST | Chat (SSE streaming) |
| `/api/v1/agent/steer` | POST | Steer — инъекция сообщения в активный run |
| `/v1/chat/completions` | POST | OpenAI-compatible endpoint |
| `/v1/models` | GET | Список доступных моделей |
| `/v1/capabilities` | GET | Machine-readable API capabilities |
| `/v1/toolsets` | GET | Список toolsets и инструментов |
| `/api/v2/sessions` | GET, POST | List / create sessions (paginated) |
| `/api/v2/sessions/{id}` | GET, PATCH, DELETE | Session CRUD |
| `/api/v2/sessions/{id}/messages` | GET | Session messages |
| `/api/v2/sessions/{id}/chat` | POST | Chat within session (sync) |
| `/api/v2/sessions/{id}/chat/stream` | POST | Chat within session (SSE) |
| `/api/v1/memory/*` | GET, POST, DELETE | Memory management |
| `/api/v1/skills/*` | GET, POST, DELETE | Skills management |
| `/api/v1/checkpoints/*` | GET, POST | Checkpoints |
| `/api/v1/cron/*` | GET, POST, DELETE | Cron jobs |
| `/api/v1/kanban/*` | GET, POST | Kanban board |
| `/actuator/health` | GET | Health check |

## Конфигурация

### Основные опции

| Опция | Env var | Default | Описание |
|-------|---------|---------|----------|
| `agent.model.provider` | `AGENT_MODEL_PROVIDER` | `openai-compatible` | LLM провайдер |
| `agent.model.name` | `AGENT_MODEL_NAME` | — | Название модели |
| `agent.model.base-url` | `AGENT_MODEL_BASE_URL` | — | URL endpoint |
| `agent.model.api-key` | `AGENT_MODEL_API_KEY` | — | API-ключ |
| `agent.model.reasoning-effort` | — | `medium` | low/medium/high |
| `agent.model.fast-mode` | — | `false` | Быстрый режим (low tokens) |
| `agent.max-turns` | — | `100` | Максимум ходов |
| `agent.max-model-calls-per-turn` | — | `100` | Максимум вызовов модели за ход |

### Поведение и доставка

| Опция | Env var | Default | Описание |
|-------|---------|---------|----------|
| `agent.gateway.busy-input-mode` | `AGENT_BUSY_INPUT_MODE` | `interrupt` | `interrupt` / `queue` / `steer` — обработка сообщений при занятом агенте |
| `agent.gateway.busy-ack-enabled` | `AGENT_BUSY_ACK_ENABLED` | `true` | Отправлять busy-ack при mid-run сообщении |
| `agent.commentary-enabled` | `AGENT_COMMENTARY_ENABLED` | `true` | Commentary-сообщения при tool execution |
| `bot.streaming-transport` | `AGENT_STREAMING_TRANSPORT` | `auto` | `auto` / `edit` / `draft` / `off` — транспорт стриминга в Telegram |

### Безопасность

| Опция | Описание |
|-------|----------|
| `agent.security.redact-secrets` | Маскирование API-ключей, токенов, паролей |
| `agent.security.redact-pii` | Маскирование PII (email, phone, IP) |
| `agent.security.approval-gate` | Подтверждение деструктивных инструментов |

## Профили

| Профиль | Назначение |
|---------|------------|
| `dev` | Ollama Cloud / локальный endpoint, порт 8090, PostgreSQL localhost:5432 |
| `noop` | LLM-заглушка + H2 in-memory; для тестов и offline-разработки |
| `cli` | Активирует Picocli REPL |
| `prod` | Production endpoint (OpenAI / совместимый), INFO-логи |

## Быстрый старт

### Dev (real LLM через Ollama Cloud)

```bash
cd backend
export OLLAMA_API_KEY=***
java -jar build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev \
  --spring.datasource.password=project_workflow \
  --server.port=8090
```

### NoOp (без LLM, без PostgreSQL)

```bash
cd backend
java -jar build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=noop \
  --server.port=8090
```

### Streaming

```bash
curl -N -X POST http://localhost:8090/api/v1/agent/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"Привет"}'
```

### OpenAI-compatible

```bash
curl -s -X POST http://localhost:8090/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"kimi-k2.6","messages":[{"role":"user","content":"hi"}]}' | jq .
```

### CLI (standalone, REST client to backend)

```bash
# Start backend first
cd backend && ./gradlew bootJar
java -jar build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev &

# Start CLI
cd ../cli && ./gradlew bootJar
java -jar build/libs/java-agent-cli-0.0.1-SNAPSHOT.jar --backend.url=http://localhost:8090
```

92 slash commands, SSE streaming, JLine autocomplete. `/help` — список всех команд.

## Production

```bash
docker compose up --build      # production, порт 8080
docker compose -f docker-compose.local.yml up --build  # local dev, порты 18090/18091
```

- `docker-compose.prod.yml` — production (порт 8080, PostgreSQL 5432)
- `docker-compose.local.yml` — local dev (порт 18090, PostgreSQL 18091)
- `docker-compose.e2e.yml` — E2E testing
- Dockerfile: `eclipse-temurin:25-jre-noble` + Chromium runtime deps (full image)
- Dockerfile.slim: minimal image without bundled Chromium (installed at runtime)
- `server.shutdown: immediate` — workaround для graceful shutdown бага Spring Boot 4.1.0
- Health readiness включает только `db`

## Сборка и тесты

```bash
cd /opt/dev/java-agent
./gradlew :backend:test :telegram-bot:test :cli:test   # unit + fast integration (~4600)
./gradlew :backend:slowTest                            # @Tag("slow") на РЕАЛЬНОМ PostgreSQL
./gradlew jacocoTestReport                             # coverage report
./gradlew bootJar                                      # собрать jar
```

**slowTest — реальный PostgreSQL** (не H2): один shared singleton Testcontainer
(postgres:16-alpine, withReuse) на JVM; схемой владеет Flyway (ddl-auto=none).
Testcontainers ≥1.21.x требуется для Docker 29. Отдельные H2-стриминг-тесты
сохраняют create-drop.

**Текущее состояние**: ~2905 тестов (backend 1090 / bot 1498 / cli 317), CI green.

### Деплой (dev-лэддер)

```bash
# после зелёного CI:
./gradlew :backend:bootJar :telegram-bot:bootJar
cp backend/build/libs/backend-0.0.1-SNAPSHOT.jar /opt/java-agent/lib/java-agent-backend-<VER>.jar
cp telegram-bot/build/libs/telegram-bot-0.0.1-SNAPSHOT.jar /opt/java-agent/lib/java-agent-bot-<VER>.jar
echo <VER> > /opt/java-agent/VERSION
ln -sfn /opt/java-agent/lib/java-agent-backend-<VER>.jar /opt/java-agent/lib/java-agent-backend-latest.jar
ln -sfn /opt/java-agent/lib/java-agent-bot-<VER>.jar /opt/java-agent/lib/java-agent-bot-latest.jar
systemctl restart java-agent-backend java-agent-bot
# верификация: health + живой ход + journalctl
```

### E2E

```bash
./scripts/e2e-docker-compose-test.sh  # Docker Compose E2E (noop + PostgreSQL)
```

## Переменные окружения

| Переменная | Назначение |
|------------|------------|
| `AGENT_NAME` | Имя агента |
| `AGENT_MODEL_PROVIDER` | `openai-compatible`, `noop` |
| `AGENT_MODEL_BASE_URL` | URL endpoint |
| `AGENT_MODEL_API_KEY` | API-ключ |
| `AGENT_MODEL_NAME` | Название модели |
| `AGENT_MODEL_TIMEOUT_SECONDS` | Таймаут HTTP (default 600) |
| `AGENT_AUXILIARY_*` | Настройки auxiliary-модели |
| `AGENT_VISION_*` | Настройки vision |
| `AGENT_BUSY_INPUT_MODE` | `interrupt` / `queue` / `steer` |
| `AGENT_BUSY_ACK_ENABLED` | Busy-ack (default true) |
| `AGENT_COMMENTARY_ENABLED` | Commentary (default true) |
| `AGENT_STREAMING_TRANSPORT` | `auto` / `edit` / `draft` / `off` (bot) |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | PostgreSQL |
| `AGENT_BROWSER_CDP_URL` | URL Chrome DevTools Protocol |
| `AGENT_TERMINAL_*` | Таймауты терминала |
| `AGENT_SERVER_PORT` | Порт приложения (default 8090) |

## Документация

- `docs/README.md` — обзор
- `docs/01-scope.md` — границы проекта
- `docs/02-core-architecture.md` — архитектура
- `docs/03-dependency-map.md` — маппинг Python → Java
- `docs/04-proposed-java-structure.md` — модульная структура
- `docs/05-migration-notes.md` — нетривиальные моменты
- `docs/06-vision-browser.md` — vision и browser
- `docs/27-overall-gap-plan.md` — план доработок
- `docs/28-hermes-parity-audit.md` — parity аудит
- `docs/29-quality-audit-fixes.md` — quality аудит (45 исправлений)
- `docs/31-code-quality-audit.md` — deep code quality аудит (32 finding, ALL RESOLVED)
- `backend/docs/09-builtin-tools.md` — встроенные инструменты
- `backend/docs/10-production-readiness.md` — production readiness
- `backend/docs/11-chromium.md` — Chromium auto-install
- `backend/docs/12-streaming.md` — SSE streaming
- `backend/docs/13-production-hardening.md` — context compression и production packaging
- `backend/docs/conventions.md` — конвенции Lombok / Records / MapStruct