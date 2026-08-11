# ADR-003: LangChain4j as LLM Client Abstraction

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2025-02-01 |
| **Deciders** | Project lead |
| **Tags** | llm, client, abstraction, library |

## Context

The agent needs to call LLM providers for:
1. Chat completions (with tool-calling support).
2. Streaming responses (token-by-token via SSE).
3. Multiple providers (OpenAI, Ollama, compatible endpoints).
4. Error handling (rate limits, context overflow, billing errors).

Options considered:
- **Direct HTTP client** (OkHttp / Java HttpClient): Full control but requires building all request/response models, tool-call schemas, and streaming handlers manually.
- **Spring AI**: Spring's native AI framework — good Spring integration but was still maturing.
- **LangChain4j**: Java port of LangChain. Mature, supports OpenAI-compatible endpoints, streaming, tool-calling, and has a clean `ChatLanguageModel` / `StreamingChatLanguageModel` API.

## Decision

Use **LangChain4j 1.18.0** as the LLM client abstraction layer.

Wrap it behind a `ModelClient` interface to decouple the core runtime from the LangChain4j library:

```java
public interface ModelClient {
    ChatResponse complete(List<Message> messages, List<ToolDefinition> tools, ModelRequestOptions options);
    void stream(List<Message> messages, List<ToolDefinition> tools, StreamingResponseHandler handler);
}
```

`LangChain4jModelClient` implements this interface using LangChain4j's API internally. `NoOpModelClient` provides a stub for the `noop` profile.

## Consequences

**Positive:**
- Provider-agnostic: swap OpenAI ↔ Ollama by changing config, not code.
- Built-in streaming support with callback-based `StreamingResponseHandler`.
- Tool-calling schema generation handled by the library.
- `ErrorClassifier` and `RateLimitTracker` add custom error handling on top.

**Negative:**
- Dependency on LangChain4j's API stability — library version upgrades may require adapter changes.
- LangChain4j's internal models differ from the project's domain models — `OpenAiMapper` (MapStruct) converts between them.
- Some provider-specific features (e.g., OpenAI's `reasoning_effort`, `cache_read_tokens`) need custom headers and parsing beyond the library's API.

**Mitigations:**
- `ModelClient` interface isolates the core runtime — only `LangChain4jModelClient` needs updating on library upgrades.
- `OpenAiMapper` centralises all domain ↔ library model conversion.
- Custom `AgentProperties.ModelProperties` expose provider-specific settings (reasoningEffort, headers map) that LangChain4j doesn't natively support.

## References

- `core/client/ModelClient.java` — interface
- `client/langchain4j/LangChain4jModelClient.java` — implementation
- `client/NoOpModelClient.java` — stub for noop profile
- `client/langchain4j/ErrorClassifier.java` — error categorisation
- `client/langchain4j/RateLimitTracker.java` — rate limit handling
- `api/mapper/OpenAiMapper.java` — domain ↔ LangChain4j mapping
- [LangChain4j documentation](https://docs.langchain4j.dev/)