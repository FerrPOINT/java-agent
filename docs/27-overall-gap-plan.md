# План доработок Hermes + java-agent

## java-agent — текущий статус

| ID  | Задача | Статус | Примечания |
|-----|--------|--------|------------|
| P0-1 | CLI jar startup fix | ✅ | BackendClient @Autowired, @EnableConfigurationProperties(BackendProperties.class) |
| P0-2 | Defaults 100×100 | ✅ | max-turns=100, max-model-calls-per-turn=100 в application.yml + AgentProperties |
| P0-3 | Backend endpoints для CLI slash-команд | ✅ | AgentController + CliRuntimeSettingsService + SessionEntity.cliState |
| P0-4 | CLI state → backend chat request | ✅ | ChatRequest расширен, BackendClient передаёт CliState |
| P1-5 | Reasoning/fast/voice в model client + prompt builder | ✅ | ModelProperties.reasoningEffort/fastMode, buildParameters прокидывает reasoning |
| P1-9 | Telegram-bot health URL fix | ✅ | HealthController /health возвращает status=UP (Telegram bot health client) |
| verify | Сборка + тесты | ✅ | ./gradlew test BUILD SUCCESSFUL, CLI jar --help стартует |

## Hermes Python — текущий статус

| Задача | Статус | Примечания |
|--------|--------|------------|
| Дефолты max_turns=100 / max_iterations=100 | ✅ | hermes_cli/config.py + cli-config.yaml.example |
| CLI parser'ы `lsp`, `send`, `proxy`, `hooks` | ✅ | Уже подключены в top-level parser, --help работает |
| `migrate`, `fallback`, `secrets` | ✅ | Встроены в main.py, есть `func=` по умолчанию |
| `service_manager.py` NotImplementedError | ⚠️ | Ожидаемо для host-only backend (Windows/macOS unsupported) — не блокер |

## Что ещё не реализовано / требует решения

### 1. Hermes: отсутствующие top-level команды в документации — ✅
- `portal` — parser существует (`hermes_cli/portal_cli.py`), добавлен в `_SUBCOMMANDS` в `main.py`; `hermes portal --help` и `hermes portal status --help` работают.

### 2. Hermes: пустые/неподключённые модули
- `hermes_cli/send_cmd.py` существует, но основной `send` parser использует код из `main.py`/`subcommands/send.py`? Проверить дублирование.
- `hermes_cli/service_manager.py` — NotImplemented для `host` service backend. Linux использует systemd/Docker. Для Linux не нужен.
- `hermes_cli/secrets_cli.py` не импортирован как `secrets` parser; вместо этого inline `secrets`/`bitwarden` в main.py. Нужно либо подключить, либо удалить.

### 3. Hermes: заглушки/пустые реализации
- `hermes_cli/subcommands/checkpoints.py`, `curator.py`, `computer-use.py`, `sessions.py` — parserы не имеют `.set_defaults(func=...)`, при вызове просто печатают help. Это заглушки.
- Нужно либо реализовать, либо явно пометить `help="(stub — prints help only)"`.

### 4. Hermes: runtime defaults reasoning effort
- `reasoning_effort` в конфиге string (medium). java-agent ожидает int-ish значение 100. Нужно согласовать семантику (OpenAI reasonig effort — low/medium/high; max_completion_tokens — int). В java-agent fast-mode использует low, а 100 — max completion tokens.

### 5. Hermes: CLI конфигурационная связность — ✅
- `agent.max_turns: 100` (config.py + cli-config.yaml.example).
- `delegation.max_iterations: 100` (config.py + cli-config.yaml.example).
- `goals.max_turns: 100` (config.py, cli.py, gateway/run.py, tui_gateway/server.py); fallback 20 → 100.
- `HERMES_MAX_ITERATIONS` ghost в .env — doctor предупреждает, но автоматически не чистит.

### 6. java-agent: покрытие тестами новых endpoint'ов — ✅
- `AgentControllerTest` — добавлены тесты для reasoning/fast/voice/title/tools/enable/disable/subgoal/browser/queue/snapshot.
- `CliRuntimeSettingsServiceTest` — новый тестовый класс для cli state persistence, tools, reset, missing session.

### 7. java-agent: persistence of CLI state — ЧАСТИЧНО
- `SessionEntity.cliState` остаётся `@Transient` — состояние CLI живёт только в рамках одного runtime вызова. Для полноценной персистентности нужна JSONB колонка или удаление `@Transient` с реальной миграцией.

### 8. java-agent: prompt builder и reasoning — ЧАСТИЧНО
- `LangChain4jModelClient` прокидывает `reasoningEffort`/`fastMode`. `DefaultPromptBuilder` не использует reasoning effort в system prompt. Fast/voice mode не влияют на prompt.

### 9. java-agent: Telegram-bot health client
- `AgentBackendClient` имеет `checkBackendHealth` с `/actuator/health`. Убедиться, что backend URL возвращает `UP` и бот правильно считывает `status`. Тестов на это нет.

### 10. java-agent: SkillBundleService install/uninstall
- Заглушки `installBundle`/`uninstallBundle` бросают `UnsupportedOperationException`. CLI slash-команды `/install`, `/uninstall` вызывают backend endpoint'ы, которые не реализованы. Нужно либо реализовать, либо убрать команды из `SlashCommandRegistry`.

## Приоритеты и рекомендации

1. **Сделать сейчас** — P0/P1 java-agent завершены, можно переходить к покрытию тестами (P0-3 tests, health test).
2. **Hermes** — критично только унифицировать дефолты (сделано). Остальное — документальные/UX доработки.
3. **Не трогать без явного указания** — production deploy, touch prod server, merge MR.
