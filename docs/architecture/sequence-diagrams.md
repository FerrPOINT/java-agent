# Sequence Diagrams

Key runtime flows for the Java Agent platform, rendered as Mermaid sequence diagrams.

---

## 1. Conversation Loop (One Turn)

```mermaid
sequenceDiagram
    participant U as User
    participant Runtime as DefaultAgentRuntime
    participant Context as ContextEngine
    participant Model as ModelClient
    participant Tools as ToolExecutionService
    participant Memory as MemoryProvider

    U->>Runtime: runTurn(session, input, refs)
    Runtime->>Memory: prefetch(input, sessionId)
    Runtime->>Context: prepareContext(session, messages)
    Context-->>Runtime: prepared messages
    loop tool calls
        Runtime->>Model: complete(context, tools)
        Model-->>Runtime: ChatResponse (toolCalls)
        Runtime->>Tools: execute(toolCall) [parallel via virtual threads]
        Tools-->>Runtime: ToolResult[]
    end
    Runtime->>Model: complete(context + toolResults, tools)
    Model-->>Runtime: ChatResponse (final text)
    Runtime->>Memory: syncTurn(sessionId, messages) [async virtual thread]
    Runtime-->>U: TurnResult
```

---

## 2. SSE Streaming

```mermaid
sequenceDiagram
    participant Client
    participant Controller as AgentController
    participant Stream as AgentStreamingService
    participant Model as ModelClient
    participant Tools as ToolExecutionService

    Client->>Controller: POST /agent/chat/stream
    Controller->>Stream: streamChat(message, sessionId, emitter)
    Stream->>Model: stream(context, tools)
    loop token chunks
        Model-->>Stream: delta (token)
        Stream-->>Client: SSE: data:{type:"token",content:...}
    end
    alt has tool calls
        Stream-->>Client: SSE: data:{type:"tool_start",...}
        Stream->>Tools: execute(toolCall)
        Tools-->>Stream: ToolResult
        Stream-->>Client: SSE: data:{type:"tool_result",...}
        Stream->>Model: continue with tool results
    end
    Stream-->>Client: SSE: data:{type:"done"}
```

---

## 3. Context Compression

```mermaid
sequenceDiagram
    participant Runtime as AgentRuntime
    participant Engine as ContextEngine
    participant Compressor as DefaultContextCompressor
    participant Model as ModelClient
    participant Lock as CompressionLockRepository

    Runtime->>Engine: prepareContext(session, messages)
    Engine->>Compressor: compress(messages, targetChars)
    Compressor->>Lock: isLocked(sessionId, generation)?
    Lock-->>Compressor: false
    Compressor->>Compressor: split: protect head (N=3) + tail (N=6)
    Compressor->>Compressor: prune tool outputs (500 char cap)
    Compressor->>Model: summarize(middle messages)
    Model-->>Compressor: summary text
    Compressor->>Compressor: build: [REFERENCE ONLY prefix] + summary + tail
    Compressor->>Lock: lock(sessionId, generation)
    Compressor-->>Engine: compressed messages
```

---

## 4. Model API Call Retry

```mermaid
sequenceDiagram
    participant Runtime as AgentRuntime
    participant Classifier as ErrorClassifier
    participant Model as ModelClient

    Runtime->>Model: complete(context, tools)
    Model-->>Runtime: exception
    Runtime->>Classifier: classify(exception)
    alt RETRYABLE
        Runtime->>Runtime: wait (500ms * 2^attempt + jitter, cap 5s)
        Runtime->>Model: complete(context, tools) [retry]
    else RATE_LIMIT
        Runtime->>Runtime: wait (2s * 2^attempt, cap 30s)
        Runtime->>Model: complete(context, tools) [retry]
    else CONTEXT_OVERFLOW
        Runtime->>Runtime: trigger compression
        Runtime->>Model: complete(compressed, tools) [retry, no attempt consumed]
    else PERMANENT / BILLING / CONTENT_POLICY
        Runtime-->>Runtime: TurnResult.error()
    end
```

---

## 5. Tool Loop Detection

```mermaid
sequenceDiagram
    participant Runtime as AgentRuntime
    participant Guard as ToolGuardrails
    participant Tools as ToolExecutionService

    loop tool calls in turn
        Runtime->>Guard: recordToolCall(name, args, success)
        alt 5+ identical calls (idempotent) or 7+ (mutating)
            Guard->>Guard: set halted = true
            Guard-->>Runtime: isHalted() = true
            Runtime-->>Runtime: TurnResult("Halted by guardrails")
        else 3+ consecutive failures
            Guard->>Guard: set halted = true
        else 10+ total calls
            Guard-->>Runtime: log warning
        end
    end
```

---

## 6. Approval Flow

```mermaid
sequenceDiagram
    participant Runtime as AgentRuntime
    participant Guard as ToolGuardrails
    participant Queue as ApprovalQueue
    participant User as User (via API)

    Runtime->>Guard: requiresApproval(toolCall)?
    Guard-->>Runtime: true
    Runtime->>Queue: requestApproval(toolName, args)
    Queue-->>Runtime: approvalId (pending)
    Queue->>User: GET /agent/approvals/pending
    User->>Queue: POST /agent/approvals/{id}/approve
    Queue-->>Runtime: isApproved = true
    Runtime->>Runtime: proceed with tool execution
```