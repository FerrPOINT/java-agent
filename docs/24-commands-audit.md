# Аудит команд: java-agent vs Hermes

> Создан: 2026-07-29
> Источник: <https://hermes-agent.nousresearch.com/docs/reference/slash-commands/>
> Java-agent: 48 команд (42 активных + 6 stubs)

---

## Сводка

| Категория | Кол-во | Команды |
|-----------|--------|---------|
| ✅ Полностью реализованы | 38 | /agents, /approve, /background, /branch, /compress, /context, /debug, /deny, /fast, /footer, /help, /insights, /memory, /model, /new, /platform, /profile, /reasoning, /reload_mcp, /reload_skills, /reset, /restart, /resume, /retry, /rollback, /sessions, /set_home, /skills, /status, /stop, /title, /topic, /undo, /update, /usage, /verbose, /version, /voice, /yolo |
| ⚠️ Stubs (нужна реализация) | 6 | /codex_runtime, /credits, /goal, /kanban, /personality, /subgoal |
| ❌ Полностью отсутствуют | 9 | /queue, /steer, /diff, /cron, /curator, /suggestions, /blueprint, /reload, /start |
| 🔀 Отсутствуют алиасы | 12 | /q, /bp, /suggest, /sethome, /set-home, /fork, /gateway, /platforms, /tasks, /codex-runtime, /reload-mcp, /reload-skills |
| 🆕 Только в java-agent | 1 | /whoami (полезно, оставить) |

---

## ❌ Полностью отсутствующие команды (9)

### 1. `/queue <prompt>` (alias: `/q`)

**Hermes:** Queue a prompt for the next turn without interrupting the current response.
**Важно:** Это **messaging-команда** — одна из самых востребованных в Telegram.
**Java-agent:** BusySessionHandler уже поддерживает queue mode, но нет **команды** `/queue` — пользователь не может явно поставить промпт в очередь. Сейчас сообщения просто буферизуются автоматически в queue mode.
**Что нужно:** Команда `/queue <prompt>` — сохраняет промпт, выводит "Queued for next turn". После завершения текущего turn промпт обрабатывается.
**Сложность:** Низкая. Queue infrastructure уже есть в BusySessionHandler.

### 2. `/steer <prompt>`

**Hermes:** Inject a mid-run note that arrives at the agent after the next tool call — no interrupt, no new user turn. The text is appended to the last tool result's content once the current tool completes.
**Важно:** Уникальная Hermes-фича. Позволяет "подрулить" агентом без прерывания.
**Java-agent:** Нет. Потребуется backend-поддержка — внедрение steer-текста в tool result.
**Сложность:** Высокая. Нужно: backend endpoint для steer, модификация агентского loop для инъекции текста.

### 3. `/diff [staged|all|session] [--stat] [path...]`

**Hermes:** Show git changes in the working directory.
**Java-agent:** Нет. Terminal tool может выполнить `git diff`, но нет готовой команды.
**Сложность:** Низкая. Обёртка над `git diff` через backend terminal tool.

### 4. `/cron`

**Hermes:** Manage scheduled tasks (list, add/create, edit, pause, resume, run, remove).
**Java-agent:** Backend имеет CronJobService, CronJobTool, CronJobController — **всё готово**, но нет bot-команды!
**Сложность:** Низкая. Просто wire bot command → AgentBackendClient → CronJobController.

### 5. `/curator [status|run|pin|archive]`

**Hermes:** Background skill maintenance.
**Java-agent:** Нет. SkillBundleService есть, но curator-логики нет.
**Сложность:** Средняя. Нужен CuratorService в backend.

### 6. `/suggestions [accept|dismiss N|catalog|clear]` (alias: `/suggest`)

**Hermes:** Review suggested automations.
**Java-agent:** Нет.
**Сложность:** Средняя. Нужен SuggestionService + entity + migration.

### 7. `/blueprint [name] [slot=value ...]` (alias: `/bp`)

**Hermes:** Set up automation from blueprint template.
**Java-agent:** Нет.
**Сложность:** Средняя. Зависит от /suggestions infrastructure.

### 8. `/reload`

**Hermes:** Reload .env variables into the running session.
**Java-agent:** Нет. Нужно перечитать env vars и обновить config.
**Сложность:** Низкая. Spring уже поддерживает refresh, но env reload требует custom logic.

### 9. `/start`

**Hermes:** Platform-protocol command. Telegram sends /start automatically on first contact. Hermes acknowledges silently — no agent reply, no session burn.
**Java-agent:** Нет. Если пользователь пишет /start, бот отвечает "Unknown command".
**Сложность:** Очень низкая. Просто добавить StartCommand с пустым/тихим ответом.

---

## ⚠️ Stubs — нужны реальные реализации (6)

### 10. `/goal <text>` (stub)

**Hermes:** Standing goal with auto-continue. Judge model checks after each turn; if not done, auto-continues. Subcommands: status, pause, resume, clear. Budget: 20 turns.
**Что нужно:** GoalService + GoalEntity + migration. Auxiliary judge model. Auto-continue loop.
**Сложность:** Высокая.

### 11. `/subgoal <text>` (stub)

**Hermes:** Append criterion to active goal. Subcommands: list, remove <N>, clear.
**Зависит от:** /goal.
**Сложность:** Средняя (после /goal).

### 12. `/credits` (stub)

**Hermes:** Show Nous credit balance and top-up link.
**Что нужно:** Wire к UsageTracker data + внешний API (Nous Portal).
**Сложность:** Низкая (если не нужен внешний API — просто показать локальные данные).

### 13. `/personality [name]` (stub)

**Hermes:** Set a personality overlay for the session.
**Что нужно:** PersonalityService + пресеты личностей (system prompt overlays).
**Сложность:** Средняя.

### 14. `/kanban <action>` (stub)

**Hermes:** Drive collaboration board from chat.
**Что нужно:** External kanban API integration.
**Сложность:** Высокая (зависит от внешней системы).

### 15. `/codex_runtime [auto|codex_app_server|on|off]` (stub)

**Hermes:** Toggle Codex app-server runtime.
**Что нужно:** Codex CLI integration.
**Сложность:** Высокая (внешняя зависимость). Может остаться stub.

---

## 🔀 Отсутствующие алиасы (12)

| Алиас | → Команда | Сложность |
|-------|-----------|-----------|
| `/q` | `/queue` | Тривиально (после реализации /queue) |
| `/bp` | `/blueprint` | Тривиально (после реализации /blueprint) |
| `/suggest` | `/suggestions` | Тривиально (после реализации /suggestions) |
| `/sethome` | `/set_home` | Тривиально |
| `/set-home` | `/set_home` | Тривиально |
| `/fork` | `/branch` | Тривиально |
| `/gateway` | `/platform` | Тривиально |
| `/platforms` | `/platform` | Тривиально |
| `/tasks` | `/agents` | Тривиально |
| `/codex-runtime` | `/codex_runtime` | Тривиально |
| `/reload-mcp` | `/reload_mcp` | Тривиально |
| `/reload-skills` | `/reload_skills` | Тривиально |

---

## План доработки команд

### Этап A: Быстрые победы (1 день)

| # | Команда | Сложность | Тестов |
|---|---------|-----------|--------|
| A1 | `/start` — тихое подтверждение | Очень низкая | 1 |
| A2 | `/cron` — wire к существующему CronJobController | Низкая | 3 |
| A3 | `/diff` — обёртка над git diff через terminal tool | Низкая | 2 |
| A4 | `/reload` — reload env vars | Низкая | 2 |
| A5 | 12 алиасов — тривиально | Очень низкая | 2 |
| A6 | `/queue` — wire к существующей queue infrastructure | Низкая | 3 |
| **Итого** | | | **~13** |

### Этап B: Средние (2–3 дня)

| # | Команда | Сложность | Тестов |
|---|---------|-----------|--------|
| B1 | `/credits` — UsageTracker data | Низкая | 2 |
| B2 | `/personality` — PersonalityService + пресеты | Средняя | 4 |
| B3 | `/steer` — backend injection в tool result | Высокая | 5 |
| B4 | `/curator` — CuratorService | Средняя | 3 |
| B5 | `/suggestions` + `/blueprint` — SuggestionService | Средняя | 6 |
| **Итого** | | | **~20** |

### Этап C: Сложные (3–5 дней)

| # | Команда | Сложность | Тестов |
|---|---------|-----------|--------|
| C1 | `/goal` — GoalService + judge model + auto-continue | Высокая | 8 |
| C2 | `/subgoal` — Depends on /goal | Средняя | 3 |
| C3 | `/kanban` — External API | Высокая | 5 |
| C4 | `/codex_runtime` — Codex CLI integration | Высокая | 3 |
| **Итого** | | | **~19** |

---

## Итого

| Категория | Кол-во | Тестов | Оценка |
|-----------|--------|--------|--------|
| Этап A (быстрые) | 6 задач | ~13 | 1 день |
| Этап B (средние) | 5 задач | ~20 | 2–3 дня |
| Этап C (сложные) | 4 задачи | ~19 | 3–5 дней |
| **Всего** | **15 задач** | **~52** | **6–9 дней** |

Финально: 48 → 57 команд (+9 новых) + 12 алиасов + 6 stubs → real.
