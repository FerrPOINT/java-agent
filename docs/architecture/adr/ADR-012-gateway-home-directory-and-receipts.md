# ADR-012: Gateway Home-Channel Directory and Outbound Message Receipts (WP-2)

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-09-14 |
| **Deciders** | Project lead |
| **Tags** | gateway, delivery, parity, wp-2 |

## Context

docs/35 WP-2 requires gateway identity, targets, lifecycle and a real messaging
dashboard. Four concrete defects block it:

1. `SendMessageTool` rejects bare home-channel and topic/thread targets — there is
   no durable home mapping and `TelegramAdapter.send` ignores `threadId` entirely.
2. `/set_home` (bot) writes only to in-memory `BotProperties.homeChatId`; the
   value is lost on restart. Backend bare-platform delivery (`CronJobService.ownerChatId()`)
   guesses "first numeric allowed-user-id" instead of reading a persisted home.
3. No outbound message receipts: `react`/`unreact` without an explicit `message_id`
   fail ("Java requires this explicitly"), and delivery acks store the outbound id
   only inside `delivery_work_items` rows scoped to cron/delegate — no general
   per-target last-message state.
4. `MessagingDashboardController` messaging state is static; gateway
   start/stop/restart in `DashboardSystemController` return 501.

Hermes reference (`05d705dd`): `gateway/config.py` `HomeChannel{platform, chat_id,
name, thread_id, user_id, scope_id}` persisted per-platform in config
(`persist_home_channel`), `gateway/delivery.py` `DeliveryTarget.parse` accepting
`origin|local|<platform>|<platform>:<chat_id>[:<thread_id>]`, and
`channel_directory.py` for friendly-name resolution.

## Decision

### 1. Persisted home-channel directory (backend-owned)

New table `gateway_home_channels` (V55):

- PK `(platform, profile)` — one home per platform per profile.
- `chat_id` NOT NULL, `thread_id` nullable (topic where `/set_home` ran), `name`,
  `user_id`, `updated_at`, `updated_by`.

Backend `GatewayHomeChannelService` is the single authority:
`resolve(platform, profile)`, `setHome(...)`, `clearHome(...)`. Bare-platform
targets in `SendMessageTool` and `CronJobService` normalize through it; the
"first allowed-user-id" heuristic in `CronJobService.ownerChatId()` is removed in
favor of the persisted home (fallback: legacy heuristic while the table is empty,
so existing deployments keep working — flagged in logs).

`/set_home` (bot) persists through the backend endpoint
(`PUT /api/gateway/home-channel`) instead of only mutating `BotProperties`.

### 2. Outbound message receipts (backend-owned, general)

New table `outbound_message_receipts` (V55):

- `id UUID PK`, `platform`, `chat_id`, `thread_id` nullable, `session_id` nullable,
  `direction` (`outbound`), `message_id` (platform message id), `content_hash`,
  `idempotency_key` unique, `created_at`.
- Index `(platform, chat_id, thread_id, created_at DESC)` for last-message lookup.

`OutboundReceiptService.record(...)` called by every outbound send path that
learns a platform message id: gateway adapter sends (via
`GatewayRoutingService`), delivery-ledger ack. `lastMessageFor(target)` returns
the most recent receipt for `react`/`unreact` without explicit `message_id`.

Cross-user/session protection: receipts are keyed by target (platform+chat+thread),
reaction routing validates the requesting session's origin matches the receipt's
target scope (same chat) or fails closed with an explicit error.

### 3. GatewayLifecycleService (backend)

Bounded state machine `RUNNING → DRAINING → STOPPED` (+ `FAILED`), held in
memory with a persisted `gateway_runtime_state` row (profile, state, updated_at,
last_error) for dashboard reads across restart. `drain()` stops new inbound
dispatch, waits bounded time for in-flight work, reports active count.
`DashboardSystemController` `/api/gateway/start|stop|restart` and messaging status
cards read this real state instead of static values / 501.

### 4. MessagingDashboardController on real state

- `/api/messaging/platforms` returns real per-platform state derived from
  `GatewayLifecycleService` + adapter connectivity + persisted home channels.
- Telegram config write/test: token write goes through profile config (WP-4
  boundary) — until WP-4, a narrow `PUT /api/messaging/platforms/telegram`
  accepts token/home/allowlist updates into the persisted store introduced here,
  secrets never round-trip (masked read).
- Pairing approve/revoke/clear-pending: implemented over the Telegram
  allowlist in `AgentProperties.Gateway.Telegram` persisted through the same
  narrow config write boundary (approve = add to allowlist, revoke = remove).
  Onboarding start/apply for Telegram reads bot pairing state; WhatsApp remains
  explicit unsupported (no adapter) — honest 501 stays, documented as out of
  product scope until a real adapter exists.
- Webhooks: `/api/webhooks*` implemented over the existing
  `TelegramWebhookController` + `webhookSecret` config (enable/list/toggle/delete
  with signed callback metadata), no generic remote subscription claiming.

### 5. SessionSource enrichment (compatibly)

`SessionSource` record gains nullable `messageId` and `metadata` components with
legacy-compatible constructors. `TelegramAdapter.send` learns `threadId` routing
(`message_thread_id`) and records receipts. No legacy call site breaks.

## Testing

- Unit: home resolve/fallback-legacy-then-persisted, receipt record/last lookup,
  cross-session reaction guard, lifecycle state transitions + drain bound.
- PostgreSQL (slowTest): concurrent setHome, receipt idempotency key collision,
  last-message ordering, gateway_runtime_state recovery after restart.
- Controller: platforms status real state; pairing approve/revoke mutates
  allowlist; webhooks CRUD over secret config; no 501 left on implemented routes.
- E2E (fixture): set home → send bare `telegram` target → receipt recorded →
  `react` without message_id → drain → reject inbound → stop.
- Delivery E2E: cron job with bare-platform deliver → ledger claim resolves home
  from `gateway_home_channels` (not the legacy heuristic).

## Consequences

- `delivery_work_items` remains the sole cron/delegate delivery lane (WP-1
  cutover preserved); outbound receipts are a cross-cutting send-state store, not
  a second delivery ledger.
- The legacy owner heuristic is kept only as empty-table fallback to avoid
  breaking the running dev deployment; removal tracked in WP-4 config boundary.
- WhatsApp/QR onboarding stays explicitly unsupported — honest 501, not faked.
