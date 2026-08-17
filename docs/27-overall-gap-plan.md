# План доработок Hermes + java-agent

Дата обновления: 2026-08-14. Все задачи завершены.

## java-agent — P0/P1

| ID  | Задача | Статус | Примечания |
|-----|--------|--------|------------|
| P0-1 | CLI jar startup fix | ✅ | BackendClient @Autowired, @EnableConfigurationProperties |
| P0-2 | Defaults 100×100 | ✅ | max-turns=100, max-model-calls-per-turn=100 |
| P0-2b | Nudge intervals (memory=10, skill=15) | ✅ | Per-session counters, BackgroundReviewService |
| P0-3 | Backend endpoints для CLI slash-команд | ✅ | AgentController + CliRuntimeSettingsService + SessionEntity.cliState |
| P0-4 | CLI state → backend chat request | ✅ | ChatRequest расширен, BackendClient передаёт CliState |
| P1-5 | Reasoning/fast/voice в model client | ✅ | ModelProperties.reasoningEffort/fastMode |
| P1-9 | Telegram-bot health URL fix | ✅ | HealthController /health → status=UP |
| verify | Сборка + тесты | ✅ | 6386 тестов, 0 failures |

## Hermes Python

| Задача | Статус | Примечания |
|--------|--------|------------|
| Дефолты max_turns=100 / max_iterations=100 | ✅ | config.py + cli-config.yaml.example |
| CLI parser'ы `lsp`, `send`, `proxy`, `hooks` | ✅ | Подключены в top-level parser |
| `migrate`, `fallback`, `secrets` | ✅ | Встроены в main.py |
| `service_manager.py` NotImplementedError | ⚠️ | Ожидаемо для host-only backend |
| `portal` top-level команда | ✅ | Добавлен в _SUBCOMMANDS |
| CLI конфигурационная связность | ✅ | max_turns=100, max_iterations=100, goals.max_turns=100 |

## Parity Work Packages (12 WP)

| WP | Область | Статус | Компоненты |
|----|---------|--------|------------|
| WP-1 | Memory drift + multiple match | ✅ | @Version optimistic lock, unique-duplicate check |
| WP-2 | Memory schema + response | ✅ | ToolParam.enumValues, buildSuccessResponse |
| WP-3 | Skills patch + absorbed_into | ✅ | replace_all, patch support files, absorbed_into |
| WP-4 | SkillViewTool preprocessing | ✅ | SkillPreprocessor integrated |
| WP-5 | Curator idle/first-run/dry-run | ✅ | maybe_run_curator, min_idle_hours, seed last_run_at |
| WP-6 | Compression: dedup + sanitize | ✅ | MD5 dedup, _sanitize_tool_pairs |
| WP-7 | Compression: auto-focus + protect | ✅ | Topic extraction, tail guarantee |
| WP-8 | Compression: dynamic threshold | ✅ | recalculateThreshold() on model switch |
| WP-9 | Streaming parameters | ✅ | Heartbeat 180s, buffer, think-block filter, split 32768 |
| WP-10 | Error classifier + retry guards | ✅ | 13 categories, TurnRetryState 5 guards |
| WP-11 | Session resume + child resolution | ✅ | resolveResumeSessionId() |
| WP-12 | Cron context_from chaining | ✅ | contextFrom field + loadContextFromOutput() |

## P2 fixes (12 items)

| ID | Задача | Статус |
|----|--------|--------|
| P2-1 | Memory target enum в schema | ✅ |
| P2-2 | Memory required params в schema | ✅ |
| P2-3 | Memory char limit с delimiter | ✅ |
| P2-4 | SkillsListTool category filter | ✅ |
| P2-5 | Skills edit backup/rollback | ✅ (scan-before-write) |
| P2-6 | Compression dynamic threshold recalc | ✅ |
| P2-7 | Streaming split >4096 → 32768 | ✅ |
| P2-8 | Retry jitter proportional | ✅ |
| P2-9 | PII redaction | ✅ |
| P2-10 | Delegation subagent_auto_approve | ✅ |
| P2-11 | Delegation max_spawn_depth=1 | ✅ |
| P2-12 | Session /undo | ✅ |

## S-1..S-5: Новые функции

| ID | Функция | Статус | Описание |
|----|---------|--------|----------|
| S-1 | Steer mode | ✅ | busy-input: interrupt / queue / steer. SteerBuffer, `/agent/steer` endpoint, InboundMessageProcessor routing. Config: `agent.gateway.busy-input-mode` |
| S-2 | MEDIA: tags delivery | ✅ | MediaDeliveryService извлекает MEDIA: tags из ответов, доставляет файлы в Telegram (images/video/audio/docs). StreamEditor strips tags из streaming display. ContextCompressor strips из summarizer input |
| S-3 | Commentary messages | ✅ | CommentaryCallback интерфейс, commentary-enabled config. Промежуточные сообщения при tool execution |
| S-4 | Busy-ack messages | ✅ | busy-ack-enabled config. Подтверждение при mid-run сообщении: «⏩ Steered…» / «⏳ Queued…» |
| S-5 | Native draft streaming | ✅ | StreamEditor поддерживает Telegram draft streaming API (sendDraft). streaming-transport: auto/edit/draft/off. Auto-fallback после 3 неудач |

## Quality audit fixes

| Область | Кол-во | Статус |
|---------|--------|--------|
| CRITICAL | 4 | ✅ Все исправлены |
| HIGH | 12 | ✅ Все исправлены |
| MEDIUM | 17 | ✅ Все исправлены |
| LOW | 12 | ✅ Все исправлены |
| **Итого** | **45** | ✅ |

Подробности: `docs/29-quality-audit-fixes.md`.

## Итоги

| Метрика | Значение |
|---------|----------|
| Тестов | 6386 |
| Test classes | 515 |
| Failures | 0 |
| CLI slash commands | 92 |
| Telegram bot commands | 56 + 10 aliases |
| REST controllers | 17 |
| Hermes parity components | 107 → 107 ✅ |
| Quality audit fixes | 45/45 ✅ |