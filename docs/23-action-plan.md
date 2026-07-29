# План дальнейших действий

> Создан: 2026-07-29
> Текущее состояние: 334 Java-файла, 1483 теста, 0 failures, 11 миграций (V1–V11)
> Все 27 компонентов из плана 22-full-porting.md реализованы
> Lombok: 126/334 файлов (38%) — 208 без Lombok (из них ~86 records/interfaces/enums)

---

## Текущий статус по планам

### План 22-full-porting.md — ✅ ЗАВЕРШЁН

| Этап | Компонентов | Статус |
|------|-------------|--------|
| 0. Lombok migration | частично (126/334) | ⚠️ 122 класса без Lombok |
| 1. Cron Jobs | 7 | ✅ |
| 2. Prompt Caching | 1 | ✅ |
| 3. Conversation Compression | 1 | ✅ |
| 4. Checkpoint Manager | 1 | ✅ |
| 5. Usage Tracker | 1 | ✅ |
| 6. Image Generation | 2 | ✅ |
| 7. TTS / Voice | 4 | ✅ |
| 8. Transcription | 2 | ✅ |
| 9. MCP OAuth | 1 | ✅ |
| 10. Managed Tool Gateway | 1 | ✅ |
| 11. Think Scrubber | 1 | ✅ |
| 12. Error Classifier | 1 | ✅ |
| 13. Rate Limit Tracker | 1 | ✅ |
| 14. Skill Bundles | 1 | ✅ |
| 15. Coding Context | 1 | ✅ |
| 16. Interrupt / Cancellation | 1 | ✅ |
| 17. Tool Result Classification | 1 | ✅ |
| 18. Turn Finalizer | 1 | ✅ |
| 19. Tool Output Limits | 1 | ✅ |
| 20. Full application.yml | ✅ | ✅ |
| 21. Очередность | — | ✅ |
| 22. Риски | — | ✅ |

### План 22-telegram-bot-audit-fixes.md — ⏳ ОЖИДАЕТ ВЫПОЛНЕНИЯ

21 задача (P0/P1/P2), 0% выполнено.

---

## План дальнейших действий — 4 направления

### Направление 1: Bugfixes — план 22-telegram-bot-audit-fixes.md (21 задача)

**Приоритет: ВЫСОКИЙ — прямо влияет на UX**

| Этап | Задач | Что | Тестов | Оценка |
|------|-------|-----|--------|--------|
| P0 | 7 | Threading, рекурсивный drain, потеря interrupt-сообщений, tool progress в тексте, setMyCommands JSON, race в queue, unescaped errors | ~18 | 2–3 дня |
| P1 | 7 | Порядок typing→send, reaction на interrupt, footer, splitter code blocks, "(1/N)" индикатор, batch merge, memoryUpdated в ответе | ~18 | 1–2 дня |
| P2 | 7 | Rate limiter, retry на 429, TypingManager shutdown/atomic, configurable maxMessageLength, blockquote, валидация config | ~14 | 1 день |
| **Итого** | **21** | | **~50** | **4–6 дней** |

Каждая задача: кодофикс → unit test → `./gradlew test` → commit.

---

### Направление 2: Lombok миграция (122 класса без Lombok)

**Приоритет: СРЕДНИЙ — техдолг, не блокирует функциональность**

| Категория | Классов | Что делать |
|-----------|---------|------------|
| Bot commands (impl/) | 42 | `@Slf4j` + `@RequiredArgsConstructor` |
| Backend core (tool, memory, security, prompt) | ~35 | `@Slf4j` + `@RequiredArgsConstructor` |
| Backend config/health/cli | ~15 | `@Slf4j` где нужен логгер |
| Bot formatting/client/webhook | ~10 | `@Slf4j` + `@RequiredArgsConstructor` |
| Gateway/session | ~10 | `@Slf4j` + `@RequiredArgsConstructor` |
| Прочее | ~10 | по обстоятельствам |
| **Итого** | **~122** | |

Партиями по 20–30 файлов, после каждой — `./gradlew test`.
Оценка: 1–2 дня.

---

### Направление 3: Заглушки команд (6 stub-команд)

**Приоритет: НИЗКИЙ — не влияют на core-функциональность**

| Команда | Назначение | Что нужно |
|---------|-----------|-----------|
| `/credits` | Баланс кредитов | Backend endpoint + реальный UsageTracker data |
| `/codex_runtime` | Codex runtime | Зависит от внешнего Codex CLI — может остаться stub |
| `/goal` | Управление целями | Backend GoalService + entity + migration |
| `/subgoal` | Подцели | Зависит от /goal |
| `/kanban` | Kanban интеграция | External API integration |
| `/personality` | Система личностей | PersonalityService + entity + migration |

Оценка: 2–3 дня (если все 6; /credits и /personality проще, остальные зависят от внешних систем).

---

### Направление 4: Интеграционное тестирование и продакшн-ридинесс

**Приоритет: СРЕДНИЙ — после bugfixes**

| Задача | Что | Тестов | Оценка |
|--------|-----|--------|--------|
| E2E: Bot → Backend → LLM | Интеграционный тест: сообщение → стриминг → финальный ответ | 3–5 | 1 день |
| E2E: Tool execution | Terminal/file/web tool → результат в ответе | 3–5 | 1 день |
| E2E: Session persistence | Сообщение → restart → история сохранена | 2–3 | 0.5 дня |
| Docker compose test | Полный `docker compose up` → health check → базовый диалог | 2–3 | 0.5 дня |
| CI pipeline | GitHub Actions / GitLab CI: test + build + image | — | 0.5 дня |
| **Итого** | | **~12** | **3–4 дня** |

---

## Рекомендуемая очерёдность

```
Шаг 1: P0 bugfixes (7 задач, ~18 тестов)           ← 2–3 дня
Шаг 2: P1 bugfixes (7 задач, ~18 тестов)           ← 1–2 дня
Шаг 3: P2 bugfixes (7 задач, ~14 тестов)           ← 1 день
  ── checkpoint: 1533 тестов, 0 failures ──
Шаг 4: Lombok миграция (122 класса, партиями)      ← 1–2 дня
  ── checkpoint: 1533 тестов, 0 failures ──
Шаг 5: Интеграционное тестирование                 ← 3–4 дня
  ── checkpoint: 1545 тестов, 0 failures ──
Шаг 6: Заглушки команд (по приоритету)             ← 2–3 дня
  ── checkpoint: ~1560 тестов, 0 failures ──
```

**Итого: ~10–15 рабочих дней, +~75 тестов, финально ~1560 тестов.**

---

## Текущие цифры

| Метрика | Значение |
|---------|----------|
| Java-файлов (main) | 334 |
| Java-файлов (test) | 274 |
| Тестов | 1483 |
| Миграций (backend) | 11 (V1–V11) |
| Миграций (telegram-bot) | 5 (V1–V5) |
| Команд бота | 48 (42 активных + 6 stubs) |
| Компонентов из плана 22 | 27/27 (100%) |
| Lombok-классов | 126/334 (38%) |
| Коммиты | 17 (от 2aa5a14 до 10d3abf) |
| Git-ветка | main |
| Контейнеры | agent (backend), telegram-bot, db |
| Модель | kimi-k2.6, openai-compatible |