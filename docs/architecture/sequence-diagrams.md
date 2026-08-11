# Sequence Diagrams

> Key runtime flows in the Java Agent.
> All diagrams use Mermaid syntax.

---

## 1. Chat Turn (Non-Streaming)

A complete agent turn: user sends a message → model processes → tools execute → response returned.

```mermaid
sequenceDiagram
    actor Client
    participant AC as AgentController
    participant ARS as AgentRuntimeService
    participant DAR as DefaultAgentRuntime
    participant CE as ContextEngine
    participant MC as ModelClient
    participant TE as ToolExecutionService
    participant IB as IterationBudget
    participant MM as MemoryManager

    Client->>AC: POST /api/v1/agent/chat {ChatRequest}
    AC->>ARS: runTurn(request)
    ARS->>DAR: runTurn(session, input, references, options)

    DAR->>DAR: inputSanitizer.sanitize(input)
    DAR->>IB: startTurn(sessionId) → TurnSnapshot
    DAR->>MM: onTurnStart(sessionId, safeInput)
    DAR->>CE: prepareContext(session, messages)
    DAR->>MC: complete(context, tools, options)

    loop Tool Loop (max 100 turns)
        MC-->>DAR: ChatResponse {content?, toolCalls?}

        alt No tool calls
            DAR->>DAR: triggerBackgroundReview(session, messages)
            DAR-->>ARS: TurnResult {messages, completed=true}
        else Has tool calls
            DAR->>DAR: guardrail.check()
            DAR->>IB: recordModelCall(snapshot)
            alt Single tool call
                DAR->>TE: execute(name, id, args, session)
                TE-->>DAR: ToolResult
            else Multiple tool calls (parallel)
                DAR->>DAR: executeToolsInParallel (virtual threads)
                DAR-->>DAR: List<ToolResult>
            end
            DAR->>IB: recordToolExecution(snapshot)
            DAR->>CE: prepareContext(session, messages)
            DAR->>MC: complete(context, tools, options)
        end
    end

    DAR->>MM: syncAll(sessionId, messages)
    ARS-->>AC: ChatResponseDto
    AC-->>Client: JSON response
```

---

## 2. Streaming Chat (SSE)

Real-time token-by-token output via Server-Sent Events.

```mermaid
sequenceDiagram
    actor Client
    participant AC as AgentController
    participant ASS as AgentStreamingService
    participant MC as ModelClient
    participant TE as ToolExecutionService
    participant DB as PostgreSQL

    Client->>AC: POST /api/v1/agent/chat/stream {ChatRequest}
    AC->>ASS: streamTurn(request)
    ASS-->>AC: SseEmitter (600s timeout)
    AC-->>Client: SSE stream opened

    ASS->>ASS: Load/create session via SessionRepository
    ASS->>MC: stream(messages, tools, handler)

    loop Token streaming
        MC-->>ASS: StreamEvent {type=CONTENT, text}
        ASS-->>Client: SSE: event=content, data="..."
    end

    alt Model requests tool calls
        MC-->>ASS: StreamEvent {type=TOOL_CALLS}
        ASS->>TE: execute(toolName, args, session)
        TE-->>ASS: ToolResult
        ASS-->>Client: SSE: event=tool_result, data="..."
        ASS->>MC: stream(continue with tool results)
    end

    MC-->>ASS: Stream complete
    ASS->>DB: Save messages (TransactionTemplate)
    ASS-->>Client: SSE: event=done, data=usage
    ASS->>ASS: SseEmitter.complete()
```

---

## 3. Tool Execution with Approval Gate

Destructive tools require user approval before execution.

```mermaid
sequenceDiagram
    actor User
    participant DAR as DefaultAgentRuntime
    participant AQ as ApprovalQueue
    participant TE as ToolExecutionService
    participant TH as ToolHandler

    DAR->>DAR: Model returns tool call
    DAR->>AQ: requestApproval(sessionId, toolName, args)
    AQ-->>User: Pending approval notification

    loop Poll for approval (200ms interval, 5min timeout)
        DAR->>AQ: isPending(sessionId)

        alt User approves
            User->>AQ: approve(sessionId, decision)
            AQ-->>DAR: approved
        else User denies
            User->>AQ: deny(sessionId, note)
            AQ-->>DAR: denied
        else Timeout
            DAR->>DAR: Break after 5 min
        end

        alt Interrupted
            DAR->>DAR: interruptToken.isCancelled(sessionId)
            DAR-->>DAR: Turn cancelled
        end
    end

    alt Approved
        DAR->>TE: execute(toolName, toolCallId, args, session)
        TE->>TH: handle(args, context)
        TH-->>TE: ToolResult
        TE-->>DAR: ToolResult
    else Denied
        DAR->>DAR: ToolResult.fail("denied by user")
    end
```

---

## 4. Context Compression

When context exceeds token limits, the compressor summarises older messages.

```mermaid
sequenceDiagram
    participant DAR as DefaultAgentRuntime
    participant EC as ErrorClassifier
    participant CC as ContextCompressor
    participant MC as ModelClient

    DAR->>MC: complete(context, tools)
    MC-->>DAR: Exception: context overflow

    DAR->>EC: classify(exception)
    EC-->>DAR: ErrorType.CONTEXT_OVERFLOW

    DAR->>CC: compress(context, targetChars)

    alt Compression reduces context
        CC-->>DAR: compressed messages (fewer/smaller)
        DAR->>DAR: compressionAttempted = true
        DAR->>MC: complete(compressedContext, tools)
        MC-->>DAR: ChatResponse
    else Compression cannot reduce
        CC-->>DAR: same or larger context
        DAR-->>DAR: Fail with context overflow error
    end
```

### Compression Strategy

```mermaid
flowchart TB
    Start[Context exceeds limit] --> CheckProtect{protectFirstN + protectLastN}
    CheckProtect --> Split[Split messages into<br/>protected + compressible]
    Split --> Summarise[Summarise compressible chunk<br/>via LLM call]
    Summarise --> Reassemble[Reassemble: protected + summary]
    Reassemble --> Retry[Retry model call with compressed context]

    style Summarise fill:#e6a23c,color:#fff
    style Retry fill:#67c23a,color:#fff
```

---

## 5. Curator Cycle

The curator runs periodically to maintain skill health (stale detection, archiving, backups).

```mermaid
sequenceDiagram
    participant Timer as ScheduledExecutor
    participant CS as CuratorService
    participant SM as SkillManager
    participant DB as PostgreSQL
    participant FS as Filesystem

    Timer->>CS: Trigger (interval = 168h default)
    CS->>CS: Check minIdleHours gate

    alt System idle ≥ minIdleHours
        CS->>SM: listAllSkills()
        SM->>DB: SELECT * FROM skills
        DB-->>SM: List<SkillEntity>
        SM-->>CS: all skills

        CS->>CS: Classify: active / stale / archived
        loop For each skill
            alt Last activity > staleAfterDays (30)
                CS->>DB: UPDATE skill SET lifecycleState='stale'
            end
            alt Stale > archiveAfterDays (90)
                CS->>DB: UPDATE skill SET lifecycleState='archived', archived=true
            end
        end

        CS->>FS: Create backup snapshot
        FS-->>CS: Backup path

        CS->>DB: INSERT curator_snapshot (reason, skillCount, manifest)
        CS-->>Timer: Cycle complete
    else System active
        CS-->>Timer: Skipped (not idle enough)
    end
```

---

## 6. Telegram Message Processing

From Telegram update to agent response delivery.

```mermaid
sequenceDiagram
    actor User
    participant TG as Telegram API
    participant LP as LongPollingService
    participant BMP as BotMessageProcessor
    participant AUTH as AuthorizationService
    participant CR as CommandRegistry
    participant BSS as BotSessionStore
    participant ABC as AgentBackendClient
    participant SE as StreamEditor
    participant TYP as TypingManager

    TG-->>LP: getUpdates (long poll)
    LP-->>BMP: UpdateEvent

    BMP->>AUTH: isAuthorized(userId, username)

    alt Not authorized
        BMP-->>User: "Unauthorized" message
    else Authorized
        alt Callback query
            BMP->>BMP: Route to CallbackQueryHandler
        else Slash command
            BMP->>CR: get(commandName)
            CR-->>BMP: CommandHandler
            BMP->>BMP: Execute handler
        else Text / media message
            BMP->>BSS: resolveSession(chatId)
            BSS-->>BMP: BotSessionEntity

            alt Session busy
                BMP->>BMP: Queue via BusySessionHandler
            else Session free
                BMP->>BSS: markBusy(chatId)
                BMP->>TYP: startTyping(chatId)

                BMP->>ABC: streamChat(sessionId, text)
                ABC-->>BMP: SSE stream

                loop Stream tokens
                    BMP->>SE: editMessage(chatId, messageId, partialText)
                    SE->>TG: editMessageText (rate-limited 1.5s)
                end

                BMP->>TYP: stopTyping(chatId)
                BMP->>BSS: markFree(chatId)
                BMP->>SE: finalEdit(chatId, fullResponse)
            end
        end
    end
```

---

## 7. Memory Sync & Background Review

Memory is synced asynchronously after each turn; background review runs with a delay.

```mermaid
sequenceDiagram
    participant DAR as DefaultAgentRuntime
    participant MM as MemoryManager
    participant MP as MemoryProvider
    participant BRS as BackgroundReviewService
    participant VT as VirtualThreadExecutor
    participant LLM as LLM Provider

    DAR->>MP: prefetch(safeInput, sessionId)

    Note over DAR: ... turn executes ...

    DAR->>MM: syncAll(sessionId, messages)
    MM->>VT: submit(() → memoryProvider.syncTurn)
    VT->>MP: syncTurn(sessionId, messages)
    MM->>VT: submit(() → queuePrefetchAll)
    VT->>MP: queuePrefetchAll(input, sessionId)

    DAR->>BRS: reviewTurn(sessionId, messages)
    BRS->>BRS: Schedule with delayMs (2000ms)

    Note over BRS: After delay...
    BRS->>LLM: Summarise turn for actions/insights
    LLM-->>BRS: ReviewSummary

    alt Summary has actions
        BRS->>BRS: storeReviewSummary(sessionId)
        Note over BRS: Surfaced on next turn
    else No actions
        BRS->>BRS: clearFlag(sessionId)
    end
```

---

## 8. Model Call Retry with Error Classification

```mermaid
sequenceDiagram
    participant DAR as DefaultAgentRuntime
    participant MC as ModelClient
    participant EC as ErrorClassifier
    participant CC as ContextCompressor

    loop Retry attempts (max 3)
        DAR->>MC: complete(context, tools, options)
        MC-->>DAR: Exception

        DAR->>EC: classify(exception)
        EC-->>DAR: ErrorType

        alt PERMANENT / BILLING / CONTENT_POLICY
            DAR-->>DAR: Fail immediately
        else RATE_LIMIT
            DAR->>DAR: Backoff: 2s × 2^attempt, cap 30s
        else CONTEXT_OVERFLOW
            DAR->>CC: compress(context, targetChars)
            CC-->>DAR: compressed context
            DAR->>DAR: Retry without consuming attempt
        else RETRYABLE
            DAR->>DAR: Backoff: 500ms × 2^attempt + jitter, cap 5s
        end
    end

    DAR-->>DAR: Throw RuntimeException after max attempts
```