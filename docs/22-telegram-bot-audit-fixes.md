# Plan: Telegram Bot Audit Fixes

> Created: 2026-07-29
> Status: **PENDING — awaiting user approval to start**
> Commits: `a2c406f` (connectivity + streaming + markdown fixes)

## Summary

Full audit of java-agent Telegram bot vs Hermes Agent. Found 21 issues across
3 priority tiers. Each task includes code fix + unit tests (JUnit 5 + Mockito + AssertJ).

---

## P0 — Critical Bugs (block UX right now)

### Task 1: LongPollingService — process updates on thread pool

**Problem:** `updateHandler.accept(event)` runs on the polling thread (line 87).
One slow LLM response blocks ALL chats — no new updates are polled until
processing completes.

**Hermes:** Each update dispatched via `asyncio.create_task` — polling loop
never blocks.

**Fix:** Add a `ExecutorService` (fixed thread pool, ~4 threads). In `pollLoop`,
submit each update to the pool instead of calling `accept()` inline. The
polling thread only fetches and dispatches.

**Files:**
- `LongPollingService.java` — add `ExecutorService processPool`, submit events
- `LongPollingService.java` — shutdown pool in `stop()`

**Tests:**
- `LongPollingServiceTest`: verify update processed on separate thread
- `LongPollingServiceTest`: verify slow processing doesn't block next poll cycle
- `LongPollingServiceTest`: verify pool shutdown on stop

---

### Task 2: BotMessageProcessor — linear queue drain, not recursive

**Problem:** `drainQueue` (line 329) calls `accept(queuedEvent)` which re-enters
`handleTextOrMedia` → if that message also gets queued, the stack grows
unboundedly → `StackOverflowError` with many queued messages.

**Hermes:** Queue drain is linear — `while (hasQueued) { process(poll) }`.

**Fix:** Replace recursive `accept()` call in `drainQueue` with direct
`handleTextOrMedia()` call inside a `while` loop. Add a max-queue-depth guard
(100 messages) to prevent infinite loops.

**Files:**
- `BotMessageProcessor.java` — rewrite `drainQueue` method

**Tests:**
- `BotMessageProcessorTest`: verify 50 queued messages processed without StackOverflow
- `BotMessageProcessorTest`: verify queue order preserved (FIFO)
- `BotMessageProcessorTest`: verify max-queue-depth guard drops excess messages

---

### Task 3: BotMessageProcessor — interrupt mode preserves user message

**Problem:** When `busyMode=interrupt`, incoming message triggers
`busyHandler.interrupt(chatId)` but the message itself is **discarded** — never
queued, never processed. User's interrupting message is lost.

**Hermes:** Interrupt stops current turn, then the new message is queued for
re-processing after interrupt completes.

**Fix:** In `handleTextOrMedia`, interrupt branch: call `interrupt(chatId)` then
`queueMessage(chatId, event)` so the message is drained after the current
turn stops.

**Files:**
- `BotMessageProcessor.java` — interrupt branch: add `queueMessage` after `interrupt`

**Tests:**
- `BotMessageProcessorTest`: verify interrupt message queued for re-processing
- `BotMessageProcessorTest`: verify interrupt message is first in drain order

---

### Task 4: BotMessageProcessor — tool progress is transient, removed from final text

**Problem:** Tool calls (`🔧 toolName...`) and results (`✅ toolName: preview`)
are appended to `accumulated` (lines 396, 404) and become part of the final
message permanently. User sees tool noise embedded in the response.

**Hermes:** Tool progress shown during streaming, but stripped from the final
message. Only clean LLM output + footer remain.

**Fix:** Track tool progress in a separate `StringBuilder toolProgress`. During
streaming, display `accumulated + toolProgress` (user sees tool activity).
On `onComplete`, finalize with `accumulated` only (clean LLM text) + footer.
The `editStream` calls during tool execution show the combined view, but
`finalizeStream` shows only the clean text.

**Files:**
- `BotMessageProcessor.java` — split `accumulated` into `cleanText` + `toolProgress`
- `BotMessageProcessor.java` — `editStream` shows `cleanText + toolProgress`
- `BotMessageProcessor.java` — `finalizeStream` shows `cleanText + footer` only

**Tests:**
- `BotMessageProcessorTest`: verify finalized text has no 🔧/✅ lines
- `BotMessageProcessorTest`: verify tool progress shown during streaming (editStream calls)
- `BotMessageProcessorTest`: verify footer appended to clean text only

---

### Task 5: TelegramClient — setMyCommands sends JSON array, not string

**Problem:** `setMyCommands` (line 209) serializes commands as a JSON string
and passes it as `Map.of("commands", commandsJson)`. Telegram expects
`commands` to be a JSON array, not a string.

**Fix:** Pass the command list directly as a `List<Map<String, String>>` —
Jackson will serialize it as a JSON array.

**Files:**
- `TelegramClient.java` — `setMyCommands` / `setMyCommandsForChat`: pass list of maps

**Tests:**
- `TelegramClientTest`: verify `commands` param is a list (array in JSON), not a string
- `TelegramClientTest`: verify command structure: `[{command, description}]`

---

### Task 6: BusySessionHandler — thread-safe queue

**Problem:** `queues.computeIfAbsent(chatId, k -> new ArrayList<>())` returns a
plain `ArrayList`. `queueMessage` and `drainQueue` can run concurrently →
data race on the list.

**Fix:** Replace `ArrayList` with `ConcurrentLinkedQueue`. Update `drainQueue`
to poll from the queue (thread-safe).

**Files:**
- `BusySessionHandler.java` — `ConcurrentLinkedQueue` instead of `ArrayList`
- `BusySessionHandler.java` — `queueMessage`: `computeIfAbsent` with queue
- `BusySessionHandler.java` — `drainQueue`: `poll()` in loop

**Tests:**
- `BusySessionHandlerTest`: verify concurrent queue + drain (100 messages, 2 threads)
- `BusySessionHandlerTest`: verify FIFO order under concurrency

---

### Task 7: BotMessageProcessor — sendError escapes MarkdownV2

**Problem:** `sendError` (line 635) sends error text with `parseMode` but
doesn't call `MarkdownConverter.convert()`. Error messages containing `.`,
`_`, `-` (paths, exception names) → Telegram rejects with 400.

**Fix:** Apply `MarkdownConverter.convert()` in `sendError` before
`telegramClient.sendMessage()`.

**Files:**
- `BotMessageProcessor.java` — `sendError`: add `MarkdownConverter.convert()`

**Tests:**
- `BotMessageProcessorTest`: verify error with path `C:\Users\test` doesn't throw
- `BotMessageProcessorTest`: verify error text properly escaped (dots, underscores)

---

## P1 — UX Issues (visible to user)

### Task 8: BotMessageProcessor — stopTyping after send, not in finally

**Problem:** `stopTyping` is in `finally` block (line 325). For streaming path,
typing indicator disappears before the final message edit lands — brief visual
gap where neither typing nor the final text is visible.

**Hermes:** `stop_typing` called **after** the final message is sent (line 8722).

**Fix:** Move `stopTyping` from `finally` to end of `try` block (after
`onProcessingComplete`). In `catch`, `stopTyping` after `sendError`. In
`finally`, only `markFree` + `drainQueue`.

**Files:**
- `BotMessageProcessor.java` — relocate `stopTyping` calls

**Tests:**
- `BotMessageProcessorTest`: verify order: finalizeStream → stopTyping
- `BotMessageProcessorTest`: verify order: sendError → stopTyping
- `BotMessageProcessorTest`: verify stopTyping always called (even on exception)

---

### Task 9: ReactionManager — onCancel for interrupted messages

**Problem:** Interrupted messages get 👍 (`onProcessingComplete(true)`) because
the `StreamInterruptedException` is caught in `onError` callback, which sets
`finalized[0] = true`, and the outer code treats it as success.

**Fix:** Track interrupt state. If the stream was interrupted, call
`reactionManager.onCancel()` instead of `onProcessingComplete(true)`.

**Files:**
- `BotMessageProcessor.java` — track `interrupted` flag, call `onCancel` if true

**Tests:**
- `BotMessageProcessorTest`: verify interrupted → `onCancel` called
- `BotMessageProcessorTest`: verify normal completion → `onProcessingComplete(true)`
- `BotMessageProcessorTest`: verify error → `onProcessingComplete(false)`

---

### Task 10: Footer — embedded in streaming message, not separate

**Problem:** After streaming fix (blocking stream), footer should be embedded
in `finalizeStream`. Verify this works end-to-end — no separate footer message.

**Hermes:** When `already_sent=true`, footer sent as trailing message (line 9123).
When streaming includes footer in the final edit, no separate send needed.

**Fix:** Verify `onComplete` callback in `streamChat` appends footer to
`finalText` before `finalizeStream`. Remove any duplicate footer send path.
Add `streamFinalized` guard.

**Files:**
- `BotMessageProcessor.java` — verify footer path in `onComplete`
- `BotMessageProcessor.java` — remove redundant `sendFormatted` for `streamFinalized=true`

**Tests:**
- `BotMessageProcessorTest`: verify single message sent (no duplicate)
- `BotMessageProcessorTest`: verify footer in finalized stream text
- `BotMessageProcessorTest`: verify `sendFormatted` not called when `streamFinalized=true`

---

### Task 11: MessageSplitter — don't break code blocks

**Problem:** Split on paragraph boundaries (`\n\n`) can split inside a ``` ```
code block, breaking the fence. Each chunk has unbalanced fences.

**Fix:** Track code block state during splitting. If inside a code block, skip
paragraph boundaries — only split on fence boundaries or fall through to
line/hard split.

**Files:**
- `MessageSplitter.java` — add `insideCodeBlock` tracking
- `MessageSplitter.java` — skip `\n\n` split if inside code block

**Tests:**
- `MessageSplitterTest`: verify code block spanning 5000 chars → 2 chunks with balanced fences
- `MessageSplitterTest`: verify normal text split on paragraph boundary still works
- `MessageSplitterTest`: verify mixed code + text → split on text boundary, not inside code

---

### Task 12: MessageSplitter — "(1/N)" continuation indicator

**Problem:** When response is split into multiple chunks, there's no visual
indicator. User thinks the response was cut off.

**Fix:** If `chunks.size() > 1`, prepend `(1/N)` to first chunk, `(2/N)` to
second, etc. Only when N > 1.

**Files:**
- `MessageSplitter.java` — add index prefix when size > 1

**Tests:**
- `MessageSplitterTest`: verify 2-chunk message → "(1/2)" + "(2/2)" prefixes
- `MessageSplitterTest`: verify 1-chunk message → no prefix
- `MessageSplitterTest`: verify prefix doesn't break MarkdownV2 (escaped dots)

---

### Task 13: TextBatchDebouncer — join short messages with space, not newline

**Problem:** Messages are joined with `\n`. "What is" + "the capital" + "of
France?" → "What is\nthe capital\nof France?" — LLM sees 3 lines, not 1 question.

**Hermes:** Short messages are merged naturally.

**Fix:** If all batched texts are ≤320 chars (short), join with `" "` (space).
If any text is >320 chars, join with `\n` (preserve current behavior for long
multi-paragraph input).

**Files:**
- `TextBatchDebouncer.java` — adaptive join: space for short, newline for long

**Tests:**
- `TextBatchDebouncerTest`: verify "what is" + "API" → "what is API" (space)
- `TextBatchDebouncerTest`: verify long paragraph + long paragraph → joined with `\n`
- `TextBatchDebouncerTest`: verify mixed short + long → joined with `\n` (conservative)

---

### Task 14: AgentBackendClient — remove memoryUpdated from response text

**Problem:** `AgentBackendClient.java:109` appends
`"💾 Self-improvement review: Memory updated"` to the response content. This
appears in every response when memory is updated — non-configurable, noisy.

**Hermes:** Memory updates are silent — not injected into response text.

**Fix:** Remove the `memoryUpdated` text injection. If memory update
notification is needed, send it as a separate ephemeral message or make it
configurable.

**Files:**
- `AgentBackendClient.java` — remove `memoryUpdated` append to response

**Tests:**
- `AgentBackendClientTest`: verify response content doesn't contain `💾`
- `AgentBackendClientTest`: verify response content is clean LLM output

---

## P2 — Stability & Correctness

### Task 15: TelegramClient — accurate rate limiter

**Problem:** Permit released 1s after call **completes**, not after **acquire**.
If API call takes 0.1s, effective rate is ~10/s (not configured 1/s).

**Fix:** Release permit 1s after **acquire** using
`schedule(() -> sem.release(), 1, SECONDS)` immediately after successful
acquire, not in `finally` block.

**Files:**
- `TelegramClient.java` — move `releaseRateLimit` to right after `acquireRateLimit`

**Tests:**
- `TelegramClientTest`: verify 2 calls 0.5s apart → second blocks until 1s elapsed
- `TelegramClientTest`: verify rate accuracy within ±100ms

---

### Task 16: TelegramClient — retry on 429 with retry_after

**Problem:** Telegram returns 429 with `retry_after` parameter. Bot ignores it,
logs warning, returns empty.

**Fix:** Parse `retry_after` from 429 error response. `Thread.sleep(retry_after
* 1000)`. Retry the call once. Max 1 retry.

**Files:**
- `TelegramClient.java` — `callApi`: detect 429, parse retry_after, sleep, retry

**Tests:**
- `TelegramClientTest`: verify 429 → sleep `retry_after` seconds → retry succeeds
- `TelegramClientTest`: verify no infinite retry (max 1)
- `TelegramClientTest`: verify non-429 error → no retry

---

### Task 17: TypingManager — @PreDestroy shutdown

**Problem:** `ScheduledExecutorService` never shut down. `stopAll()` exists but
never called.

**Fix:** Add `@PreDestroy` method calling `stopAll()`.

**Files:**
- `TypingManager.java` — add `@PreDestroy` method

**Tests:**
- `TypingManagerTest`: verify `stopAll` called on bean destruction
- `TypingManagerTest`: verify no tasks running after shutdown

---

### Task 18: TypingManager — atomic startTyping

**Problem:** `containsKey` + `put` is not atomic. Two concurrent calls for same
chatId could create two scheduled tasks, one orphaned.

**Fix:** Use `putIfAbsent` — if key exists, don't create a new task. The
`ScheduledFuture` is stored atomically.

**Files:**
- `TypingManager.java` — replace `containsKey` + `put` with `putIfAbsent`

**Tests:**
- `TypingManagerTest`: verify concurrent `startTyping` for same chat → 1 task
- `TypingManagerTest`: verify `stopTyping` + immediate `startTyping` → new task

---

### Task 19: MessageSplitter — use BotProperties.maxMessageLength

**Problem:** `TELEGRAM_MAX_LENGTH = 4096` is hardcoded. `BotProperties.maxMessageLength`
is ignored.

**Fix:** Pass `maxMessageLength` as a constructor parameter or method argument.

**Files:**
- `MessageSplitter.java` — accept `maxMessageLength` parameter
- `BotMessageProcessor.java` — pass `properties.getMaxMessageLength()` to splitter

**Tests:**
- `MessageSplitterTest`: verify custom max length (e.g., 2000) respected
- `MessageSplitterTest`: verify default 4096 still works

---

### Task 20: MarkdownConverter — blockquote support

**Problem:** `>` at start of line is escaped as `\>` — Telegram shows literal
`\>` instead of a blockquote.

**Fix:** Detect `>` at start of line (after optional whitespace). Don't escape
it. Telegram MarkdownV2 supports `>` for blockquotes.

**Files:**
- `MarkdownConverter.java` — add blockquote detection before `escapePlain`

**Tests:**
- `MarkdownConverterTest`: verify `> quote` → `> quote` (not `\> quote`)
- `MarkdownConverterTest`: verify `text > text` → `text \> text` (mid-line still escaped)
- `MarkdownConverterTest`: verify `> quote\n> more` → both lines unescaped

---

### Task 21: BotProperties — basic validation

**Problem:** No JSR-303 validation. Empty token, negative intervals, zero
rate limit — all fail at runtime only.

**Fix:** Add `@Validated` on `BotProperties`. Add `@NotBlank` on token,
`@Positive` on `typingRefreshInterval`, `@Min(1)` on `rateLimitPerSecond`.

**Files:**
- `BotProperties.java` — add validation annotations
- `BotApplication.java` — ensure `@EnableConfigurationProperties` with validation

**Tests:**
- `BotPropertiesTest`: verify empty token → validation error
- `BotPropertiesTest`: verify negative interval → validation error
- `BotPropertiesTest`: verify valid config → no errors

---

## Execution Order

1. **P0 Tasks 1-7** (critical bugs) — do first, verify each with tests
2. **P1 Tasks 8-14** (UX) — after P0, verify end-to-end
3. **P2 Tasks 15-21** (stability) — polish, lower priority

Each task: code fix → unit test → `./gradlew test` → commit.

## Test Count Estimate

- 21 tasks × ~2 tests = **~42 new tests**
- Current: 986 backend + 425 telegram-bot = 1411 tests
- Expected after: **~1453 tests**