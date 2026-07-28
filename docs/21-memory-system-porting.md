# План: Перенос системы памяти Hermes в java-agent

> **Цель:** Перенести полную систему памяти Hermes — self-improvement review, write-approval gate, memory tool (add/replace/remove), frozen snapshot, /memory pending/approve/reject — в java-agent backend + telegram-bot.

---

## 1. Текущее состояние

### 1.1 Что есть в java-agent (база)

| Компонент | Файл | Описание |
|-----------|------|----------|
| `MemoryProvider` interface | `core/memory/MemoryProvider.java` | `recall(userId, query, limit)`, `store(userId, category, fact)` |
| `DatabaseMemoryProvider` | `core/memory/DatabaseMemoryProvider.java` | PostgreSQL FTS через `MemoryRepository.searchByUserId` |
| `NoOpMemoryProvider` | `core/memory/NoOpMemoryProvider.java` | Заглушка |
| `MemoryEntity` | `persistence/entity/MemoryEntity.java` | `id`, `userId`, `category`, `fact`, `createdAt` |
| `MemoryRepository` | `persistence/repository/MemoryRepository.java` | `findByUserIdOrderByCreatedAtDesc`, `searchByUserId` (FTS), `findByUserIdAndFactLikeIgnoreCase` |
| `MemoryTool` | `tools/memory/MemoryTool.java` | LLM tool: `store` / `recall` actions |
| `DefaultContextEngine` | `core/context/DefaultContextEngine.java` | `appendMemoryRecall()` — инжектит memory в system prompt |
| `/memory` command (bot) | `commands/impl/MemoryCommand.java` | Только **чтение** — `backendClient.getMemory()` |
| Backend `/agent/memory` endpoint | `api/AgentController.java` | `memoryProvider.recall("default", "", 100)` — userId зашит |

### 1.2 Чего нет (что есть в Hermes)

| Фишка Hermes | Описание | Статус |
|--------------|----------|--------|
| **Self-improvement review** | После каждого turn фоновый поток анализирует диалог и сохраняет факты в memory | ❌ Нет |
| **Memory tool: add/replace/remove** | Tool с actions: add, replace (old_text → new), remove (old_text) — не просто store | ❌ Нет (только store/recall) |
| **Two stores: memory + user** | `memory` — заметки агента, `user` — профиль пользователя. Отдельные лимиты | ❌ Нет (одна таблица) |
| **Frozen snapshot** | Снапшот memory в system prompt — стабилен на всю сессию (prefix cache) | ❌ Частично (recall каждый turn) |
| **Write-approval gate** | `memory.write_approval` — если on, writes идут в pending queue, пользователь одобряет | ❌ Нет |
| **Pending store** | File/DB-backed pending writes: `pending/{id, action, target, content, old_text, status}` | ❌ Нет |
| **`/memory pending`** | Список pending writes | ❌ Нет |
| **`/memory approve <id>`** | Одобрить pending write → применить | ❌ Нет |
| **`/memory reject <id>`** | Отклонить pending write | ❌ Нет |
| **`/memory approval on|off`** | Toggle write-approval gate | ❌ Нет |
| **💾 "Memory updated" уведомление** | Сообщение в чат после self-improvement review | ❌ Нет |
| **Memory char limits** | memory: 2200 chars, user: 1375 chars. Entry delimiter: § | ❌ Нет |
| **Threat scanning** | Проверка memory content на injection/exfiltration patterns | ❌ Нет |
| **System prompt block** | `buildSystemPrompt()` → `§ MEMORY\n...\n§ USER\n...` в system prompt | ❌ Частично |
| **Background review prompt** | LLM prompt: "Review the conversation and save to memory if appropriate" | ❌ Нет |
| **Sync after turn** | `MemoryProvider.syncTurn(user, assistant, sessionId, messages)` | ❌ Нет |
| **Prefetch** | `MemoryProvider.prefetch(query, sessionId)` — pre-fetch relevant facts before turn | ❌ Нет |

---

## 2. Архитектура

### 2.1 Поток данных

```
User message
    ↓
Backend: AgentRuntime.runTurn()
    ├── ContextEngine.prepareContext()
    │   ├── MemoryStore.getSnapshot() → frozen memory/user blocks в system prompt
    │   └── appendRecentHistory()
    ├── LLM call (with memory tool available)
    │   └── Memory tool: add/replace/remove → WriteApprovalGate
    │       ├── Gate OFF → apply directly to MemoryStore
    │       └── Gate ON → stage to PendingStore
    └── Post-turn: BackgroundReviewService
        ├── Fork: LLM review prompt → "Save to memory if appropriate"
        ├── Memory tool calls → WriteApprovalGate
        └── Notify telegram-bot: "💾 Memory updated" (if writes happened)
```

### 2.2 Хранилище

```
PostgreSQL
├── memory (existing)
│   ├── id UUID PK
│   ├── user_id VARCHAR
│   ├── category VARCHAR  (preference, fact, environment, convention, ...)
│   ├── fact TEXT
│   ├── target VARCHAR   ← NEW: 'memory' | 'user'
│   ├── created_at TIMESTAMPTZ
│   └── (existing FTS index)
│
├── memory_pending ← NEW
│   ├── id UUID PK
│   ├── user_id VARCHAR
│   ├── action VARCHAR   (add, replace, remove)
│   ├── target VARCHAR    (memory, user)
│   ├── content TEXT
│   ├── old_text VARCHAR
│   ├── summary VARCHAR   (one-line description)
│   ├── origin VARCHAR    (foreground, background_review)
│   ├── status VARCHAR    (pending, approved, rejected)
│   ├── created_at TIMESTAMPTZ
│   └── resolved_at TIMESTAMPTZ
```

---

## 3. Детальный план доработок

### Этап 1: MemoryStore — two-store model (memory + user)

| # | Задача | Файлы | Тесты |
|---|--------|-------|-------|
| 1.1 | `MemoryStore.java` — two-store model: `memoryEntries: List<String>`, `userEntries: List<String>`. Char limits: memory=2200, user=1375. Entry delimiter: `§`. Methods: `add(target, content)`, `replace(target, oldText, newText)`, `remove(target, oldText)`, `read(target)`, `getSnapshot() → {memory, user}` | `core/memory/MemoryStore.java` (новый) | `MemoryStoreTest` (8) |
| 1.2 | `MemoryEntry` — record: `id`, `target` (memory/user), `content`, `createdAt`. Replace `MemoryEntity` or add `target` column | `persistence/entity/MemoryEntity.java` (patch: +target) | — |
| 1.3 | V5 Flyway migration: `ALTER TABLE memory ADD COLUMN target VARCHAR(16) DEFAULT 'memory'` | `db/migration/V5__memory_target.sql` (новый) | — |
| 1.4 | Update `DatabaseMemoryProvider`: `store(userId, target, category, fact)`, `recall(userId, target, query, limit)`, `replace(userId, target, oldText, newText)`, `remove(userId, target, oldText)` | `core/memory/DatabaseMemoryProvider.java` (patch) | `DatabaseMemoryProviderTest` (5) |
| 1.5 | Update `MemoryProvider` interface: add `replace`, `remove`, `read`, `getSnapshot` methods | `core/memory/MemoryProvider.java` (patch) | — |
| 1.6 | Update `MemoryRepository`: `findByUserIdAndTargetOrderByCreatedAtDesc`, `findByUserIdAndTargetAndFactContaining` | `persistence/repository/MemoryRepository.java` (patch) | — |

### Этап 2: Memory tool — add/replace/remove (LLM tool)

| # | Задача | Файлы | Тесты |
|---|--------|-------|-------|
| 2.1 | Rewrite `MemoryTool.java` — actions: `add(target, content)`, `replace(target, old_text, content)`, `remove(target, old_text)`, `read(target)`. Schema description matches Hermes: "Save durable information to persistent memory... WHEN TO SAVE... TWO TARGETS... ACTIONS..." | `tools/memory/MemoryTool.java` (rewrite) | `MemoryToolTest` (8) |
| 2.2 | `MemoryArgs` record: `action`, `target` (memory/user), `content`, `old_text`, `limit` | (в MemoryTool) | — |
| 2.3 | Wire WriteApprovalGate in MemoryTool: if gate ON → stage write, return "Staged for approval". If OFF → apply directly | (в MemoryTool) | — |

### Этап 3: Write-approval gate + pending store

| # | Задача | Файлы | Тесты |
|---|--------|-------|-------|
| 3.1 | `WriteApprovalGate.java` — `isEnabled()`, `stageWrite(userId, action, target, content, oldText, summary, origin)`, `listPending(userId)`, `approve(userId, id)`, `reject(userId, id)`, `setApproval(enabled)` | `core/memory/WriteApprovalGate.java` (новый) | `WriteApprovalGateTest` (8) |
| 3.2 | `PendingMemoryEntity` — JPA entity: `id`, `userId`, `action`, `target`, `content`, `oldText`, `summary`, `origin`, `status`, `createdAt`, `resolvedAt` | `persistence/entity/PendingMemoryEntity.java` (новый) | — |
| 3.3 | `PendingMemoryRepository` — `findByUserIdAndStatus`, `findByIdAndUserId` | `persistence/repository/PendingMemoryRepository.java` (новый) | — |
| 3.4 | V6 Flyway migration: `CREATE TABLE memory_pending` | `db/migration/V6__memory_pending.sql` (новый) | — |
| 3.5 | Config: `agent.memory.write-approval` (default false), `agent.memory.enabled` (default true) | `AgentProperties.MemoryProperties` (patch) | — |
| 3.6 | Wire in `AgentConfig`: `WriteApprovalGate` bean | `config/AgentConfig.java` (patch) | — |

### Этап 4: Frozen snapshot в system prompt

| # | Задача | Файлы | Тесты |
|---|--------|-------|-------|
| 4.1 | `MemoryStore.getSnapshot()` → frozen `§ MEMORY\n...\n§ USER\n...` block. Captured at session start, stable for entire session | `core/memory/MemoryStore.java` (в этапе 1) | (в MemoryStoreTest) |
| 4.2 | Update `DefaultContextEngine.appendMemoryRecall()`: use frozen snapshot instead of live recall every turn. Snapshot refreshes on next session | `core/context/DefaultContextEngine.java` (patch) | `ContextEngineMemoryTest` (3) |
| 4.3 | Cache snapshot per session in `MemoryStore` — `getSnapshot(sessionId)` returns cached snapshot. New session → new snapshot | (в MemoryStore) | — |

### Этап 5: Self-improvement background review

| # | Задача | Файлы | Тесты |
|---|--------|-------|-------|
| 5.1 | `BackgroundReviewService.java` — after each turn, fork LLM call with review prompt. Prompt: "Review the conversation above and consider saving to memory if appropriate. Focus on: 1. User preferences/corrections 2. Environment facts. If nothing to save, say 'Nothing to save.'" | `core/memory/BackgroundReviewService.java` (новый) | `BackgroundReviewServiceTest` (5) |
| 5.2 | Review prompt templates: `_MEMORY_REVIEW_PROMPT`, `_SKILL_REVIEW_PROMPT`, `_COMBINED_REVIEW_PROMPT` — constants | `core/memory/ReviewPrompts.java` (новый) | — |
| 5.3 | Wire in `AgentRuntimeService.runTurn()`: after turn completes, call `backgroundReviewService.reviewTurn(sessionId, messages)`. Async (ScheduledExecutorService, daemon thread) | `service/AgentRuntimeService.java` (patch) | — |
| 5.4 | Review uses same LLM model, tool whitelist: only `memory` tool. Other tools denied | (в BackgroundReviewService) | — |
| 5.5 | Config: `agent.memory.background-review.enabled` (default true), `agent.memory.background-review.delay-ms` (default 2000) | `AgentProperties.MemoryProperties` (patch) | — |
| 5.6 | If review produces memory writes → notify via callback (for "💾 Memory updated" message) | (в BackgroundReviewService) | — |

### Этап 6: Backend endpoints для /memory command

| # | Задача | Файлы | Тесты |
|---|--------|-------|-------|
| 6.1 | `GET /api/v1/agent/memory/pending/{userId}` → List<PendingMemoryDto> | `api/AgentController.java` (patch) | — |
| 6.2 | `POST /api/v1/agent/memory/approve` — body: `{userId, id}` → approve pending write | `api/AgentController.java` (patch) | — |
| 6.3 | `POST /api/v1/agent/memory/reject` — body: `{userId, id}` → reject pending write | `api/AgentController.java` (patch) | — |
| 6.4 | `POST /api/v1/agent/memory/approval` — body: `{enabled: bool}` → toggle gate | `api/AgentController.java` (patch) | — |
| 6.5 | `GET /api/v1/agent/memory/all/{userId}` → List<MemoryDto> — all memory+user facts | `api/AgentController.java` (patch) | — |
| 6.6 | `DELETE /api/v1/agent/memory/{userId}/{entryId}` — delete memory entry | `api/AgentController.java` (patch) | — |
| 6.7 | DTOs: `PendingMemoryDto`, `MemoryDto`, `ApproveRequest`, `RejectRequest`, `ApprovalRequest` | `api/dto/` (новые) | — |
| 6.8 | `AgentRuntimeService` methods: `listPendingMemory`, `approvePendingMemory`, `rejectPendingMemory`, `setMemoryApproval`, `listAllMemory`, `deleteMemory` | `service/AgentRuntimeService.java` (patch) | — |

### Этап 7: Telegram-bot /memory command rewrite

| # | Задача | Файлы | Тесты |
|---|--------|-------|-------|
| 7.1 | Rewrite `MemoryCommand.java`: subcommands — `/memory` (list all), `/memory pending` (pending writes), `/memory approve <id>`, `/memory reject <id>`, `/memory approval on|off`, `/memory add <text>`, `/memory remove <text>` | `commands/impl/MemoryCommand.java` (rewrite) | `MemoryCommandTest` (8) |
| 7.2 | Add `AgentBackendClient` methods: `listPendingMemory(userId)`, `approvePendingMemory(userId, id)`, `rejectPendingMemory(userId, id)`, `setMemoryApproval(enabled)`, `listAllMemory(userId)`, `deleteMemory(userId, entryId)` | `core/AgentBackendClient.java` (patch) | — |
| 7.3 | Wire "💾 Memory updated" notification: after backend turn, if background review produced writes → backend includes `memoryUpdated: true` in response. Bot sends "💾 Self-improvement review: Memory updated" message | `core/BotMessageProcessor.java` (patch) | — |
| 7.4 | Update `BotLifecycleManager.registerCommands()`: update /memory description | `lifecycle/BotLifecycleManager.java` (patch) | — |

### Этап 8: Threat scanning

| # | Задача | Файлы | Тесты |
|---|--------|-------|-------|
| 8.1 | `MemoryThreatScanner.java` — scan memory content for injection/exfiltration patterns. Returns error string if blocked. Patterns: prompt injection, data exfiltration, control characters | `core/memory/MemoryThreatScanner.java` (новый) | `MemoryThreatScannerTest` (4) |
| 8.2 | Wire in `MemoryStore.add()`: scan content before adding. If threat detected → return error, don't store | (в MemoryStore) | — |
| 8.3 | Wire in `MemoryStore.getSnapshot()`: scan entries for snapshot. If threat → replace with `[BLOCKED: ...]` placeholder in snapshot, keep raw in live state | (в MemoryStore) | — |

### Этап 9: Integration + wiring

| # | Задача | Файлы | Тесты |
|---|--------|-------|-------|
| 9.1 | Wire `MemoryStore` in `AgentConfig` — bean with `MemoryRepository` + `WriteApprovalGate` | `config/AgentConfig.java` (patch) | — |
| 9.2 | Wire `BackgroundReviewService` in `AgentConfig` — bean with `ModelClient` + `MemoryStore` + `WriteApprovalGate` | `config/AgentConfig.java` (patch) | — |
| 9.3 | Update `DefaultAgentRuntime`: inject `BackgroundReviewService`, call `reviewTurn()` after each turn | `core/agent/DefaultAgentRuntime.java` (patch) | — |
| 9.4 | Update `AgentRuntimeService.runTurn()`: pass `memoryUpdated` flag in response DTO | `service/AgentRuntimeService.java` (patch) | — |
| 9.5 | Update `ChatResponseDto`: add `memoryUpdated` boolean field | `api/dto/ChatResponseDto.java` (patch) | — |

---

## 4. Очередность реализации

```
Этап 1 → Этап 2 → Этап 3 → Этап 4
(MemoryStore)  (Memory tool)  (Approval gate)  (Frozen snapshot)

Этап 5 → Этап 6 → Этап 7 → Этап 8 → Этап 9
(Self-improve)  (Backend API)  (Bot /memory)  (Threat scan)  (Wiring)
```

**MVP (этапы 1–4):** two-store model, memory tool with add/replace/remove, write-approval gate, frozen snapshot.

**Full (этапы 5–9):** self-improvement review, backend endpoints, bot /memory rewrite, threat scanning, integration.

---

## 5. Сводка

| Этап | Компонентов | Тестов | Новых файлов | Изменённых файлов |
|------|-------------|--------|-------------|-------------------|
| 1. MemoryStore | 4 | 8 | 2 | 4 |
| 2. Memory tool | 1 | 8 | 0 | 1 |
| 3. Approval gate | 4 | 8 | 3 | 2 |
| 4. Frozen snapshot | 0 | 3 | 0 | 1 |
| 5. Self-improvement | 2 | 5 | 2 | 2 |
| 6. Backend endpoints | 5 | 0 | 5 | 2 |
| 7. Bot /memory | 1 | 8 | 0 | 3 |
| 8. Threat scanning | 1 | 4 | 1 | 1 |
| 9. Integration | 0 | 0 | 0 | 4 |
| **Итого** | **18** | **~44** | **13** | **20** |

**Финальное состояние:**
- Two-store memory (memory + user) с char limits
- Memory tool: add/replace/remove с write-approval gate
- Frozen snapshot в system prompt (prefix cache stable)
- Self-improvement background review после каждого turn
- "💾 Memory updated" уведомление в Telegram
- `/memory` с pending/approve/reject/approval subcommands
- Threat scanning memory content
- 2 новые таблицы (memory_pending, memory.target column)
- ~44 новых теста

---

## 6. Риски

| Риск | Решение |
|------|---------|
| Background review latency | Async, daemon thread, 2s delay after turn. Non-blocking |
| Review LLM cost | Config: `background-review.enabled=false` to disable. Use cheaper model if available |
| Memory pollution (wrong facts) | Write-approval gate → user reviews before commit |
| Prefix cache invalidation | Frozen snapshot — не меняется mid-session. Refresh на следующей сессии |
| Threat patterns coverage | Начать с базовых patterns (prompt injection, exfil), расширять по мере обнаружения |
| Memory growth | Char limits (2200/1375). Replace/remove actions. Cleanup old entries по TTL (future) |

---

*Документ создан для планирования переноса системы памяти Hermes в java-agent.*