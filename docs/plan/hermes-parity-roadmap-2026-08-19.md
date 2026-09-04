# Java Agent — План дальнейших доработок

**Дата:** 2026-08-19
**Текущее состояние:** v0.1.5, 6489 тестов, backend+bot UP, 20+ багов исправлено за сессию
**Метод:** Систематическое сравнение с Hermes (4 параллельных аудитора, 36+16+6=58 находок, ~38 исправлено)

---

## Этап 1 — Критичные доработки (блокируют корректную работу бота)

### 1.1 ChatRequest: передача userId, username, language_code из бота в backend

**Проблема:** `ChatRequest` не содержит `userId`, `username`, `language_code`. Backend хардкодит `"user-1"`. Системный промпт не знает реальное имя пользователя и его язык.
**Решение:**

- Добавить поля `userId`, `username`, `languageCode` в `ChatRequest.java`
- `BotMessageProcessor` заполняет их из `UpdateEvent` (Telegram User object: `event.getMessage().getFrom().getUserName()`, `.getLanguageCode()`)
- `AgentSessionResolver.loadSession()` кладёт `languageCode` в session metadata
- `DefaultPromptBuilder` volatile tier: `User: {username}`, `Language: {languageCode}`
- `AgentStreamingService.runAgenticLoop()`: передать `userId` в `resolveOrCreate` вместо `"user-1"`
**Файлы:** `ChatRequest.java`, `BotMessageProcessor.java`, `AgentBackendClient.java`, `MessageApiClient.java`, `AgentSessionResolver.java`, `DefaultPromptBuilder.java`
**Сложность:** Средняя (API change, backward compat)
**Приоритет:** HIGH — без этого бот не знает язык пользователя

### 1.2 PLATFORM_HINTS для Telegram

**Проблема:** Hermes внедряет platform-specific guidance (markdown support, MEDIA: syntax, file delivery). Java-agent не имеет этого — модель не знает о MEDIA: тегах и Telegram formatting.
**Решение:**

- Добавить `PLATFORM_HINTS` map в `DefaultPromptBuilder` (Telegram: "Standard Markdown is auto-converted to Telegram formatting. Supported: **bold**, *italic*... MEDIA:/absolute/path/to/file...")
- Внедрять в stable tier при `platform="telegram"`
**Файлы:** `DefaultPromptBuilder.java`
**Сложность:** Низкая (константа + inject в stable tier)
**Приоритет:** HIGH — модель не использует MEDIA: доставку файлов

### 1.3 Context rotation: обновление session reference

**Проблема:** `DefaultContextEngine.prepareContext()` вызывает `rotateSession()`, создающий child session. Но `runAgenticLoop` продолжает использовать OLD session для persistence, metadata, interrupt checks. Все сообщения пишутся в старую (marked "compressed") сессию.
**Решение:**

- `prepareContext()` возвращает результат rotation через callback или return value
- `runAgenticLoop` обновляет `session` variable после rotation
- `persistTurn` пишет в новую child сессию
**Файлы:** `DefaultContextEngine.java`, `AgentStreamingService.java`, `ContextEngine.java` (interface)
**Сложность:** Высокая (контракт interface change, race conditions)
**Приоритет:** HIGH — после compression все сообщения теряются

### 1.4 Post-tool-call empty response nudge

**Проблема:** Когда модель возвращает пустой ответ ПОСЛЕ выполнения tool calls, бот отправляет generic continuation prompt. Hermes имеет специфичный nudge: "You just executed tool calls but returned an empty response. Please process the tool results above and continue with the task."
**Решение:**

- Добавить флаг `lastResponseHadToolCalls` в streaming loop
- При empty response после tool calls → отправлять `_EMPTY_TOOL_RESPONSE_NUDGE` вместо generic continuation
- Текст nudge на русском: "Ты выполнил tool calls, но вернул пустой ответ. Обработай результаты инструментов выше и продолжи задачу."
**Файлы:** `AgentStreamingService.java`
**Сложность:** Низкая
**Приоритет:** HIGH — частая причина "бот ничего не ответил после工具 вызова"

### 1.5 Think-block-only response detection

**Проблема:** `isEmpty = contentBuilder.length() == 0` не отличает truly empty от think-block-only. ThinkScrubber удаляет think blocks, и reasoning-only ответы становятся "пустыми". Бот отправляет continuation prompt, теряя reasoning context.
**Решение:**

- ThinkScrubber: добавить флаг `hadThinkContent` (true если think block был найден и удалён)
- В streaming loop: `boolean isEmpty = contentBuilder.length() == 0 && !scrubber.hadThinkContent()`
- При think-only response → не отправлять continuation, а завершить turn нормально
**Файлы:** `StreamEditor.java` (ThinkScrubber inner class), `AgentStreamingService.java`
**Сложность:** Низкая
**Приоритет:** MEDIUM-HIGH

---

## Этап 2 — Качество системного промпта (Hermes parity)

### 2.1 SKILLS_GUIDANCE обновление

**Проблема:** Java использует старую формулировку "After completing a complex task (5+ tool calls)...". Hermes изменил на "When you work out a non-trivial workflow, record it with skill_manage" (fix #82154 — старая формулировка вызывает content-filter rejection). Также отсутствует `## Skill Safety Rule` (UNAVAILABLE/RELOAD/WAIT/DEDUP).
**Решение:** Обновить SKILLS_GUIDANCE текст + добавить Skill Safety Rule блок
**Файлы:** `DefaultPromptBuilder.java`
**Сложность:** Низкая

### 2.2 MEMORY_GUIDANCE обновление

**Проблема:** В Java упрощена — отсутствуют: запрет на PR numbers/issue numbers/commit SHAs, правило "stale in 7 days", "save it as a skill", "Procedures belong in skills not memory", декларативные примеры.
**Решение:** Порт MEMORY_GUIDANCE из Hermes `prompt_builder.py:171-192`
**Файлы:** `DefaultPromptBuilder.java`
**Сложность:** Низкая

### 2.3 Model-specific guidance: grok + XML tags

**Проблема:** Java OPENAI_MODEL_GUIDANCE не применяется к "grok". Hermes включает grok. Также Hermes использует XML-теги (`<tool_persistence>`, `<mandatory_tool_use>`, `<act_dont_ask>`, `<prerequisite_checks>`, `<verification>`, `<missing_context>`).
**Решение:** Добавить "grok" в OPENAI_FAMILY_PREFIXES. Порт XML-tagged guidance blocks.
**Файлы:** `DefaultPromptBuilder.java`
**Сложность:** Низкая

### 2.4 chatType detection (group/channel/thread)

**Проблема:** `chatType` хардкод "dm". Бот не определяет group/channel/thread. Модель не знает контекст чата.
**Решение:**

- `BotMessageProcessor`: определять chat type из Telegram Update (`message.getChat().isGroupChat()`, `isSuperGroupChat()`, `isChannelChat()`)
- Передавать через `ChatRequest` или session metadata
**Файлы:** `BotMessageProcessor.java`, `ChatRequest.java` или `PiiRedactor.java`
**Сложность:** Низкая

### 2.5 USER.md profile block

**Проблема:** Hermes внедряет USER.md (user preferences, language, timezone) в volatile tier. Java не имеет системы профилей пользователей.
**Решение:** Реализовать загрузку USER.md из `~/.hermes/profiles/<profile>/memories/USER.md` (или аналога) и внедрять в volatile tier.
**Файлы:** `DefaultPromptBuilder.java`, новый `UserProfileService.java`
**Сложность:** Средняя

---

## Этап 3 — Streaming quality (StreamEditor)

### 3.1 editStreamSplit: code fence balancing

**Проблема:** Simple substring split может разрезать ```code block``` — первый chunk содержит открывающий ``` без закрывающего.
**Решение:** Реализовать `balanceFencesAcrossChunks()` — искать последний ``` boundary перед лимитом, или добавить закрывающий ``` к первому chunk и открывающий ко второму.
**Файлы:** `StreamEditor.java`
**Сложность:** Средняя

### 3.2 finalizeStream: silence marker suppression

**Проблема:** Hermes retract preview если final text = silence marker (NO_REPLY, [SILENT]). Java не делает этого — silence markers показываются пользователю.
**Решение:** В `finalizeStream`: проверить `finalText` на silence markers, если найден — delete message вместо edit.
**Файлы:** `StreamEditor.java`
**Сложность:** Низкая

### 3.3 Token guard after onComplete

**Проблема:** Если токены приходят после finalize, `editStream` создаёт новую сессию и новое сообщение. Hermes использует `_DONE` sentinel.
**Решение:** Добавить `AtomicBoolean done` flag в StreamSession. В `editStream`: `if (session.done.get()) return;`. Устанавливать в `finalizeStream`.
**Файлы:** `StreamEditor.java`, `StreamSession.java`
**Сложность:** Низкая

### 3.4 Streaming messages threading (reply_to)

**Проблема:** Java streaming messages не threaded. Hermes threads first streaming message to original user message.
**Решение:** Передать `messageThreadId` в `startStream`, использовать как `reply_to_message_id` при `sendMessage`.
**Файлы:** `StreamEditor.java`, `StreamingOrchestrator.java`
**Сложность:** Низкая

---

## Этап 4 — AgentStreamingService robustness

### 4.1 finish_reason handling

**Проблема:** `onComplete()` не проверяет `finish_reason`. Не отличает "model chose to stop" (stop) от "model was truncated" (length/incomplete) или "content_filter".
**Решение:**

- `StreamingResponseHandler.onComplete()` получить `finish_reason` от API
- `length`/`incomplete` → continuation prompt
- `content_filter` → error message to user + recovery hint
**Файлы:** `StreamingResponseHandler.java` (interface), `AgentStreamingService.java`
**Сложность:** Средняя (LangChain4j API may not expose finish_reason)

### 4.2 Proactive compression check (50% threshold)

**Проблема:** Streaming path проверяет compression только в `prepareContext` при 80% threshold. Hermes проверяет при 50% после tool batches.
**Решение:** После каждого tool batch → `checkProactiveCompression(turnMessages)` при `PROACTIVE_THRESHOLD_FRACTION=0.50`.
**Файлы:** `AgentStreamingService.java`
**Сложность:** Низкая

### 4.3 Thread.sleep backoff → interruptible

**Проблема:** `Thread.sleep(delayMs)` не реагирует на `interruptToken.cancel()` во время backoff.
**Решение:** Заменить на `interruptToken.awaitCancel(session.id(), delayMs, TimeUnit.MILLISECONDS)` или `Thread.sleep` с periodic cancel check.
**Файлы:** `AgentStreamingService.java`
**Сложность:** Низкая

### 4.4 turnIndex increment on continuation

**Проблема:** `turnIndex` не инкрементируется при continuation — multiple messages разделяют один turnIndex.
**Решение:** `turnIndex++` после каждого continuation attempt.
**Файлы:** `AgentStreamingService.java`
**Сложность:** Тривиальная

### 4.5 Cleanup: dead code + shared counter

**Проблема:** `targetChars` computed but never used (line 400). `streamRetries` shared across RATE_LIMIT and CONTEXT_OVERFLOW.
**Решение:** Убрать dead code. Разделить counters.
**Файлы:** `AgentStreamingService.java`
**Сложность:** Тривиальная

---

## Этап 5 — SessionSearchService (аудит не завершён)

### 5.1 Повторный аудит SessionSearchService

**Проблема:** Аудит дважды прерван (провайдер не отвечал). Не проверены: browse mode (current_session filtering, compression hop), discover mode (FTS query, lineage dedup, adaptive detail, bookends), scroll mode (lineage rebind), read mode (truncation, head+tail), ShapedMessage fields.
**Решение:** Повторный аудит через subagent или ручное сравнение.
**Сложность:** Средняя (561 строка Java vs 1321 строка Python)

---

## Этап 6 — Полировка и cleanup

### 6.1 Обновить skill java-agent-porting

Добавить в references/:

- `hermes-prompt-parity-audit-2026-08-19.md` — полная таблица 58 находок
- Обновить pitfalls: double history loading, ConversationCompressor prompt mutation, pendingTag write-only, onSegmentBreak MarkdownV2

### 6.2 Pre-existing flaky tests

- `StreamingOrchestratorTest.streamChat_toolCall_setsCurrentToolName` — "syncResult is null"
- `DefaultContextEngineTest.prepareContextTrimsToMaxContextMessages` — size 6 vs 5
- `BotMessageProcessorTest.toolResultConsumerTriggersSegmentBreak` — onSegmentBreak not called (expected after toolResultConsumer no-op fix)
- `AgentBackendClientTest` — tool_calls/tool_start event tests

### 6.3 Тесты для новых фиксов

- `ConversationCompressorTest`: verify system prompt NOT mutated
- `AgentStreamingServiceBranchTest`: continuation does not pollute turnMessages
- `StreamEditorTest`: onSegmentBreak uses MarkdownV2
- `StreamEditorTest`: pendingTag prepend across chunks
- `StreamingOrchestratorTest`: onComplete with messageId=-1

---

## Приоритеты

| Приоритет | Этап | Что | Почему |
|-----------|------|-----|--------|
| P0 | 1.1 | ChatRequest: userId, language_code | Бот не знает язык пользователя |
| P0 | 1.2 | PLATFORM_HINTS для Telegram | Модель не использует MEDIA: доставку |
| P0 | 1.4 | Post-tool empty response nudge | "Бот ничего не ответил после工具" |
| P1 | 1.3 | Context rotation session update | Сообщения теряются после compression |
| P1 | 1.5 | Think-block-only detection | Reasoning ответы теряются |
| P1 | 2.1-2.3 | Prompt guidance updates | Content-filter risk + model quality |
| P2 | 2.4-2.5 | chatType + USER.md | Контекст чата + профиль |
| P2 | 3.1-3.4 | StreamEditor polish | Streaming quality |
| P2 | 4.1-4.5 | AgentStreamingService robustness | Edge cases |
| P3 | 5.1 | SessionSearchService audit | Непроверенный модуль |
| P3 | 6.1-6.3 | Cleanup + tests | Технический долг |
