# Java Agent identity deletion owner

Java Agent retains central Auth subjects in persisted content. It is an
independent deletion owner, even when no native `agent_users` account exists for
that subject. A catalog entry or a disabled deployment cannot prove zero usage.

The private owner uses Base schema version 1 with owner `java-agent`:

- `POST /api/internal/v1/user-deletions/{operation}/check`
- `POST /api/internal/v1/user-deletions/{operation}/freeze`
- `POST /api/internal/v1/user-deletions/{operation}/release`

The body contains `schema_version`, `operation_id`, `identity` (`issuer` and
canonical nonzero UUID `subject`), and `inventory_revision`. Receipts retain the
complete request. Exact freeze replay returns the original fence, revision and
count. A competing operation or changed payload conflicts. Release is durable
even if it arrives before freeze; a late freeze cannot resurrect cancellation.

## Deployment and authentication

The feature defaults off (`AGENT_IDENTITY_DELETION_ENABLED=false`). Enabling it
requires PostgreSQL, the actual configured central issuer, a bound legacy issuer
and a registered coordinator subject:

| Variable | Meaning |
| --- | --- |
| `AGENT_IDENTITY_DELETION_ENABLED` | Enables the owner and provenance registration |
| `AGENT_IDENTITY_DELETION_LEGACY_ISSUER` | Explicit immutable database issuer binding; must equal `AGENT_CENTRAL_AUTH_ISSUER` |
| `AGENT_IDENTITY_DELETION_COORDINATOR_SUBJECTS` | Comma separated approved service-account UUIDs |
| `AGENT_CENTRAL_AUTH_SESSION_URI` | Live Auth endpoint used to derive `/auth/tokens/introspect?include_lifetime=true` |

Only a freshly introspected service-account PAT with the exact
`java-agent:identity-lifecycle` scope and registered subject enters the boundary.
Human JWT/PAT, machine JWT, native gateway keys and ordinary machine scopes do
not authorize it. Private authorization does not provision a content account.
The original credential expiry and request deadline remain in force after all
network and database waits. Redirects are disabled. Responses use `no-store`;
request bodies are limited to 16 KiB, SQL waits/statements to eight seconds and
the complete HTTP request to nine seconds. An unavailable owner cannot report
zero or acknowledge a successful fence.

Every owner/provenance transaction binds a fresh expiry-only request UUID before
the reference locks. It samples PostgreSQL time, measures remaining original
monotonic lifetime after that response, and fixes the minimum of that database
cap and the original credential expiry. V72 checks this cap immediately and in
an unconditional deferred COMMIT trigger. The transient receipt is inserted and
deleted in the same transaction; persistent deletion fences never expire.
This also covers central registration using its original human proof and the
existing eight-second local operator window, without entering a fake human or
native context. Only the exact PostgreSQL clock SQLSTATE/message/constraint maps
to expired authority; unrelated permission failures remain unavailable.

## Persisted provenance and writer barrier

PostgreSQL migration V72 adds immutable issuer, storage and session alias
ledgers, a reference inventory, a fence epoch and durable operation receipts.
Every declared writer takes shared advisory lock `714402216006` and locks the
epoch. Check/freeze/release take the exclusive side. A freeze drains earlier
writes, commits its receipt with the reference count, and blocks later inserts
and updates referencing that identity. Epoch row locking prevents an old
repeatable-read snapshot from bypassing a committed fence. Deletions still join
the barrier. Alias history survives native profile or session deletion.

The 42 declared sources cover direct content owners and actors, session children,
typed parent chains (checkpoint files, console output, OpenAI conversations/run
events and bot messages), native account authorities and immutable alias ledgers.
The complete reviewed list is stored in `java_identity_reference_sources` by the
migration. Missing required tables, missing/disabled triggers, missing epoch and
unresolved references make the owner unavailable. Optional bot tables are checked
whenever present; absence does not bypass checks for an installed table.

Central storage aliases are established only from a verified live central session
with its exact issuer/subject. Native directory and Telegram authorities retain
their native kind. A native UUID is not treated as a central subject. The actual
authenticated native JDBC transaction can establish a new native namespace via
transaction-local provenance; it cannot relabel an existing central alias or
bypass that alias's fence. Context is carried across the existing asynchronous
streaming boundary and cleared afterwards. The Telegram adapter registers its
actual numeric sender/chat key as native without fabricating a human context.

Profile memory resolves only the exact `MemoryScope` suffix, including its
explicit invalid-profile sentinel. Arbitrary strings and orphaned session/parent
references remain unknown. Legacy central bindings require authoritative
operator evidence. Neither UUID shape nor email nor a current catalog row
supplies it. Before rollout, resolve any unknown legacy keys against their real
authentication provenance; do not classify all old rows as native to make a
check pass. Existing rows are preserved by the additive migration.

## Backend and Telegram migration order

The current supported deployment shares the backend PostgreSQL database with
the bot's separate `flyway_bot_schema_history`. Both backend-first and bot-first
startup must converge. A bot Flyway callback takes the exclusive owner barrier
within each migration transaction, then installs guards on new optional tables
before that transaction commits. A standalone bot database without the backend
helpers keeps its existing independent behavior. Moving bot data to a separate
database requires a separately qualified owner; a same-database receipt cannot
acknowledge a different store.

## Qualification

All new database cases require an explicit disposable
`JAVA_IDENTITY_TEST_JDBC_URL` whose actual database is `java_identity_test`.
Missing configuration fails rather than silently skipping. Each test uses its
own schema and cleans that exact schema in `finally`. Run containers only via
the Base ComposeHelper v2 temporary-project harness with the pinned Temurin
25.0.4.1+1-LTS and committed Gradle wrapper; never target installed data.

The candidate includes:

- `IdentityDeletionProtocolTest`, `NativeIdentityOriginTest` and actual filter,
  Telegram resolver and asynchronous streaming regressions;
- `:backend:slowTest --tests '*IdentityDeletionOwnerPostgresTest'`: populated
  predecessor upgrade, provenance, every backend source with real rows and
  writes, exact replay/cancellation, concurrent writer drain, old repeatable-read
  snapshot, missing storage guards/epoch, eight-second SQL bound and actual
  private servlet HTTP with live introspection, expiry and nine-second deadline;
- deferred COMMIT barriers for freeze, release and central registration: the
  exact backend and actual database deadline are still current at the barrier,
  then expiry rolls back every operation, epoch and alias field; unrelated
  PostgreSQL permission diagnostics remain storage failures;
- `:telegram-bot:identityOwnerTest`: both real Flyway histories in both orders,
  actual bot reference rows and raw inserts/updates rejected after freeze.

These are test scenarios, not a qualification receipt. The exact candidate must
pass compilation, all focused and regression tests, coverage floors and migration
checks before rollout. HTTP/PG fixtures use synthetic identities and no external
credentials. Docker and Kubernetes deployment qualification remain separate
explicit gates; source changes do not authorize changing the accepted runtime.
