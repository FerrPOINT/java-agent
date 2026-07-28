# Telegram Gateway — Java Porting Summary

> Source analyzed: `/opt/dev/java-agent/prototype/python-agent/gateway`  
> Target Java package root: `com.azhukov.agent.gateway.*`  
> Classification per platform: **port / skip / defer**  
> Credentials/keys in the original source are redacted and shown as `[REDACTED]`.

---

## 1. Target Java Package Layout

```text
com.azhukov.agent.gateway
├── config
│   ├── GatewayConfig.java              # GatewayConfig, Platform enum, loadGatewayConfig, env overrides
│   ├── Platform.java                   # enum Platform + dynamic plugin members
│   ├── PlatformConfig.java             # Per-platform config DTO
│   └── PlatformTokenMap.java           # Platform -> env token name mapping
├── core
│   ├── GatewayRunner.java              # Lifecycle, adapter startup, inbound routing
│   ├── GatewayAuthorizationMixin.java  # User/chat authorization logic
│   ├── GatewaySlashCommandsMixin.java  # /new, /reset, /model, /approve, /deny, etc.
│   └── GatewayKanbanWatchersMixin.java # Non-gateway board watchers
├── session
│   ├── SessionSource.java                # platform, chat_id, chat_type, user_id, user_name, thread_id, profile
│   ├── SessionStore.java                 # Key generation / persistence
│   └── PairingStore.java                 # Pairing-code authorization
├── adapter
│   ├── BasePlatformAdapter.java          # Abstract base adapter
│   ├── MessageHandler.java               # Inbound message handler functional interface
│   ├── MessageEvent.java                 # text, message_type, source, internal
│   ├── SendResult.java                   # success, message_id, error
│   └── PlatformAdapterFactory.java       # _create_adapter equivalent
├── adapter/telegram
│   ├── TelegramAdapter.java              # Long-polling + webhook
│   ├── TelegramMessageType.java
│   └── TelegramWebhookConfig.java
├── adapter/discord     # plugin wrapper
├── adapter/slack       # plugin wrapper
├── adapter/whatsapp
│   ├── WhatsAppCloudAdapter.java
│   └── WhatsAppCloudConfig.java
├── adapter/api
│   ├── ApiServerAdapter.java
│   └── WebhookAdapter.java
├── adapter/graph
│   └── MsGraphWebhookAdapter.java
├── adapter/signal
├── adapter/weixin
├── adapter/qqbot
├── adapter/yuanbao
├── adapter/bluebubbles
├── delivery
│   ├── DeliveryRouter.java
│   └── DeliveryObligation.java
├── hooks
│   └── HookRegistry.java
├── status
│   └── RuntimeStatusWriter.java
└── run
    └── GatewayApplication.java         # java -jar entry point / Spring Boot app
```

> **Rationale:** Mirror the Python package boundaries (`gateway/`, `gateway/platforms/`, `gateway/config.py`, `gateway/run.py`, `gateway/authz_mixin.py`, `gateway/session.py`) while collapsing the flat module into cohesive Java packages.

---

## 2. Platform Adapter Inventory

### 2.1 Built-in adapters under `gateway/platforms/*.py` (28 files)

| Platform | Python File(s) | Java Package | Verdict | Notes |
|----------|----------------|--------------|---------|-------|
| **Base contract** | `base.py` | `adapter.BasePlatformAdapter` | **port** | Abstract base for all adapters. |
| **Telegram** | `telegram.py` | `adapter.telegram.TelegramAdapter` | **port** | Long-polling + webhook, media, commands, DM topics. |
| **WhatsApp Cloud** | `whatsapp_cloud.py`, `whatsapp_common.py` | `adapter.whatsapp.WhatsAppCloudAdapter` | **port** | Uses Meta Cloud API, webhook server. |
| **Signal** | `signal.py`, `signal_format.py`, `signal_rate_limit.py` | `adapter.signal` | **defer** | Needs local Signal HTTP bridge (`signal-cli-rest-api`). |
| **Weixin (WeChat)** | `weixin.py` | `adapter.weixin.WeixinAdapter` | **defer** | Chinese-specific crypto/validation. |
| **QQBot** | `qqbot/*.py` (7 files) | `adapter.qqbot` | **defer** | Heavy onboarding + chunked upload. |
| **Yuanbao** | `yuanbao.py`, `yuanbao_media.py`, `yuanbao_proto.py`, `yuanbao_sticker.py` | `adapter.yuanbao` | **defer** | Tencent Yuanbao WebSocket stack. |
| **API Server** | `api_server.py` | `adapter.api.ApiServerAdapter` | **port** | Stateless HTTP API for tools/clients. |
| **Webhook** | `webhook.py`, `webhook_filters.py` | `adapter.api.WebhookAdapter` | **port** | Generic inbound webhook with HMAC validation. |
| **MS Graph Webhook** | `msgraph_webhook.py` | `adapter.graph.MsGraphWebhookAdapter` | **defer** | Microsoft Teams meeting pipeline integration. |
| **BlueBubbles** | `bluebubbles.py` | `adapter.bluebubbles.BlueBubblesAdapter` | **defer** | iMessage bridge, macOS/BlueBubbles server required. |
| Helpers | `helpers.py`, `_http_client_limits.py` | `adapter.internal` | **port** | Shared HTTP client limits / formatting. |

### 2.2 Plugin adapters under `plugins/platforms/` (79 files)

| Platform | Plugin Path | Verdict | Notes |
|----------|-------------|---------|-------|
| **Discord** | `plugins/platforms/discord/` | **port** | Active community platform; move to `adapter.discord`. |
| **Slack** | `plugins/platforms/slack/` | **port** | Enterprise/team messaging; move to `adapter.slack`. |
| **Mattermost** | `plugins/platforms/mattermost/` | **defer** | Self-hosted Slack alternative. |
| **Matrix** | `plugins/platforms/matrix/` | **defer** | Decentralized chat; E2EE complexity. |
| **Teams** | `plugins/platforms/teams/` | **defer** | Microsoft Teams; overlaps with MSGraph. |
| **Google Chat** | `plugins/platforms/google_chat/` | **defer** | Workspace chat. |
| **Feishu/Lark** | `plugins/platforms/feishu/` | **defer** | Chinese enterprise chat. |
| **DingTalk** | `plugins/platforms/dingtalk/` | **defer** | Alibaba enterprise chat. |
| **WeCom** | `plugins/platforms/wecom/` | **defer** | WeChat Work; overlaps with `gateway/platforms/weixin.py`. |
| **IRC** | `plugins/platforms/irc/` | **skip** | Low priority; legacy chat protocol. |
| **LINE** | `plugins/platforms/line/` | **defer** | Asian messaging platform. |
| **SimpleX** | `plugins/platforms/simplex/` | **defer** | Privacy-focused messenger. |
| **SMS (Twilio)** | `plugins/platforms/sms/` | **defer** | Uses Twilio; overlaps with built-in `Platform.SMS`. |
| **Email** | `plugins/platforms/email/` | **defer** | IMAP/SMTP polling; built-in `Platform.EMAIL` exists. |
| **Home Assistant** | `plugins/platforms/homeassistant/` | **defer** | IoT state-change events. |
| **Photon** | `plugins/platforms/photon/` | **skip** | macOS/iMessage sidecar (Node sidecar). |
| **ntfy** | `plugins/platforms/ntfy/` | **skip** | Push notification relay, not conversational. |
| **Raft** | `plugins/platforms/raft/` | **skip** | Experimental internal relay. |

### 2.3 Verdict summary

| Verdict | Count | Rationale |
|---------|-------|-----------|
| **port** | 7 built-in + 2 plugins = 9 | Core messaging contract + Telegram, Discord, Slack, WhatsApp Cloud, API Server, Webhook. |
| **defer** | 8 built-in + 13 plugins = 21 | Requires external bridges, region-specific SDKs, or enterprise integrations. |
| **skip** | 3 | IRC, ntfy, Raft are low value for first Java port. |

---

## 3. Message Routing Flow

### 3.1 Python source

- **`gateway/run.py`** → `GatewayRunner.start()` / `_handle_message()`.
- **`gateway/platforms/base.py`** → `BasePlatformAdapter` with `set_message_handler(...)`.

### 3.2 GatewayRunner lifecycle (port to `core.GatewayRunner`)

1. **Load config**  
   `GatewayConfig config = loadGatewayConfigForRunner();`
2. **Discover plugins**  
   `discover_plugins()` → populate `PlatformRegistry`.
3. **Build stores**  
   - `SessionStore` → `com.azhukov.agent.gateway.session.SessionStore`
   - `PairingStore` → `com.azhukov.agent.gateway.session.PairingStore`
   - `DeliveryRouter` → `com.azhukov.agent.gateway.delivery.DeliveryRouter`
   - `HookRegistry` → `com.azhukov.agent.gateway.hooks.HookRegistry`
4. **For each `platform` in `config.getConnectedPlatforms()`**
   - `adapter = adapterFactory.create(platform, platformConfig);`
   - Wire handlers:
     ```java
     adapter.setMessageHandler(this::handleMessage);
     adapter.setFatalErrorHandler(this::handleAdapterFatalError);
     adapter.setSessionStore(sessionStore);
     adapter.setBusySessionHandler(this::handleActiveSessionBusyMessage);
     adapter.setTopicRecoveryFn(this::recoverTelegramTopicThreadId);
     adapter.setAuthorizationCheck(makeAdapterAuthCheck(platform));
     ```
   - `connect()` with timeout.
   - On success: `adapters.put(platform, adapter)`.
   - On failure: queue in `_failedPlatforms` for reconnect watcher.
5. **Start background watchers**
   - loop heartbeat
   - reconnect watcher
   - session expiry watcher
   - scale-to-zero watcher (relay-only deployments)
6. **Resume interrupted sessions** after startup.

### 3.3 Inbound message processor (port to `core.GatewayRunner.handleMessage`)

| Step | Original Python | Java Mapping |
|------|-----------------|--------------|
| Reset session ContextVars | `gateway.session_context.reset_session_vars()` | `SessionContext.reset()` |
| Ignored-channel drop | `_is_slack_ignored_channel(...)` | `SlackIgnorePolicy.test(source)` |
| Startup-restore queue | `_queue_startup_restore_event(event)` | `startupRestoreQueue.offer(event)` |
| Scale-to-zero inbound clock | `_scale_to_zero_note_real_inbound()` | `ScaleToZeroClock.ping()` |
| Plugin hook `pre_gateway_dispatch` | `_invoke_hook(...)` | `HookRegistry.emit("pre_gateway_dispatch", event)` |
| Authorization | `_is_user_authorized(source)` | `GatewayAuthorizationMixin.isUserAuthorized(source)` |
| Unauthorized DM behavior | `_get_unauthorized_dm_behavior(...)` + pairing code | `PairingStore` flow |
| Intercept update prompts | `_update_prompt_pending` | `UpdatePromptInterceptor` |
| Intercept clarify prompts | `clarify_gateway` | `ClarifyInterceptor` |
| Intercept slash-confirm | `slash_confirm` | `SlashConfirmInterceptor` |
| Resolve session key | `_session_key_for_source(source)` | `SessionStore.generateKey(source)` |
| Resolve adapter | `_adapter_for_source(source)` | `AdapterResolver.forSource(source)` |
| Handle command / run agent | `_handle_message_with_agent(...)` | `AgentDispatcher.dispatch(event, source, key, generation)` |
| Post-turn flush | `_process_message_background` | `TurnPostProcessor.run(...)` |

### 3.4 Key routing rules to preserve

- **Internal events skip authorization**.
- **Slack ignored channels drop before auth**.
- **Startup restore queues real inbound** until synthetic resume turns finish.
- **Busy sessions** are handled by `_handle_active_session_busy_message`:
  - Draining → queue or reject.
  - Pending approval → route plain-text "yes/no" to approval handlers.
  - Internal events → never interrupt.
  - `busy_text_mode = interrupt | queue | ignore`.
- **Session key** depends on `group_sessions_per_user`, `thread_sessions_per_user`, and multiplex `profile`.

---

## 4. Telegram Adapter — Long-Polling vs Webhook

### 4.1 Source

- `gateway/platforms/telegram.py` — `TelegramAdapter.connect()`.

### 4.2 Connection logic

```text
if TELEGRAM_WEBHOOK_URL is set:
    WEBHOOK mode
else:
    LONG-POLLING mode (default)
```

### 4.3 Java mapping

| Concern | Python | Java |
|---------|--------|------|
| Entry | `TelegramAdapter.connect(is_reconnect)` | `TelegramAdapter.connect(boolean reconnect)` |
| Token | `self.config.token` (from `TELEGRAM_BOT_TOKEN`) | `telegramConfig.getToken()` |
| HTTP client | `python-telegram-bot` + `HTTPXRequest` | Spring WebClient / TelegramBots Java library |
| Custom base URL | `extra.base_url`, `extra.base_file_url`, `extra.local_mode` | `TelegramClientConfig` |
| Fallback IPs | `AGENT_TELEGRAM_DISABLE_FALLBACK_IPS`, `TelegramFallbackTransport` | `TelegramTransportConfiguration` |
| Proxy | `TELEGRAM_PROXY` → `resolve_proxy_url()` | `ProxyConfiguration` |
| Polling mode | `start_polling(allowed_updates=Update.ALL_TYPES, drop_pending_updates=True, error_callback=...)` | `TelegramLongPollingService` |
| Webhook mode | `start_webhook(listen="0.0.0.0", port, url_path, webhook_url, secret_token)` | `TelegramWebhookController` |
| Webhook secret | `TELEGRAM_WEBHOOK_SECRET` **required** | `TelegramWebhookSecretValidator` |
| Register commands | `set_my_commands` for Default / Private / Group scopes | `TelegramCommandRegistrar` |
| DM topics | `extra.dm_topics` + `_setup_dm_topics()` | `TelegramDmTopicManager` |
| Status indicator | `extra.status_indicator` toggles bot short description | `TelegramStatusIndicatorService` |
| Update handlers | text, command, location, media, callback query | `TelegramUpdateDispatcher` |
| Media batching | `AGENT_TELEGRAM_MEDIA_BATCH_DELAY_SECONDS` | `MediaBatchBuffer` |
| Text batching | `AGENT_TELEGRAM_TEXT_BATCH_DELAY_SECONDS` | `TextBatchBuffer` |

### 4.4 Webhook requirements

- Public HTTPS URL (`TELEGRAM_WEBHOOK_URL`).
- Local listen port (`TELEGRAM_WEBHOOK_PORT`, default **8443**).
- **Required** secret token (`TELEGRAM_WEBHOOK_SECRET`) — fail-closed if missing.
- Path derived from URL or defaults to `/telegram`.
- Server must bind `0.0.0.0`.

### 4.5 Long-polling requirements

- Delete stale webhook first to avoid silent update loss.
- Custom error callback for **polling conflicts** and **network errors**.
- Auto-reconnect with exponential backoff.

### 4.6 Message-length rules

- `MAX_MESSAGE_LENGTH = 4096` (legacy MarkdownV2).
- `RICH_MESSAGE_MAX_CHARS = 32768` (Bot API 10.1 rich messages).
- Length function: UTF-16 code units.

---

## 5. Authorization by User ID / Username

### 5.1 Source

- `gateway/authz_mixin.py` → `GatewayAuthorizationMixin._is_user_authorized(source)`.
- Called from `GatewayRunner._handle_message` and `BasePlatformAdapter._is_sender_authorized`.

### 5.2 Java mapping

```java
public interface GatewayAuthorization {
    boolean isUserAuthorized(SessionSource source);
    String getUnauthorizedDmBehavior(Platform platform, String profile);
}
```

### 5.3 Decision order (preserve exactly)

1. **System platforms always allowed**: `HOMEASSISTANT`, `WEBHOOK`.
2. **Upstream relay authorization**: `source.deliveredViaUpstreamRelay == true` OR adapter `authorizationIsUpstream == true`.
3. **Group/forum/channel chat-ID allowlist** (even when `user_id` is null):
   - `TELEGRAM_GROUP_ALLOWED_CHATS`
   - `QQ_GROUP_ALLOWED_USERS`
   - `group_allowed_chats` from adapter `extra`
4. **Bot allowlist**: `DISCORD_ALLOW_BOTS`, `FEISHU_ALLOW_BOTS`, `TELEGRAM_ALLOW_BOTS`, `SLACK_ALLOW_BOTS` with values `mentions` / `all`.
5. **Missing user_id → deny** (after chat-scoped checks).
6. **Per-platform allow-all** (`TELEGRAM_ALLOW_ALL_USERS`, etc.).
7. **Adapter-verified role auth** (`source.roleAuthorized == true`).
8. **Pairing store approval** (`PairingStore.isApproved(platform, userId)`).
9. **Environment allowlists**:
   - Per-platform: `TELEGRAM_ALLOWED_USERS`, `DISCORD_ALLOWED_USERS`, ...
   - Group user scoped: `TELEGRAM_GROUP_ALLOWED_USERS`
   - Group chat scoped: `TELEGRAM_GROUP_ALLOWED_CHATS`, `QQ_GROUP_ALLOWED_USERS`
   - Global: `GATEWAY_ALLOWED_USERS`
10. **No allowlist configured**:
    - If adapter enforces own access policy with effective `allowlist` → allow.
    - Else if `GATEWAY_ALLOW_ALL_USERS` → allow.
    - Else → deny.
11. **Wildcard `*`** in any allowlist allows everyone.
12. **Username normalization**:
    - If `user_id` contains `@`, also check the local part.
    - WhatsApp: expand phone ↔ LID aliases.
    - SimpleX: also match `user_name`.

### 5.4 Environment variables to map

| Category | Variables (values redacted in code) |
|----------|-------------------------------------|
| Telegram | `TELEGRAM_ALLOWED_USERS`, `TELEGRAM_GROUP_ALLOWED_USERS`, `TELEGRAM_GROUP_ALLOWED_CHATS`, `TELEGRAM_ALLOW_ALL_USERS`, `TELEGRAM_ALLOW_BOTS` |
| Discord | `DISCORD_ALLOWED_USERS`, `DISCORD_ALLOW_ALL_USERS`, `DISCORD_ALLOW_BOTS` |
| Slack | `SLACK_ALLOWED_USERS`, `SLACK_ALLOW_ALL_USERS`, `SLACK_ALLOW_BOTS` |
| WhatsApp | `WHATSAPP_ALLOWED_USERS`, `WHATSAPP_ALLOW_ALL_USERS` |
| WhatsApp Cloud | `WHATSAPP_CLOUD_ALLOWED_USERS`, `WHATSAPP_CLOUD_ALLOW_ALL_USERS` |
| Signal | `SIGNAL_ALLOWED_USERS`, `SIGNAL_GROUP_ALLOWED_USERS`, `SIGNAL_ALLOW_ALL_USERS` |
| Email | `EMAIL_ALLOWED_USERS`, `EMAIL_ALLOW_ALL_USERS` |
| SMS | `SMS_ALLOWED_USERS`, `SMS_ALLOW_ALL_USERS` |
| Mattermost | `MATTERMOST_ALLOWED_USERS`, `MATTERMOST_ALLOW_ALL_USERS` |
| Matrix | `MATRIX_ALLOWED_USERS`, `MATRIX_ALLOW_ALL_USERS` |
| DingTalk | `DINGTALK_ALLOWED_USERS`, `DINGTALK_ALLOW_ALL_USERS` |
| Feishu | `FEISHU_ALLOWED_USERS`, `FEISHU_ALLOW_ALL_USERS`, `FEISHU_ALLOW_BOTS` |
| WeCom | `WECOM_ALLOWED_USERS`, `WECOM_ALLOW_ALL_USERS` |
| WeCom Callback | `WECOM_CALLBACK_ALLOWED_USERS`, `WECOM_CALLBACK_ALLOW_ALL_USERS` |
| Weixin | `WEIXIN_ALLOWED_USERS`, `WEIXIN_ALLOW_ALL_USERS` |
| BlueBubbles | `BLUEBUBBLES_ALLOWED_USERS`, `BLUEBUBBLES_ALLOW_ALL_USERS` |
| QQ | `QQ_ALLOWED_USERS`, `QQ_GROUP_ALLOWED_USERS`, `QQ_ALLOW_ALL_USERS` |
| Yuanbao | `YUANBAO_ALLOWED_USERS`, `YUANBAO_ALLOW_ALL_USERS` |
| Global | `GATEWAY_ALLOWED_USERS`, `GATEWAY_ALLOW_ALL_USERS` |

> **No credentials/values are stored in this document.** All tokens are read at runtime via Spring `Environment` / `.env` / secret scope.

### 5.5 Unauthorized DM behavior

- Config key: `unauthorized_dm_behavior` at platform or global level.
- Email defaults to `ignore`.
- When an allowlist is configured, default to `ignore` (do not spam pairing codes).
- Otherwise `pair` → generate pairing code and ask owner to run `agent pairing approve <platform> <code>`.
- Rate-limit pairing responses per `(platform, user_id)`.

---

## 6. Configuration Mapping

### 6.1 `Platform` enum

From `gateway/config.py`:

```java
public enum Platform {
    LOCAL("local"),
    TELEGRAM("telegram"),
    DISCORD("discord"),
    WHATSAPP("whatsapp"),
    WHATSAPP_CLOUD("whatsapp_cloud"),
    SLACK("slack"),
    SIGNAL("signal"),
    MATTERMOST("mattermost"),
    MATRIX("matrix"),
    HOMEASSISTANT("homeassistant"),
    EMAIL("email"),
    SMS("sms"),
    DINGTALK("dingtalk"),
    API_SERVER("api_server"),
    WEBHOOK("webhook"),
    MSGRAPH_WEBHOOK("msgraph_webhook"),
    FEISHU("feishu"),
    WECOM("wecom"),
    WECOM_CALLBACK("wecom_callback"),
    WEIXIN("weixin"),
    BLUEBUBBLES("bluebubbles"),
    QQBOT("qqbot"),
    YUANBAO("yuanbao"),
    RELAY("relay");
}
```

Dynamic plugin platforms discovered from `plugins/platforms/<name>/plugin.yaml`.

### 6.2 Token / credential environment map

| Platform | Env var for token/credential |
|----------|-------------------------------|
| Telegram | `TELEGRAM_BOT_TOKEN` |
| Discord | `DISCORD_BOT_TOKEN` |
| Slack | `SLACK_BOT_TOKEN` |
| Mattermost | `MATTERMOST_TOKEN` |
| Matrix | `MATRIX_ACCESS_TOKEN` |
| Weixin | `WEIXIN_TOKEN` |
| WhatsApp Cloud | `WHATSAPP_CLOUD_APP_ID`, `WHATSAPP_CLOUD_APP_SECRET`, `WHATSAPP_CLOUD_WABA_ID`, `WHATSAPP_CLOUD_VERIFY_TOKEN` |
| API Server | `API_SERVER_KEY` |
| Home Assistant | `HOMEASSISTANT_TOKEN` |
| SMS | `TWILIO_AUTH_TOKEN` |
| BlueBubbles | `BLUEBUBBLES_SERVER_URL`, `BLUEBUBBLES_PASSWORD` |
| QQ | `QQ_APP_ID`, `QQ_CLIENT_SECRET` |
| Feishu | `FEISHU_APP_ID`, `FEISHU_APP_SECRET`, `FEISHU_ENCRYPT_KEY`, `FEISHU_VERIFICATION_TOKEN` |
| DingTalk | `DINGTALK_APP_KEY`, `DINGTALK_APP_SECRET` |
| WeCom | `WECOM_CORP_ID`, `WECOM_SECRET`, etc. |
| Signal | `SIGNAL_HTTP_URL`, `SIGNAL_ACCOUNT` |

All values read at runtime; placeholder in docs is `[REDACTED]`.

### 6.3 `GatewayConfig` responsibilities

| Method / Field | Java Equivalent |
|----------------|-----------------|
| `GatewayConfig.platforms` | `Map<Platform, PlatformConfig>` |
| `GatewayConfig.get_connected_platforms()` | `GatewayConfig.getConnectedPlatforms()` |
| `GatewayConfig.sessions_dir` | `GatewayConfig.getSessionsDir()` |
| `GatewayConfig.multiplex_profiles` | `GatewayConfig.isMultiplexProfiles()` |
| `GatewayConfig.group_sessions_per_user` | `GatewayConfig.isGroupSessionsPerUser()` |
| `GatewayConfig.thread_sessions_per_user` | `GatewayConfig.isThreadSessionsPerUser()` |
| `load_gateway_config()` | `GatewayConfigLoader.load()` |
| `_apply_env_overrides()` | `EnvironmentOverrideApplier.apply(config)` |

### 6.4 Per-platform enablement predicates

From `gateway/config.py` `_PLATFORM_CONNECTED_CHECKERS`:

| Platform | Enablement predicate |
|----------|----------------------|
| Weixin | `WEIXIN_TOKEN` set |
| WhatsApp Cloud | `WHATSAPP_CLOUD_*` set |
| Signal | `http_url` extra set |
| API Server | usable `API_SERVER_KEY` |
| Webhook | always enabled if configured |
| MSGraph Webhook | `tenant_id` / `client_id` extras set |
| BlueBubbles | server URL + password set |
| QQ | app ID + secret set |
| Yuanbao | token set |
| Relay | `relay_url` / secret set |

---

## 7. Base Adapter Contract to Port

From `gateway/platforms/base.py`:

```java
public abstract class BasePlatformAdapter {
    protected PlatformConfig config;
    protected Platform platform;
    protected MessageHandler messageHandler;
    protected Consumer<BasePlatformAdapter> fatalErrorHandler;
    protected SessionStore sessionStore;
    protected BiFunction<MessageEvent, String, CompletableFuture<Boolean>> busySessionHandler;
    protected TriFunction<String, String, String, Boolean> authorizationCheck;

    public abstract CompletableFuture<Boolean> connect(boolean isReconnect);
    public abstract CompletableFuture<Void> disconnect();
    public abstract CompletableFuture<SendResult> send(String chatId, String content,
                                                       String replyTo, Map<String,Object> metadata);

    // Optional overrides
    public CompletableFuture<SendResult> editMessage(String chatId, String messageId,
                                                     String content, boolean finalize) { ... }
    public CompletableFuture<Boolean> deleteMessage(String chatId, String messageId) { ... }
    public CompletableFuture<SendResult> sendDraft(...) { ... }
    public CompletableFuture<SendResult> sendClarify(...) { ... }
    public CompletableFuture<SendResult> sendPrivateNotice(...) { ... }
    public CompletableFuture<Void> sendTyping(String chatId, Map<String,Object> metadata) { ... }
    public CompletableFuture<String> createHandoffThread(String parentChatId, String name) { ... }

    // Capability flags
    public boolean supportsCodeBlocks() { return false; }
    public boolean supportsStatusText() { return false; }
    public boolean supportsAsyncDelivery() { return true; }
    public boolean splitsLongMessages() { return false; }
    public String typedCommandPrefix() { return "/"; }
    public boolean supportsInchannelContinuable() { return false; }
    public boolean isInteractiveResume() { return true; }
    public boolean enforcesOwnAccessPolicy() { return false; }
    public boolean authorizationIsUpstream() { return false; }
    public boolean requiresEditFinalize() { return false; }
}
```

---

## 8. Port Order Recommendation

1. **Foundation** — `config`, `session`, `adapter` base, `SendResult`, `MessageEvent`.
2. **Authorization** — `GatewayAuthorizationMixin` + `PairingStore`.
3. **Core runner** — `GatewayRunner` lifecycle, reconnect watcher, adapter factory.
4. **Telegram** — long-polling service + webhook controller + update dispatcher.
5. **API Server + Webhook** — stateless HTTP surfaces for tools and integrations.
6. **WhatsApp Cloud** — Meta webhooks + send API.
7. **Discord plugin port** — move from `plugins/platforms/discord` to `adapter.discord`.
8. **Slack plugin port** — move from `plugins/platforms/slack` to `adapter.slack`.
9. **Deferred platforms** — implement behind a `DeferredAdapter` stub that logs "not implemented".

---

## 9. Notes & Warnings

- **Fail-closed by default**: no allowlist + no `ALLOW_ALL` → deny. This must be preserved exactly.
- **Webhook secret**: Telegram webhook mode refuses to start without `TELEGRAM_WEBHOOK_SECRET`.
- **Multiplexing**: per-profile adapter maps (`_profile_adapters`) isolate tokens and pairing stores.
- **Session context**: reset ContextVars at handler entry to prevent cross-session leaks in async dispatch.
- **Scale-to-zero**: only real user inbound updates the idle clock; internal events do not.
- **No credentials preserved**: any hardcoded example values in original code are replaced with `[REDACTED]`.

---

*Document generated from analysis of `/opt/dev/java-agent/prototype/python-agent/gateway`.*
