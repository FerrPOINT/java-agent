# Полный план фиксов и тестов Java Agent

**Дата:** 2026-09-08

**Назначение:** закрыть все известные незавершенные функциональные контракты, а не только девять крупных пунктов в конце `docs/34-hermes-parity-audit-2026-09-01.md`. Документ составлен по исходникам, миграциям, существующим E2E и явным `501`/unsupported-веткам.

## 1. Фактическая точка старта

| Область | Состояние |
|---|---|
| Ветка | `main`, commit `66be1d81` (`0.1.237`) |
| Рабочее дерево перед планом | чистое |
| Unit/integration baseline | `./gradlew test jacocoTestReport` green, 2026-09-08 |
| Backend coverage | LINE `80.26%`, BRANCH `64.00%` |
| Telegram bot coverage | LINE `82.15%`, BRANCH `65.91%` |
| Declarative HTTP E2E | 36 YAML scenarios |
| CLI E2E | 50 cases |
| Основной закрытый фундамент | c1-c3, h10, h12, multi-user mu1-mu14, profile project tree |
| Свободный номер миграции | выше общего `V51`; каталог общий для `db/migration` и `db/postgresql` |

`docs/34` правильно фиксирует крупные parity-группы, но не является источником истины о готовности маршрута: ранее его таблица уже содержала несуществующие `sessions/prune` и `sessions/import`. Перед закрытием каждой задачи ниже проверяется production route, persistence и реальный consumer.

## 2. Правила выполнения

1. Один work package - один законченный пользовательский контракт. Не оставлять UI/API route, который возвращает успех, но не делает действие.
2. Каждый bug получает regression test с наблюдаемым поведением; каждая новая production-служба - unit test.
3. Для ledger, ownership, migration, retry и restart использовать PostgreSQL Testcontainers. H2 допускается только для быстрых controller/unit paths.
4. Flyway version выбирается непосредственно перед созданием миграции после скана **обоих** каталогов миграций. Номера не резервируются в этом документе.
5. Нельзя записывать секреты в YAML, БД, логи, E2E fixtures или ответы API. OAuth/credential persistence требует encrypted-at-rest решения до включения write route.
6. Все profile/user/session операции fail closed: mismatch path/query/body, unknown profile, чужая сущность и missing owner дают 4xx, а не fallback к default profile.
7. Stateful contract проверяется минимум трижды: happy path, повтор/идемпотентность, restart/concurrency/recovery.
8. Dev/prod не трогать без отдельного разрешения. Локальные/noop/Testcontainers проверки входят в каждую волну.

## 3. Общая модель зависимостей

```text
Gateway identity + durable delivery
  -> cron/delegate reinjection + platform delivery
  -> media/reactions/topics + dashboard gateway lifecycle

Persisted profile/config/env store
  -> MCP config/catalog/OAuth writes
  -> skills hub background operations
  -> messaging/platform writes and model/provider settings

Run lifecycle/cancellation core
  -> OpenAI Responses/Runs approvals
  -> execute-code session kernel and RPC
  -> console/PTY command tasks

Browser provider port + provider credentials
  -> cloud/hybrid browser routes
  -> image/audio/vision provider matrix
```

Нельзя начинать dashboard write endpoints до их persistence/service слоя. Нельзя добавлять cloud browser или provider OAuth до единого credential/config boundary. Нельзя реализовывать консоль/PTY как прямой websocket-to-shell shortcut.

## 4. Work packages

### WP-0. Release and test foundation

**Цель:** сделать результаты следующих волн проверяемыми и исключить ложные green-проверки.

**Фиксы**

- Актуализировать устаревшие числа и команды в `AGENTS.md`, `docs/11-test-coverage-plan.md`, `e2e/README.md`; не менять исторические audit-результаты, а помечать их снимками даты.
- Добавить единый Gradle task/скрипт release verification: `test`, JaCoCo XML parsing, `slowTest`, migration smoke, selected HTTP E2E, CLI E2E и boot jar smoke. Не скрывать failures через shell pipe.
- Добавить machine-readable endpoint coverage report: controller mapping vs Java E2E vs YAML E2E. Сценарий housekeeping не считается покрытием endpoint.
- Ввести package gates для затронутых пакетов: LINE >= 75%, а для `tools/code` и реального external-I/O допускается обоснованное исключение, но общий LINE не должен падать ниже 80%.
- Закрыть текущие самые рискованные coverage deficits до соответствующих feature waves: `tools/delegate` 58.9%, `core/agent` 68.2%, `service` 71.0%, `tools/browser` 71.6%, `bot/cron` 45.3%.

**Тесты и критерий готовности**

- `./gradlew test jacocoTestReport`, XML parsing без hard-coded totals.
- `./gradlew slowTest` на real PostgreSQL Testcontainers.
- `./gradlew bootJar` во всех трёх модулях.
- Noop jar smoke: readiness/liveness, chat persistence, new/changed routes; после каждой entity migration обязательна именно эта проверка.
- Документированный отчёт должен перечислять failed/skipped/live-not-run, а не только pass count.

**Зависимости:** нет. Выполняется до и после всех пакетов.

---

### WP-1. Единый durable delivery ledger и gateway consumer

**Проблема:** `DelegatedTaskRunService` уже умеет atomic claim/ack/release/drop, stale claim 5 min, 8 attempts и 48-hour replay cap. Реальная cron delivery по-прежнему живёт в Telegram-only `CronDeliveryPoller`: читает последнее assistant message и двигает high-water mark отдельно. `DeliveryRouter` в bot-модуле - самостоятельная неиспользуемая ветка с отдельным retry/target parsing. Это не гарантирует один раз доставку, не работает для других gateway consumers и не реинжектирует delegate result в parent session.

**Решение**

1. Ввести backend `DeliveryWorkItem` port и общий consumer contract: `claim`, `deliver`, `ack`, `release`, `drop`, `status`, `idempotencyKey`.
2. Сохранить `delegated_task_runs` как источник для delegate; для cron создать отдельный persisted execution delivery ledger либо обобщённую таблицу delivery work items. Решение выбрать через ADR после сравнения: нельзя подменять cron session scan ещё одним in-memory queue.
3. Добавить target model, где `origin`, `local`, `platform:chat_id` и `platform:chat_id:thread_id` нормализуются до structured target. Origin берётся из сохранённого source parent session, не из текущего owner chat.
4. Сделать gateway delivery consumer в backend поверх `GatewayRoutingService`; transport adapters возвращают immutable delivery receipt: platform, target, outbound message id, error category.
5. После успешного delegate delivery один раз записывать parent-session completion/reinjection marker; повторная доставка/restart marker не дублирует.
6. Перенести cron success/failure/no-change delivery в ledger. `[SILENT]` становится terminal acknowledged без отправки. Частичный Telegram chunk failure не может ack весь payload.
7. Заменить `CronDeliveryPoller` и/или `DeliveryRouter` после переноса их уникальных возможностей. Не оставлять два конкурирующих consumer-а одного result.
8. Добавить stalled-run monitor — done (V64, StalledRunPolicy/StalledRunMonitor, recordProgress coalesce): progress events coalesce, stale child runs получают diagnostic state, но не auto-fail без подтверждённого timeout policy.

**Данные/миграции**

- При выборе общей таблицы: `delivery_work_items` с source type/id, profile, user, parent session, structured target JSON, payload reference/hash, state, attempts, claim token/timestamp, idempotency key, delivery receipt/error, created/available/delivered/dropped timestamps.
- При выборе двух ledgers: cron execution ledger получает эквивалентные claim/idempotency/attempt fields. Общая Java state machine и repository port обязательны.
- Индексы на `(state, available_at)`, `(parent_session_id, state)`, уникальность `(source_type, source_id, target_hash)`.

**Unit tests**

- target parse/normalization; `origin` and topic resolution; local delivery; invalid target fail closed.
- success ack; release with exponential/backoff policy; attempt cap; explicit drop; `[SILENT]`; chunk failure; error redaction.
- parent reinjection exactly once; result content and delivery receipt are distinct fields.

**PostgreSQL integration tests**

- two consumers racing one claim; stale claim takeover; restart after claim-before-ack; unique idempotency row; 48h expiry; profile/user ownership; transaction rollback.

**HTTP/bot E2E**

- Delegate: create background run -> terminal result -> one parent-session reinjection -> one outbound message -> repeat poll/restart gives no duplicate.
- Cron: success, failure nudge, no-change and `[SILENT]`; each path verifies persisted execution/delivery state and cleanup.
- Telegram fixture asserts method, chat/thread id, chunk ordering and outbound message id; no real Telegram account required.

**Done when:** docs/34 gaps 3 and 10 are closed by real consumer behavior, not event publication only.

---

### WP-2. Gateway identity, targets, lifecycle and messaging dashboard

**Проблема:** `SessionSource` contains no topic/thread, reply target, channel/home mapping or outbound message state. `SendMessageTool` truthfully rejects bare home-channel and thread/topic targets. `MessagingDashboardController` exposes static cards and several 501 mutations. `DashboardSystemController` gateway start/stop/restart/drain is 501.

**Решение**

1. Extend source/destination domain objects compatibly: platform, chat id, thread id, inbound message id, reply-to id, channel/home key, user/profile, source metadata. Keep legacy constructors/adapters until all call sites migrate.
2. Persist platform route bindings per profile: enabled state, allowed config reference, home target, health/last error, lifecycle state. Secrets never round-trip in dashboard payloads.
3. Add `GatewayLifecycleService`: start/stop/restart/drain/status with a bounded state machine. It manages Java adapters only, never arbitrary OS commands. Drain stops new inbound dispatch, waits bounded time, then reports active work.
4. Make `MessagingDashboardController` resolve requested profile and use real state. Implement Telegram configuration write/test, pairing approve/revoke/clear only for existing Telegram capability. WhatsApp and QR onboarding are not fabricated: either implement a real adapter in a dedicated later package or retain an explicit unsupported response with product scope documented.
5. Implement webhook subscription registry only for the existing webhook adapter and signed request contract; no generic remote subscription claiming.
6. Implement home-channel directory and thread/topic target resolution in gateway service; `SendMessageTool` no longer needs its current explicit limitations.
7. Track most recent outbound message per target/session safely so `react`/`unreact` without message id can work; prevent cross-user/session reaction routing.

**Data/miграции**

- `gateway_profile_bindings`: profile/platform PK, enabled, home target JSON, status, last error/time, configuration revision.
- `gateway_pairing_requests` only if pairing cannot safely reuse bot pairing tables; pending/approved/revoked timestamps and requester identity.
- `gateway_webhook_subscriptions` only for supported adapter events; unique profile/platform/name and signed callback metadata.
- `outbound_message_receipts`: stable delivery id, target, session, platform message id, thread, hash, created/expired; unique idempotency key.

**Tests**

- Unit: target resolution, state transitions, drain cancellation, missing home mapping, unknown profile, redaction.
- PostgreSQL: concurrent lifecycle request, pairing ownership, receipt idempotency, receipt expiry.
- Controller: all former 501 routes return real action result or a precise capability-disabled 4xx/409; no static `gateway_running=false` when service has state.
- E2E: configure test Telegram gateway -> start -> health -> send to home/topic -> response receipt -> react latest -> drain -> reject new dispatch -> stop. Test webhook signature and rejected callback.

**Done when:** direct `platform:chat_id`, bare platform home and topic/thread targets are all explicitly resolved or rejected based on persisted configuration; dashboard reflects real gateway state.

---

### WP-3. MCP persisted configuration, lazy schema cache and OAuth dashboard

**Проблема:** `McpLifecycleManager` has robust in-memory connection/timeout/circuit-breaker behavior, but `McpDashboardController` config CRUD/toggle/catalog/OAuth is 501. Existing `McpOAuthManager` refreshes tokens but no authorization flow exists. Discovery cache is not durable or single-flight across restart.

**Решение**

1. Add `McpConfigStore` port backed by profile-scoped persisted config. CRUD, enable flag, trust, transport, endpoint/command, args, allowed env key references, include/exclude, timeout and OAuth metadata must pass validation before replacing runtime config.
2. Add `McpSchemaCacheStore`: server config revision, tools/resources/prompts schemas, content hash, fetched/expiry/last success/last error. Cache stores schemas only, never secrets or raw credentials.
3. Refactor lifecycle to lazy discovery: cold startup does not synchronously discover all servers; first need triggers one single-flight refresh; callers receive last known-good schema or a bounded initializing/failure envelope.
4. Invalidate on config revision, disable/delete, explicit reload, successful reconnect and TTL expiry. Failed refresh keeps prior schema marked stale rather than deleting usable tools.
5. Implement dashboard MCP routes over config store: add/replace/delete/toggle/test/list/catalog. Catalog is data only until a catalog installer has real reviewed source/signature/dependency policy.
6. Implement OAuth Authorization Code + PKCE flow only where server metadata supports it: persistent short-lived flow state, state validation, redirect allowlist, callback code exchange, encrypted token storage, cancellation and refresh. Do not expose token values. Existing refresh logic must switch from direct `AgentProperties` lookup to config store.
7. Add lifecycle cleanup: cancel outstanding RPC on disable/reload, close stdio/HTTP resources, POSIX parent-death watchdog for spawned stdio process, and no executor leak on repeated reload.
8. Sampling/elicitation are separate subfeatures: first map exact MCP SDK support and Hermes contract; then implement typed request/approval/transcript handling. They must not be reported as part of cache/CRUD completion.

**Data/migrations**

- `mcp_server_configs` profile-scoped with unique `(profile, name)`, config revision and non-secret config fields.
- `mcp_schema_cache` keyed by server config id/revision; JSON schema fields, hash, fetched/expires/error metadata.
- Extend `mcp_oauth_tokens` with profile/config foreign key and encryption key version; migrate legacy name-only records safely.
- `mcp_oauth_flows` with state hash, PKCE verifier encrypted or short-lived protected storage, expiry, redirect and status.

**Tests**

- Unit: validation, config redaction, TTL/invalidation, cache stale fallback, single-flight, trust gate after reload, OAuth state/PKCE/error sanitization, teardown ordering.
- PostgreSQL: same server name isolated by profile, concurrent config revision, token/flow expiry, migration from legacy OAuth row.
- Local MCP fixture: cold start, parallel first requests, tool/resource/prompt schema cache after backend restart, schema change after reload, disable while call is in flight, stdio child exits after parent teardown.
- HTTP E2E: dashboard CRUD -> test -> tool invoke -> restart -> cached list -> modify config -> old tool unavailable -> delete; OAuth mock authorization/token server validates state and token refresh.

**Done when:** every current MCP dashboard 501 has a real supported implementation or is deliberately removed from exposed API because its backing feature is not in product scope; docs/34 gap 5 is split into closed cache/config/OAuth work and separately tracked sampling/elicitation work.

---

### WP-4. Profile runtime isolation, configuration and dashboard operations

**Проблема:** profile file metadata and named config writes exist, but default config writes, env storage, profile auto-description, gateway health, skill/tool hot reload, full prune/export cleanup, provider/model catalog and worker lifecycle are incomplete. Many `DashboardSystemController` actions are static 501 endpoints.

**Решение**

1. Define `ProfileRuntimeRegistry`: profile config revision, active tool registry revision, skill manager revision, gateway binding state, worker state. It replaces static `gateway_running=false` and enables controlled hot reload.
2. Implement serialized config writer for default and named profiles using the same safe atomic writer semantics as `ProfileService`; validate against typed config schema and preserve comments/unknown keys only where parser supports it.
3. Implement profile `.env` store with allowlisted key names, encrypted/OS-secret-backed values, masked reads, no reveal endpoint by default. If secure store is unavailable, do not expose writes.
4. Add reload pipeline: config change -> validate -> build candidate runtime -> atomically swap only on success -> publish revision event -> close old managed resources. Skills/toolsets/MCP/gateway react through this pipeline, never mutate global `AgentProperties` directly.
5. Finish session prune/export fidelity only for fields actually persisted. For missing filters (`cwd_prefix`, billing/chat/branch/token/cost/tool bounds), first add modeled fields and indexes, then expose filter. Do not silently accept and ignore filter.
6. Implement profile auto-description through auxiliary model only with explicit opt-in, bounded prompt, no source/secret disclosure and persisted user-editable result.
7. Implement model catalog/selection boundaries: provider metadata and pricing source, per-profile availability, explicit expensive-model confirmation, auxiliary/MoA behavior only after real runtime support. Avoid returning catalog entries for providers that cannot execute.
8. Implement dashboard operations using a safe `DashboardActionService`: doctor, prompt-size, dump, security audit, backup/download/import, debug bundle, config migrate, checkpoint prune. Every action is whitelisted, typed, cancellable, audited and reports real stdout/status through the existing action status API. No arbitrary shell command input.
9. Implement profile worker lifecycle on top of gateway/runtime registry; start/stop/restart/drain permissions and action audit are shared with WP-2.

**Data/migrations**

- `profile_runtime_state`, `profile_config_revisions`, and optional action ledger with action id/state/output path/actor/profile/timestamps.
- Persist new session fields only after dataflow mapping: chat/source metadata, branch/worktree, costs/tokens and tool-call counters. Add indexes based on concrete prune query predicates.
- Backup/archive records contain path/hash/expiry, never raw secret content.

**Tests**

- Unit: atomic writer, schema validation, profile mismatch, safe reload rollback, action allowlist, archive/path traversal/redaction.
- PostgreSQL: revision races, worker state ownership, prune filters and deletion/orphan behavior, action state recovery.
- Integration: change profile toolset/skills/MCP config -> only that profile's runtime changes; failed config preserves old functioning runtime.
- HTTP E2E: default and named profile config mutation, masked env, reload, profile status/health, dry-run and real prune, export/import cleanup, action status and cancellation.

**Done when:** no dashboard operation reports success with static data; every exposed operation is real, capability-gated, or removed from route surface. docs/34 gap 2 remaining depth is closed by persisted data, not approximated UI payloads.

---

### WP-5. Skills hub, plugin model and toolset catalog

**Проблема:** profile-local skill edit exists, but hub background install/update/uninstall is 501. `PluginDashboardController` is an explicit no-op namespace. Toolset catalog advertises unavailable tools and environment/post-setup writes are 501.

**Решение**

1. Build profile-aware `SkillHubService`: catalog search, signed/allowlisted source resolution, staged install/update/uninstall, linter/security scan, atomic swap, audit record and runtime reload via WP-4. No shelling out to an external Hermes CLI.
2. Define Java plugin scope before implementation. A plugin must declare manifest, version, capabilities, routes, tool definitions, config schema, permissions and lifecycle. It cannot load arbitrary JVM bytecode into the main process by default.
3. Implement plugin registry and visibility persistence. Use isolated process/classloader policy only after security design/ADR; plugin-owned dashboard routes are registered through a typed router contract, not wildcard proxy.
4. Separate built-in provider configuration from plugin configuration. `DashboardSystemController` plugin provider route is backed by registry only after plugin model exists.
5. Replace static `ToolsetsController` catalog with capability registry. Unsupported platforms/tools are omitted or return `available=false` with reason; they must not appear enabled merely because a label exists.
6. Implement toolset env/post-setup only through the secure profile config/env store of WP-4. Post-setup is a reviewed internal action, no raw command from API.
7. Implement only in-scope Java tools. Video generation, Discord, Spotify, Home Assistant, Yuanbao and cross-platform computer use remain explicitly out of scope unless separately accepted; remove misleading enabled/catalog entries rather than create fake stubs.

**Tests**

- Unit: manifest validation, dependency/capability conflict, signature/source policy, failed install rollback, disabled plugin route rejection, toolset availability calculation.
- Integration: skill install/update/uninstall triggers one runtime revision; plugin route ownership/ACL; malicious archive/path traversal rejected.
- HTTP/CLI E2E: hub search -> install fixture -> `skills_list`/`skill_view` -> update -> uninstall; plugin fixture lifecycle if plugin runtime is accepted; unavailable toolset not offered to model.

**Done when:** every public hub/plugin/toolset route has a backed state transition and test. Explicitly out-of-scope capabilities are not represented as implemented functionality.

---

### WP-6. OpenAI Responses/Runs approvals, cancellation and durable run state

**Проблема:** current Responses/Runs controllers already expose create/get/events/approval/steer/stop and replay external session ids, but docs/34 still identifies richer approval scopes, cancellation/interruption parity and full runtime protocol as incomplete. Completion must be verified against actual run state transitions, not endpoint presence.

**Решение**

1. Write contract-first state machine: `queued`, `in_progress`, `requires_action`, `completed`, `failed`, `cancelled`, `expired`; allowed transitions and terminal idempotency.
2. Map one run to session/user/profile/response id and persisted operation handle. All reads/control actions enforce ownership and API-key role rules.
3. Integrate tool approval queue with run state: request is durable, scoped to tool/run/session, approve/deny/expire/cancel is race-safe and emits ordered SSE events.
4. Implement cancellation propagation: stream emitter, interrupt token, queued/active tool batch, pending approval and background task. Repeated cancel produces the same terminal resource without duplicate cleanup.
5. Make run event replay durable with monotonic sequence/cursor and reconnect after restart. Existing in-memory event behavior is not sufficient proof.
6. Expand Responses input/output only after a test matrix for supported item types. Unsupported OpenAI item types return accurate typed error; never parse and discard content.

**Data/migrations**

- Extend existing response store/run persistence with state, sequence, cancellation reason/time, user/profile, approval linkage and retention expiry.
- Add unique external id mapping and indexes for `(user_id, external_run_id)`, `(state, updated_at)` and SSE replay.

**Tests**

- Unit: transition table, invalid transition, approval/cancel race, retry/idempotency, item parsing and OpenAI error envelope.
- PostgreSQL: restart from each non-terminal state, ownership isolation, concurrent approve/deny/cancel, sequence ordering.
- HTTP E2E: create -> requires_action -> approve -> completed; deny; cancel before model/tool/approval; reconnect event cursor; external session id; auth-negative and repeated delete/cancel.

**Done when:** docs/34 gap 8 has a documented supported protocol matrix with all state transitions persisted and replayable.

---

### WP-7. Execute-code runtime: session kernel and remote file RPC

**Проблема:** `ExecuteCodeTool` supports only local per-call Python and accurately rejects `session_kernel`/`remote_rpc`. A real implementation requires ownership, workspace safety, process lifecycle and cancellation.

**Решение**

1. Extract `CodeExecutionBackend` port: existing `LocalPerCall`, new `SessionKernel`, new `RemoteRpc`. Preserve local wire response exactly.
2. Session kernel manager: one kernel per session/profile/user; serialized evaluation; explicit reset; idle TTL; max kernels; timeout/interrupt kills process; stdout/stderr/output caps; no inherited secrets beyond allowlist.
3. Persist kernel metadata/lease only, never a dead Java process handle. After server restart return `kernel_lost` and let client recreate deliberately.
4. Define remote file-RPC protocol before transport: request id, workspace token, allowlisted path operations, bounded file size/chunks, execution result, cancellation and error taxonomy. Remote worker authenticates each request and cannot read host paths outside workspace.
5. Add cleanup on session delete, profile delete, expiry and shutdown.

**Tests**

- Unit: mode selection, workspace path validation, command/Python args serialization, output cap, reset and normalized errors.
- Integration: state `x=2` then `x+2`, cross-session/profile isolation, concurrent same-session serialization, timeout/kill, idle eviction, restart `kernel_lost`.
- RPC fixture E2E: allowed read/write, traversal denial, chunk boundary, cancel, worker unavailable, idempotent retry.

**Done when:** both previously rejected modes perform the advertised behavior with no fallback to local execution. docs/34 gap 7 closes.

---

### WP-8. Browser routing: extension, cloud and hybrid providers

**Проблема:** local CDP is implemented and non-local provider values fail closed. No real provider router exists, so cloud/hybrid/browser-use/Camofox behavior is absent.

**Решение**

1. Introduce `BrowserBackend` port and capability model: navigate, DOM snapshot, click/type, screenshot, vision, raw CDP, session lifecycle.
2. Build routing service from explicit `browser.cloud-provider` selection. `browser.backend` stays a runtime hint and cannot silently choose cloud.
3. Centralize URL policy before provider selection and after navigation/redirect; all providers use the same SSRF/private-network and website policy boundary.
4. Implement one real non-local backend first after selecting available infrastructure: extension-control/hybrid is preferred because it preserves current user browser ownership. Browser-use/Camofox/cloud providers become separate implementations behind the port.
5. Add provider session/lease cleanup, health/capability diagnostics and fail-closed unsupported operation response.

**Tests**

- Unit: selection matrix, capabilities, no fallback, URL/eval safety preflight/post-redirect, error normalization.
- Fixture integration: fake backend captures commands, validates cleanup and rejects private URL for every provider branch.
- Live tagged test only with authorized browser/cloud credentials: navigation, screenshot `MEDIA`, reconnect, private URL denial. Never run by default CI.

**Done when:** docs/34 gap 4 has one real cloud/hybrid route and each advertised provider capability is exercised or marked unavailable.

---

### WP-9. Dashboard WebSocket console, PTY and pub sidecar

**Проблема:** Event websocket and MP3 TTS relay exist, but `/api/console`, `/api/pty`, `/api/pub`, PTY cursor/reconnect and command confirmation/cancellation protocol do not exist.

**Решение**

1. Write ADR and protocol fixtures first: auth, profile/session binding, host/origin guard reuse, frame schema/version, cursor/replay, close/error codes, backpressure, max frame/output, cancellation and retention.
2. Implement `ConsoleTaskService`: durable task id, approved command request, output sequence/cursor, cancellation, terminal status and retention. It executes through existing command guard/terminal safety boundary, not raw websocket input.
3. Implement PTY service separately: explicit workspace/cwd, user/profile/session ownership, start/input/resize/read/close, binary-safe bounded output, cursor reconnect, process group cleanup and idle timeout. PTY capability remains unavailable where host policy forbids it.
4. Implement pub channel registry: named channel ACL, profile/session scope, retained/replay policy, bridge from `EventService` without unbounded memory queue.
5. Extend existing dashboard WebSocket auth/Origin guard to all new endpoints; add token/session authorization checks after handshake too.

**Data/migrations**

- `console_tasks`, `console_task_output`, optional `pty_sessions`, `pub_channels`/`pub_events` only if in-memory event core cannot guarantee restart semantics.
- Index output by `(task_id, sequence)` and enforce ownership on all task/session queries.

**Tests**

- Unit: command validation/approval, cursor calculation, ACL, backpressure/drop policy, terminal state transitions.
- PostgreSQL: reconnect after restart, output sequence no duplicate/gap, cross-profile denial, cancel race, retention cleanup.
- Websocket integration: unauthenticated/bad-origin rejected; connect -> start -> reconnect cursor -> receive tail once -> cancel; PTY input/resize/reconnect/close; pub ACL/replay.

**Done when:** docs/34 gap 1 is real protocol behavior, not a route-shaped stub.

---

### WP-10. Provider ecosystem: TTS, transcription, image generation and vision

**Статус 2026-09-15:** capability matrix (WP-10 шаги 1–3) реализована (`MediaProvider` contract + `MediaProviderRegistry` + `ExistingProvidersFacade`). Реальные TTS/STT/image-провайдеры — **not planned** по решению владельца (2026-09-15): голосовые и image-интеграции не нужны флоту, реальных required-интеграций нет. Raw PCM streaming (шаг 5) отменён вместе с ними; MP3-relay остаётся поддерживаемым контрактом. Пункт закрыт; переоткрытие — только по явному запросу с именем конкретного провайдера.

**Проблема:** the current OpenAI-oriented providers and MP3 relay are narrower than Hermes. There is no common credential resolver, provider capability matrix or raw PCM streaming contract.

**Решение**

1. Define common provider contracts and error taxonomy for TTS, STT, image generation/edit/upscale and vision. Include sync/streaming, input/output MIME, size limits, cache ownership, cancellation and cost attribution.
2. Reuse WP-4 secure credential/config store. Provider selection must prove a provider is configured and supports requested operation before model tool exposure.
3. Build capability registry used by toolset/dashboard/model prompt; unavailable providers are omitted/disabled rather than advertised as working.
4. Add providers one at a time, prioritizing real required integrations. Each has mock HTTP contract tests before an optional `@Tag("live")` test.
5. Implement raw PCM only after WP-9 websocket framing/backpressure is finished; current MP3 relay remains a separate supported contract until then.
6. Implement image edit/upscale/video only when provider and product scope are explicitly accepted; do not fabricate generic success.

**Tests**

- Unit: provider selection, missing credential, incompatible media, retry/timeout/cancel, cache cleanup, redacted errors.
- Mock-server integration per provider: request body/auth headers, response parsing, multipart errors, media content type.
- Live tests with env-only credentials and explicit opt-in: one minimal operation per provider, cleanup cached artifacts.

**Done when:** docs/34 gap 6 has a published provider capability matrix and each enabled cell has deterministic tests.

---

### WP-11. CLI and Telegram native attachment/relay parity

**Проблема:** media delivery exists in Telegram bot, but `MessageEvent.Attachment` is not a complete cross-surface contract; CLI file/image drop is incomplete; media re-delivery/dedupe, full audio/video/document inbound flow, active session recovery and native relay need an end-to-end contract. `MediaDeliveryService` itself records partial streamed tag handling as a known limitation.

**Решение**

1. Introduce `AttachmentArtifact` domain contract: id/hash, origin, MIME, size, sanitized name, local cache reference, lifetime, user/profile/session/message linkage and disposition. Convert Telegram inbound and CLI drops into this contract before chat request.
2. Add attachment persistence/repository only for metadata and safe cache reference. Blob storage uses controlled cache roots and TTL cleanup; no arbitrary file paths in session JSON.
3. CLI: implement explicit attachment command/drop lifecycle, validate path/symlink/size/MIME, attach to active/new session, render delivery receipts and clean temporary materialization.
4. Telegram: normalize photo/document/voice/audio/video/sticker/location content; preserve message/thread ids; route transcription where enabled; protect media group dedupe; support active-session recovery after restart.
5. Outbound: derive media MIME disposition (photo/document/video/audio/voice) from artifact; media send receipt participates in WP-1 idempotency; retry never sends same artifact twice after ambiguous success.
6. Replace per-response regex-only delivery where necessary with artifact references while retaining `MEDIA:<path>` compatibility during migration. Final extraction runs on completed content so streaming chunks cannot leak raw tags.

**Data/migrations**

- `attachment_artifacts` with hash, owner/profile/session/message, safe path, MIME/size, state and expiry; unique `(owner, hash, disposition)` where appropriate.
- Link outbound receipts to artifact id and platform message id.

**Tests**

- Unit: MIME/disposition, tag extraction across chunk boundaries, JSON/code masking, path/symlink/size rejection, duplicate hash, cleanup.
- PostgreSQL: ownership/isolation, expiry cleanup, ambiguous send/retry idempotency, session recovery.
- CLI E2E: attach image/document -> backend receives metadata -> invalid path/oversize rejected -> cleanup.
- Telegram fixture E2E: inbound photo/voice/audio/video/document and outbound matching Telegram method; media group; topic; restart/re-delivery no duplicate.

**Done when:** docs/34 gap 9 has supported CLI and Telegram attachment/media behavior backed by persisted receipts and E2E fixtures.

**Status 2026-09-15: CLOSED (tail).** Artifact contract (V62) + REST surface + ChatRequest.attachments + first-turn `[Attachments]` injection are real; Telegram inbound registers artifacts with legacy fallback, outbound delivery is receipt-guarded (V64, first platform message id wins, no re-send after ambiguous success), M3 partial-tag leak fixed; CLI `/attach`/`/attachments`/`/detach` with full validation. Evidence: AttachmentFlowE2ETest (PG, 5/5), AttachmentArtifactRepositoryTest incl. V64 CHECK (6/6), AttachmentControllerTest (10), CliStateApplierAttachmentsTest (8), AttachmentApiClientTest (9), BotMessageProcessorAttachmentsTest (7), MediaDeliveryPartialTagTest (6), BotMessageProcessorArtifactPathTest (6), AttachCommandsTest (12). Remaining (tracked in docs/34): bot active-session recovery after restart, DM topics depth.

---

### WP-12. Dashboard and filesystem capability closure

**Проблема:** several remaining routes in `DashboardSystemController`, `FilesystemDashboardController`, memory/toolset/model endpoints expose 501 or static responses. They need a decision rather than indefinite compatibility shells.

**Work items**

1. Dashboard doctor/prompt-size/dump/security-audit/config-migrate: implement through `DashboardActionService` in WP-4; return action id and stream status, never synchronous shell output.
2. Backup/import/download/debug-share/checkpoint prune: implement archive allowlist, encryption/redaction, upload size/type checks, output expiry and audit. Reuse `ProfileService` archive protections instead of separate tar handling.
3. Git mutation endpoints: only implement mutation after command approval/ownership/worktree safety and explicit product scope; otherwise remove routes from advertised dashboard capabilities rather than keep a misleading action button.
4. Memory provider config write: map only provider-supported schema fields to secure config store; validate before runtime reload; unsupported provider returns capability detail.
5. Model settings: close real default-profile auxiliary assignment and provider metadata only for executable providers. MoA remains a separate runtime implementation, not an API-only option.
6. Toolset web-extract backend selection, env/post-setup and custom endpoint/OAuth flows must use common configuration/credential/action services. Do not write ad hoc YAML or execute arbitrary setup scripts.

**Tests**

- Controller contract tests for former 501 path, validation, authorization, action status and failed reload rollback.
- Archive/path traversal/secret scanning tests for backup/import/debug bundles.
- Testcontainers state persistence and concurrent action requests.
- HTTP E2E action lifecycle, action cancellation, backup import/export cleanup, model/toolset capability changes.

**Done when:** every retained dashboard action is backed by an audited service. Routes outside approved product scope are absent from catalog/UI and return a documented capability response only if direct compatibility requires it.

## 5. Required test matrix for every work package

| Layer | Required evidence |
|---|---|
| Unit | happy path, invalid input, error path, idempotent repeat, ownership/fail-closed branch |
| Concurrency | latches/barriers, not sleeps; duplicate consumer/action and cancellation races |
| PostgreSQL integration | migration, constraints/indexes, transaction rollback, restart/recovery when state is durable |
| Spring/controller | request validation, auth/RBAC, profile path/query/body mismatch, response schema and error shape |
| HTTP E2E | full lifecycle, persisted side effect, negative case, repeated call, cleanup |
| CLI E2E | user-visible command/drop/attachment path, malformed input, session lifecycle, cleanup |
| Telegram fixture E2E | exact Bot API method/payload, message/thread ids, media/reaction delivery, retry dedupe |
| Live | tagged and opt-in only: actual configured LLM/provider/browser/Telegram contract; no dev/prod access without permission |

Every YAML scenario added for a new stateful endpoint must assert content plus side effect. HTTP 200 alone is not evidence. Temporary database rows, local artifacts, archive files, child processes and fixture resources are removed and absence is asserted.

## 6. Verification gates after each wave

1. `./gradlew test jacocoTestReport` and parse JaCoCo XML; overall LINE stays >= 80%, touched production package LINE >= 75% unless documented external-I/O exception.
2. Focused `./gradlew slowTest --tests ...` with PostgreSQL Testcontainers for migrations/state/ownership/recovery.
3. `./gradlew bootJar` for changed modules.
4. Local jar/noop smoke against changed public routes. Entity and migration changes require persisted message/session smoke, not only controller mocks.
5. Changed declarative HTTP scenarios and CLI E2E run against an actual local backend. Bot routes use isolated fixture bot/API.
6. Check migrations in both directories for a single monotonically unique version; run clean-schema migration test and upgrade test from the prior schema snapshot when migration changes data.
7. `git diff --check`, secret-pattern scan excluding test fixtures/build output, and scan for dead references to removed stubs/routes.
8. Commit only after all applicable gates are green. No deploy is part of this plan; deployment requires separate explicit instruction.

## 7. Execution order

1. WP-0 foundation and coverage guardrails.
2. WP-1 durable delivery ledger, then WP-2 gateway identity/lifecycle. These remove duplicate delivery mechanisms and establish a real transport boundary.
3. WP-4 profile runtime/config store. It is required before configuration writes in MCP, skills, providers and dashboard.
4. WP-3 MCP cache/config/OAuth.
5. WP-5 skills/plugin/toolset capability catalog.
6. WP-6 OpenAI run lifecycle and WP-7 execute-code runtime. They share durable cancellation/ownership principles but must not edit the same run-state service concurrently.
7. WP-8 browser router.
8. WP-9 console/PTY/pub protocol.
9. WP-10 providers.
10. WP-11 CLI/Telegram attachments and relay, using the completed delivery/gateway/config layers.
11. WP-12 remaining dashboard/operation routes and final capability cleanup.

Each work package is a separate reviewable release series; do not mix schema/data migration, dashboard cleanup and unrelated refactors in the same commit.

## 8. Completion definition

The plan is complete only when:

- no retained public route returns a fabricated success or an untracked static placeholder;
- every retained `501` is either implemented or removed from advertised capability/UI because it is explicitly out of product scope;
- docs/34 large gaps are either closed with code+tests or split into exact, still-open subcontracts with no false "done" marking;
- all supported platform/provider/toolset capabilities are derived from runtime capability registry;
- unit, Postgres integration, HTTP/CLI/bot E2E and applicable live test evidence are recorded for each wave;
- source documentation, `AGENTS.md`, E2E coverage map and migration tests reflect the actual final code.

## 9. Execution baseline and current Hermes delta (2026-09-11)

### Fixed reference points

| Item | Value |
|---|---|
| Java baseline | `main` at `66be1d816d557bd9fb23de2c0f51cca4e26af56e` (`0.1.237`) |
| Java working tree | Dirty before this execution: documentation and release-gate files are uncommitted; do not discard or overwrite unrelated work. |
| Hermes reference | Read-only worktree `/opt/dev/hermes-workspace/hermes-agent-upstream-20260911` at `05d705dd695d1084388529124dc2ffe5ce919e89`. |
| Old local Hermes checkout | `6ce7ab8b...`, dirty and far behind upstream; it is not a source for new parity decisions. |
| Verified Java baseline | `./gradlew :backend:test :cli:test :telegram-bot:test --no-daemon --console=plain` passed on 2026-09-11. |

The reference worktree exists only to read source and tests. It must never be used to update the dirty local Hermes checkout or to access development/production services.

### Mandatory WP-0 deliverables before implementation waves

1. Create a versioned parity matrix from the reference worktree. Every row contains: Hermes commit/path/symbol/test, Java owner path, user-visible contract, status (`implemented`, `partial`, `absent`, `out_of_scope`), evidence and target work package.
2. Scan every public Java controller route and every model-visible tool description. A `501`, a static success payload, or an advertised unavailable tool is a matrix row; an honest unsupported response is not marked complete.
3. Finish and execute the local release-gate runner. Its JSON report must record every gate as `PASS`, `FAIL`, or `NOT_RUN` with the reason. It must not turn missing Docker, live credentials or a stopped local backend into a green result.
4. Establish the current local baseline: module tests, JaCoCo reports, release-runner unit tests, endpoint inventory, boot JARs, and one controlled PostgreSQL/Testcontainers attempt. If Docker is unavailable, record the exact blocked command and retain the mandatory integration test for the first host with Docker.
5. Add regression tests for release scripts before expanding them. Endpoint inventory remains explicitly static; it cannot count as behavioral E2E coverage.

### Current reference-derived work breakdown

#### Wave A - Delivery and gateway foundation (WP-1, then WP-2)

**Reference contracts:** `gateway/delivery_ledger.py`, `cron/delivery_queue.py`, `gateway/run.py`, `gateway/stream_dispatch.py`, platform adapter delivery and recovery tests.

1. Design an ADR comparing a single `delivery_work_items` table with two ledgers sharing one state machine. Choose one before migration work.
2. Persist source type/id, profile, user, parent session, normalized target, payload hash/reference, idempotency key, state, attempt/claim fields, delivery receipt and terminal error metadata.
3. Move cron and delegated-task completion delivery onto one consumer contract: atomic claim, send, ack, release/drop, stale-claim recovery and terminal unknown-send handling. Delete or retire duplicate consumer paths after their unique behavior is migrated.
4. Persist structured destinations: `origin`, `local`, `platform:chat_id`, `platform:chat_id:thread_id`. Derive `origin` from the stored source session, never a current default chat.
5. Add one parent-session reinjection marker per completed delegated run; delivery retries/restarts cannot create a second marker.
6. Build gateway lifecycle and target resolution only after the delivery boundary exists: start/stop/restart/drain state machine, registered-home target, thread/topic routing, outbound receipts and latest-message reaction lookup.

**Proof:** Postgres claim race, stale takeover, crash after claim, retry/attempt cap, receipt idempotency, profile/owner isolation; fixture Telegram sends assert target, topic, chunks, API method and returned message id.

#### Wave B - Profile runtime and safe configuration (WP-4)

**Reference contracts:** Hermes profile scope, profile-aware gateway/messaging configuration, cache invalidation and runtime reload flows.

1. Introduce a profile runtime registry with immutable revisioned snapshots for config, tools, skills, MCP and gateways.
2. Implement a serialized typed config writer for default and named profiles. Candidate config must validate and construct successfully before the old runtime is replaced.
3. Add a secure profile env/credential boundary: allowlisted names, encrypted or OS-backed values, masked read and no reveal-by-default endpoint. If secure storage is unavailable, write endpoints remain absent.
4. Build atomic reload: validate -> create candidate -> swap -> publish revision -> close old managed resources. A failed candidate preserves the active runtime.
5. Finish only session fields Java truly persists. Add `cwd`, `git_repo_root`, source/chat metadata, token/cost/tool counters before exposing filters that require them.

**Proof:** configuration revision race, failed-reload rollback, no cross-profile credential/config leak, resource cleanup, persisted project/repo/worktree grouping and real status rather than static dashboard fields.

#### Wave C - MCP, skills, plugins and capability catalog (WP-3, then WP-5)

**Reference contracts:** `tools/mcp_schema_cache.py`, MCP lifecycle/config routes, skills hub and provider registry behavior.

1. Add a profile-scoped MCP config store with validation, config revision, redaction and runtime replacement.
2. Add a durable schema cache keyed by config fingerprint/revision, TTL and content hash. First caller performs single-flight discovery; parallel callers receive a bounded initializing response or last-known-good stale schema.
3. Invalidate cache on replace/disable/delete/reload/reconnect; cancellation and transport teardown must close outstanding resources.
4. Implement OAuth only after the config/credential store: Authorization Code + PKCE, short-lived state, redirect allowlist, encrypted tokens and mock-server tests. Sampling and elicitation remain separate contracts.
5. Make the capability registry authoritative for dashboard, model prompt and API catalog. Unsupported toolsets are removed or explicitly unavailable, never advertised as operational.
6. Define plugin isolation and manifest/permission policy in an ADR before enabling install/update routes. Do not load arbitrary JVM code into the backend.

**Proof:** cold cache + concurrent first use, restart cache reuse, config invalidation, profile isolation, OAuth state replay/rejection, failed plugin/skill installation rollback and capability visibility reflected in actual model tool registration.

#### Wave D - Durable runtime control and code execution (WP-6, then WP-7)

**Reference contracts:** Hermes Runs/Responses state/event contracts, `tools/code_kernel.py`, `tools/code_execution_rpc.py`.

1. Persist run state machine and event sequence: queued, in-progress, requires-action, completed, failed, cancelled and expired. Every mutation enforces user/profile/session ownership and terminal idempotency.
2. Connect approval producer, queue, decision, expiration and cancellation to the persisted run state. Cancellation must interrupt model stream, tool batches, waiting approval and background work.
3. Make SSE replay monotonic and restart-safe; unsupported OpenAI item types fail as typed errors rather than being silently discarded.
4. Implement session kernel before remote RPC: one serialized kernel per owner/profile/session, reset, output/time limits, idle eviction, process-group teardown and `kernel_lost` after backend restart.
5. Implement remote RPC as a separately authenticated workspace protocol with bounded chunking, path allowlist, cancellation and idempotency. It must never fall back to local execution.

**Proof:** approve/deny/cancel races, cursor reconnect after restart, `x=2` then `x+2`, cross-profile kernel isolation, timeout kill, remote traversal denial and worker-unavailable behavior.

#### Wave E - Browser, console/PTY and provider/media execution (WP-8 through WP-11)

**Reference contracts:** `tools/browser_extension_router.py`, `hermes_cli/pty_session.py`, provider registries, attachment/media delivery contracts.

1. Define browser backend capability interface and preserve URL policy before dispatch and after redirect. Implement one authorized hybrid/extension backend first; no silent local-CDP fallback for a requested non-local provider.
2. Write console/PTY/pub ADR and frame fixtures before code. Console tasks and PTY sessions need independent state machines, ownership, confirmation, sequence/cursor replay, backpressure, cancellation and cleanup. Raw websocket-to-shell is prohibited.
3. Create `AttachmentArtifact` metadata + safe cache lifecycle before expanding CLI/Telegram attachment support. Outbound artifacts use the Wave A receipt/idempotency path.
4. Add provider capability/credential contracts before individual TTS/STT/image/vision providers. Each enabled cell has mock HTTP contract tests and opt-in live tests only.

**Proof:** browser provider selection/redirect denial, PTY reconnect/cancel/origin rejection, attachment MIME/path/dedupe/recovery, exact Telegram API payloads, provider missing-credential/error/cancel and artifact cleanup.

#### Wave F - Public-surface closure and release (WP-12)

1. Enumerate all current `501`, static payload and unsupported model-tool/catalog entries by controller/tool.
2. For each item, choose exactly one outcome: real backed behavior, removed from catalog/UI, or a documented capability-disabled contract required for compatibility.
3. Do not classify an item as out of scope merely to close the audit: reference source must be checked first and the user-visible API/UI must no longer suggest it works.
4. Re-run the full evidence matrix after all waves; update `docs/34`, this document, `AGENTS.md`, route inventory and migration tests from actual code.

### Wave gates and commit discipline

Each wave starts with a testable design note/ADR when it introduces persistence, protocol, credentials or runtime lifecycle. Each stateful feature includes unit, controller, PostgreSQL/Testcontainers, concurrency, restart/recovery and end-to-end fixture tests before it is marked complete. Migrations are checked across both migration directories immediately before creation. Each wave is reviewable and committed separately; unrelated cleanup does not ride along.

No deployment, service restart, server login or live credential test is part of this execution plan. Those require a separate explicit instruction.

## 10. Initial parity matrix - reference `05d705dd` (2026-09-11)

This is the initial, evidence-backed matrix for the contracts that remain partial or absent. It is deliberately not a completed-feature list. Additional rows are added by WP-0 route/tool inventory before a related work package starts.

| Hermes reference | User-visible contract | Java owner/evidence | Status | Target |
|---|---|---|---|---|
| `gateway/delivery_ledger.py`: `record_obligation`, `mark_delivered`, `sweep_recoverable`; `tests/gateway/test_delivery_ledger*.py` | A final response is durable before send, recovery is fenced, and ambiguous send outcome is never silently replayed as an ordinary retry. | `CronDeliveryPoller`, `DeliveryRouter`, `DelegatedTaskRunService`; separate consumers and no shared outbound receipt ledger. | partial | WP-1 |
| `cron/delivery_queue.py`: `enqueue`, `claim_next`, `recover_abandoned`; `tests/cron/test_delivery_queue*.py` | Cron delivery is atomically claimed and terminalized with an explicit unknown-send outcome after abandoned delivery. | `CronJobService` has cron state; delivery remains Telegram-specific `CronDeliveryPoller`. | partial | WP-1 |
| `hermes_cli/web_routers/messaging.py`: onboarding, platform config and profile-scoped platform state | Messaging config/onboarding/lifecycle uses actual profile state, never fabricated dashboard cards. | `MessagingDashboardController.java` returns 501 for onboarding, config write, pairing and subscriptions; `SendMessageTool.java` rejects home/thread targets. | absent | WP-2 |
| Hermes profile runtime/config path and profile-aware gateway readers | Requested profile owns its config, secrets, managed resources and reload outcome; a failed change retains the previous runtime. | `ProfileService.java` persists a bounded file profile surface; `DashboardSystemController.java` keeps default config/env writes and runtime lifecycle as 501. | partial | WP-4 |
| `tools/mcp_schema_cache.py`: fingerprint/cache read/write; `tests/tools/test_mcp_schema_cache*.py` and `test_mcp_lazy_start.py` | Revisioned persistent lazy schema cache, bounded first discovery and last-known-good fallback. | `McpLifecycleManager.java` covers live lifecycle/circuit-breaker paths; `McpDashboardController.java` config/OAuth/cache persistence routes are 501. | partial | WP-3 |
| Hermes skill/plugin registries and provider registries | Only configured, executable capabilities are discoverable; installs/config changes are staged and reload safely. | `SkillsDashboardController.java` hub actions and `PluginDashboardController.java` are 501; `ToolsetsController.java` advertises unavailable products. | partial | WP-5 |
| Hermes Responses/Runs lifecycle and approval/event replay paths | Approval, cancellation and event cursor state survive restart and enforce ownership. | `OpenAiRunsController.java` provides bounded endpoints; docs/34 identifies durable approval/cancellation/replay parity as open. | partial | WP-6 |
| `tools/code_kernel.py`: `KernelRegistry`, owner cleanup; `tools/code_execution_rpc.py`: local/remote RPC loops | Per-session stateful kernel and authenticated remote workspace RPC, with teardown and no local fallback. | `ExecuteCodeTool.java` explicitly supports only local per-call execution and returns unsupported errors for both modes. | absent | WP-7 |
| `tools/browser_extension_router.py`: `route_browser_tool`; browser control tests | An explicitly requested non-local controller is authoritative; policy applies on every provider path. | `BrowserService.java` has local CDP and fail-closed non-local selection but no actual hybrid/cloud provider router. | partial | WP-8 |
| `hermes_cli/pty_session.py`: `PtySession`, `PtySessionRegistry`; console/PTY websocket tests | Owned, reconnectable, bounded console and PTY sessions with cursor replay and process cleanup. | Implemented (ADR-015): `ConsoleTaskService` durable tasks V61 + `/api/console/ws` live streaming (`ConsoleWebSocketHandler`), `PtySessionService` script(1)-PTY с ownership/ring/reconnect/cleanup/idle-TTL/fail-closed capability, `PubChannelRegistry` ACL+replay+backpressure. Open: confirmation state, raw PCM. | done | WP-9 |
| Hermes TTS/image/vision provider registries | Capability is derived from configured provider and credentials; supported media operations have a deterministic dispatch/cleanup contract. | Java has bounded OpenAI-oriented providers and MP3 relay, but no common credential/capability matrix. | partial | WP-10 |
| Hermes attachment/media delivery and Telegram adapter tests | Inbound/outbound media uses durable artifact metadata, target/thread preservation and retry dedupe. | `MessageEvent.java` and `MediaDeliveryService.java` are partial; CLI drops, complete artifact persistence and delivery receipts are absent. | partial | WP-11 |
| Public dashboard and tool catalog routes | Retained route is real; unsupported functionality is absent from the advertised catalog or returns an explicit capability-disabled response. | 501s remain in dashboard, MCP, messaging, skills, toolsets and plugins; static inventory finds 456 mappings, 152 without even a static test reference. | partial | WP-12 |

### WP-0 evidence snapshot

- Full module test baseline: backend `6604`, Telegram bot `1720`, CLI `333`; all passed on 2026-09-11.
- JaCoCo baseline: backend LINE `80.26%`, Telegram bot LINE `82.17%`.
- Endpoint inventory: `456` mappings, `304` static test references and `152` mappings without a static reference. This is prioritization input only, not E2E coverage.
- `scripts/release_verify.py` has six regression tests and emits a partial report after every gate. A complete local execution exceeded the current tool wall-time while its PostgreSQL/boot-JAR gates were still running; the resulting report intentionally remains `RUNNING` rather than falsely claiming completion. Re-run it on an unrestricted local shell or split the gates while preserving the same JSON evidence format.
