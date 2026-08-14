# Hermes → Java-Agent: Parity Audit

Дата: 2026-08-14. Финальный статус после завершения всех работ.

## Сводная статистика

Аудит выявил **107 компонентов** в 8 функциональных областях. После 12 Work Packages, P1+P2 fixes и S-1..S-5:

| Область | ✅ Паритет | ⚠️ Отличается | ❌ Отсутствует | Всего |
|---------|-------------|-------------|---------------|-------|
| Memory | 11 | 0 | 0 | 11 |
| Skills | 14 | 0 | 0 | 14 |
| Context | 10 | 0 | 0 | 10 |
| Streaming | 9 | 0 | 0 | 9 |
| Error/Security | 8 | 0 | 0 | 8 |
| Session | 6 | 0 | 0 | 6 |
| Tools | 35 | 0 | 6 | 41 |
| Delegation/Cron/CLI | 8 | 0 | 0 | 8 |
| **Итого** | **101** | **0** | **6** | **107** |

**6 отсутствующих** — platform-specific tools (discord, feishu, x_search, computer_use, video_*, mixture_of_agents). Сознательное решение: не нужны для Java-агента.

## Parity по областям

### Memory (11/11 ✅)

| # | Компонент | Статус |
|---|-----------|--------|
| 1 | Drift detection (@Version + fact size check) | ✅ |
| 2 | Replace/remove multiple matches (unique check) | ✅ |
| 3 | MemoryTool schema (PRIORITY, session_search) | ✅ |
| 4 | File locking (synchronized + DB isolation) | ✅ |
| 5 | Delimiter `\n§\n` | ✅ |
| 6 | Char limit считает delimiter | ✅ |
| 7 | Schema: action/target enum | ✅ |
| 8 | Response: usage info | ✅ |
| 9 | Replace/Remove: contains match | ✅ |
| 10 | Pending memory + approval flow | ✅ |
| 11 | MemoryManager lifecycle hooks | ✅ |

### Skills (14/14 ✅)

| # | Компонент | Статус |
|---|-----------|--------|
| 1 | SkillManageTool patch (fuzzy, replace_all, file_path) | ✅ |
| 2 | SkillManageTool delete — absorbed_into | ✅ |
| 3 | SkillViewTool — file_path param | ✅ |
| 4 | SkillViewTool — preprocessing (template vars + inline shell) | ✅ |
| 5 | Curator — idle gating (min_idle_hours) | ✅ |
| 6 | Curator — first-run deferral | ✅ |
| 7 | Curator — dry-run mode | ✅ |
| 8 | SkillsListTool — category filter | ✅ |
| 9 | Skills edit backup/rollback (scan-before-write) | ✅ |
| 10 | SkillSecurityScanner | ✅ |
| 11 | SkillsHubService install/uninstall | ✅ |
| 12 | SkillBundleService install/uninstall | ✅ |
| 13 | SkillsListTool — category param | ✅ |
| 14 | SkillManageTool — support files | ✅ |

### Context (10/10 ✅)

| # | Компонент | Статус |
|---|-----------|--------|
| 1 | Compression — tool result dedup (MD5) | ✅ |
| 2 | Compression — tool pair sanitization | ✅ |
| 3 | Compression — auto-focus topic | ✅ |
| 4 | Compression — protect last user/assistant | ✅ |
| 5 | Compression — tool group alignment | ✅ |
| 6 | Compression — dynamic threshold (model switch) | ✅ |
| 7 | PromptBuilder — 3-tier (stable/context/volatile) | ✅ |
| 8 | Prompt cache tracking | ✅ |
| 9 | Context compression — MEDIA: tag stripping | ✅ |
| 10 | Context window management | ✅ |

### Streaming (9/9 ✅)

| # | Компонент | Статус |
|---|-----------|--------|
| 1 | Heartbeat (180s) | ✅ |
| 2 | Buffer threshold (total volume) | ✅ |
| 3 | Think-block filter | ✅ |
| 4 | Split >4096 → 32768 (rich) | ✅ |
| 5 | edit_interval (0.8s) | ✅ |
| 6 | fresh_final (60s) | ✅ |
| 7 | Cursor " ▉" | ✅ |
| 8 | Adaptive backoff | ✅ |
| 9 | Native draft streaming (S-5) | ✅ |

### Error/Security (8/8 ✅)

| # | Компонент | Статус |
|---|-----------|--------|
| 1 | ErrorClassifier — 13 категорий + ClassificationResult | ✅ |
| 2 | Retry one-shot recovery guards (TurnRetryState) | ✅ |
| 3 | Retry jitter (proportional) | ✅ |
| 4 | SSRF protection (SsrfSafeHttpClient) | ✅ |
| 5 | File safety (DefaultFileSafety) | ✅ |
| 6 | Secret redaction (DefaultRedactor) | ✅ |
| 7 | PII redaction (redact-pii config) | ✅ |
| 8 | Approval gate (destructive tools) | ✅ |

### Session (6/6 ✅)

| # | Компонент | Статус |
|---|-----------|--------|
| 1 | Session resume — child resolution | ✅ |
| 2 | Session branch — lineage tracking | ✅ |
| 3 | /undo (checkpoint restore) | ✅ |
| 4 | Session search (DB-based) | ✅ |
| 5 | Checkpoints (DB-based, Base64 content) | ✅ |
| 6 | Session CRUD API (/api/v2/sessions) | ✅ |

### Tools (35/41 ✅, 6 сознательно отсутствуют)

| # | Компонент | Статус |
|---|-----------|--------|
| 1-35 | 35 общих инструментов | ✅ |
| 36 | computer_use | ❌ Не нужен |
| 37 | video_* | ❌ Не нужен |
| 38 | x_search | ❌ Не нужен |
| 39 | mixture_of_agents | ❌ Не нужен |
| 40 | discord | ❌ Platform-specific |
| 41 | feishu | ❌ Platform-specific |

### Delegation/Cron/CLI (8/8 ✅)

| # | Компонент | Статус |
|---|-----------|--------|
| 1 | max_spawn_depth=1 (flat) | ✅ |
| 2 | subagent_auto_approve | ✅ |
| 3 | Cron context_from chaining | ✅ |
| 4 | Cron no_agent mode | ✅ |
| 5 | CLI slash commands (92) | ✅ |
| 6 | Steer mode (S-1) | ✅ |
| 7 | Busy-ack (S-4) | ✅ |
| 8 | Commentary (S-3) | ✅ |

## Новые REST endpoints (parity)

| Endpoint | Hermes | Java-agent | Статус |
|----------|--------|------------|--------|
| GET /v1/models | ✅ | ✅ ModelsController | ✅ |
| GET /v1/capabilities | ✅ | ✅ CapabilitiesController | ✅ |
| GET /v1/toolsets | ✅ | ✅ ToolsetsController | ✅ |
| /api/v2/sessions CRUD + chat | ✅ | ✅ SessionCrudController | ✅ |
| POST /agent/steer | ✅ | ✅ AgentChatController | ✅ |

## Итог

| Метрика | Значение |
|---------|----------|
| Всего компонентов | 107 |
| Полный паритет | 101 (94%) |
| Сознательно отсутствуют | 6 (6%) |
| Поведенческих отличий | 0 |
| Work Packages завершено | 12 |
| P1+P2 fixes | 12 |
| S-1..S-5 новых функций | 5 |
| Quality audit fixes | 45 |

**Паритет с Hermes достигнут.** Все поведенческие отличия устранены. Оставшиеся 6 компонентов — platform-specific tools, сознательно не реализованные.