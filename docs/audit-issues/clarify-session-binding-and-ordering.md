# Blocking clarify resolves the wrong pending turn and accepts cross-chat callbacks

Labels: `bug`, `agent-audit`, `hermes-parity` (local draft; GitHub authentication unavailable)

## Reproduction

1. Register two blocking clarify prompts against the same backend session.
2. Post a typed reply to `/api/v1/agent/session/{sessionId}/clarify/text`.
3. Observe that `ClarifyGatewayStore.pendingForSession()` takes `ConcurrentHashMap.values().findFirst()`, whose iteration order is unspecified, so the reply can resolve either prompt.
4. Render a Telegram clarify prompt with a backend session UUID different from the bot session UUID, then type a valid choice. `ClarifyTextInterceptor` posts to the bot-session UUID rather than the backend session UUID carried by the SSE event, so the original waiter remains blocked.
5. Deliver a `clfy` callback for an existing prompt from a different Telegram chat. The renderer never records or verifies the prompt chat id and posts the answer to the backend.
6. POST /clarify/resolve with a valid clarify id under a different `{sessionId}` path. The controller resolves by id only and does not verify that the path session owns it.

## Expected behavior

- Typed replies resolve the oldest pending clarify registered for the specified backend session.
- Renderer-owned pending state binds each prompt to its Telegram chat and authoritative backend session UUID.
- A typed answer is posted to that backend UUID, not the bot persistence row UUID.
- Cross-chat callbacks and mismatched backend session paths are rejected before resolving anything.
- Failed selection-shaped input retains the prompt for retry; successful resolution removes only that prompt.

## Affected revision and files

- Audited revision: `8ea13b44` (`origin/main`, 2026-09-23)
- `backend/src/main/java/com/azhukov/agent/core/tool/ClarifyGatewayStore.java`
- `backend/src/main/java/com/azhukov/agent/api/AgentChatController.java`
- `telegram-bot/src/main/java/com/azhukov/agent/bot/keyboard/ClarifyInteractionRenderer.java`
- `telegram-bot/src/main/java/com/azhukov/agent/bot/keyboard/ClarifyTextInterceptor.java`

## Evidence

- `pendingForSession()` explicitly acknowledges unordered `ConcurrentHashMap` iteration, then calls `findFirst()`.
- `ClarifyInteractionRenderer.PromptPayload` contains no chat id; `handleCallback(long chatId, ...)` never compares its input with prompt ownership.
- `ClarifyTextInterceptor.tryResolve()` builds the endpoint from `session.getId()`.
- `AgentChatController.resolveClarify()` calls `store.resolve(clarifyId, ...)` without checking `{sessionId}`.

## Test plan
Add focused regression tests covering:

- two pending prompts resolve in registration order, including repeated resolve/cleanup;
- a mismatched session path cannot resolve a known clarify id;
- a renderer callback from another chat makes no backend request;
- `Other` followed by typed text posts to the backend session UUID when it differs from the bot session UUID;
- failed choice parsing retains the pending prompt and a later valid choice resolves it.

Run:
```text
./gradlew :backend:test --tests "com.azhukov.agent.core.tool.ClarifyGatewayStoreTest" --tests "com.azhukov.agent.api.AgentChatController*"
./gradlew :telegram-bot:test --tests "com.azhukov.agent.bot.keyboard.Clarify*"
```

## Risk

Blocking clarify is a cross-process, user-interaction boundary. Incorrect identity binding can misapply a user answer to another question or session, and a failed resume leaves the active tool turn held until its timeout.

## Proposed fix

Store a monotonic registration sequence and choose the minimum sequence for a session. Add `belongsToSession(sessionId, clarifyId)` at the backend boundary and enforce it before resolution. Keep renderer prompt state keyed by clarify id but include chat id and backend session id; expose a narrow lookup/consume seam for the typed interceptor. Reject absent backend session ids and cross-chat callbacks without backend I/O. Keep successful cleanup scoped to the resolved prompt.
