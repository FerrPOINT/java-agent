# План доработок: Hermes → Java-Agent Parity

Дата: 2026-08-13. Базируется на аудите `docs/28-hermes-parity-audit.md`.

## Порядок выполнения (по влиянию на качество работы агента)

### Этап 1: Context Compression (P0, самое критичное)

| # | Задача | Файлы | Описание |
|---|--------|-------|---------|
| 1.1 | Tool result dedup | `DefaultContextCompressor` | MD5-хэш дедупликация tool results — заменять дубликаты на 1-line summary |
| 1.2 | Tool pair sanitization | `DefaultContextCompressor` | Удаление orphaned tool results + вставка stub-результатов для висящих tool_calls |
| 1.3 | Protect last user/assistant | `DefaultContextCompressor` | Гарантия что последние user+assistant messages в tail после compression |
| 1.4 | Tool group alignment | `DefaultContextCompressor` | Не разрезать tool_call/result группы при compression boundary |
| 1.5 | Auto-focus topic | `DefaultContextCompressor` | Извлечение topic из последних user messages для LLM summary prompt |
| 1.6 | Тесты compression | `DefaultContextCompressorTest` | Тесты на каждый новый механизм |

### Этап 2: Streaming (P0)

| # | Задача | Файлы | Описание |
|---|--------|-------|---------|
| 2.1 | Heartbeat: 180с + отдельное сообщение | `TelegramStreamingService` | Интервал 180с, отправка отдельным сообщением, не редактирование стрима |
| 2.2 | Buffer threshold: общий объём | `StreamingService` | Считать общий накопленный объём, не дельту с последнего edit |
| 2.3 | Think-block filter: регистрочувствительный + границы | `ThinkScrubber` | Точное совпадение тегов, проверка границ (тег в начале/новой строке) |
| 2.4 | Adaptive backoff параметры | `TelegramStreamingService` | ×2 множитель, max 10с, 3 страйка, без восстановления |
| 2.5 | Тесты streaming | соответствующие тесты | Обновить тесты под новые параметры |

### Этап 3: Error Handling (P0)

| # | Задача | Файлы | Описание |
|---|--------|-------|---------|
| 3.1 | ErrorClassifier — расширить категории | `ErrorClassifier` | Добавить: AUTH, AUTH_PERMANENT, OVERLOADED, SERVER_ERROR, TIMEOUT, PAYLOAD_TOO_LARGE, MODEL_NOT_FOUND, FORMAT_ERROR |
| 3.2 | Recovery hints | `ErrorClassifier` | Возвращать recovery action (retryable, should_compress, should_rotate_credential) |
| 3.3 | Тесты error classifier | `ErrorClassifierTest` | Тесты на новые категории |

### Этап 4: Memory (P0)

| # | Задача | Файлы | Описание |
|---|--------|-------|---------|
| 4.1 | Multiple match check | `DatabaseMemoryProvider` | При replace/remove: если >1 совпадение — возвращать error |
| 4.2 | Schema description расширить | `MemoryTool` | Добавить поведенческие инструкции (WHEN TO SAVE, PRIORITY, SKIP) |
| 4.3 | Drift detection | `DatabaseMemoryProvider` | Проверка round-trip mismatch при записи (опционально — DB уже даёт изоляцию) |
| 4.4 | Тесты memory | `MemoryToolsUnitTest` | Тесты на multiple match check |

### Этап 5: Skills (P0)

| # | Задача | Файлы | Описание |
|---|--------|-------|---------|
| 5.1 | Patch: replace_all + support files | `SkillManager`, `SkillManageTool` | Добавить replace_all параметр, patch support files (не только SKILL.md) |
| 5.2 | Delete: absorbed_into параметр | `SkillManageTool`, `SkillManager` | Добавить параметр absorbed_into для классификации куратором |
| 5.3 | SkillViewTool: file_path параметр | `SkillViewTool` | Чтение конкретного linked file |
| 5.4 | Curator: idle gating + first-run deferral | `CuratorService` | `min_idle_hours` проверка, seed last_run_at |
| 5.5 | Curator: dry-run mode | `CuratorService` | Флаг dry-run для тестирования без мутаций |
| 5.6 | Тесты skills | соответствующие тесты | Тесты на новые параметры |

### Этап 6: Session (P1)

| # | Задача | Файлы | Описание |
|---|--------|-------|---------|
| 6.1 | Session lineage — parent_session_id | `SessionEntity`, `AgentRuntimeService` | Добавить parent_session_id в branch, auto-naming по lineage |
| 6.2 | /undo команда | CLI, `CheckpointManager` | Undo последнего действия через checkpoint restore |
| 6.3 | Resume — child resolution | `SessionResolver` | Следовать цепочке compression children при resume |

### Этап 7: Cron (P1)

| # | Задача | Файлы | Описание |
|---|--------|-------|---------|
| 7.1 | context_from chaining | `CronJobTool`, `CronJobService` | Inject output of upstream cron jobs as context before each run |

### Этап 8: Мелкие отличия (P2)

| # | Задача | Описание |
|---|--------|----------|
| 8.1 | Memory schema: target enum | Добавить `["memory","user"]` enum в schema |
| 8.2 | Memory schema: required params | Указать required: ["action","target"] |
| 8.3 | Delegation: max_spawn_depth default | 1 (flat) вместо 3 |
| 8.4 | Delegation: subagent_auto_approve | Добавить конфиг |
| 8.5 | Error retry jitter | Пропорциональный (0-50% от delay) вместо фиксированного |
