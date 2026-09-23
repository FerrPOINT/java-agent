# Default Settings Reference

This is the complete shipped-default reference for the Java agent runtime. It covers every leaf in the backend, Telegram bot, and CLI `application.yml` documents, every literal `@ConfigurationProperties` fallback, direct `@Value` fallbacks, and non-configurable transport defaults that affect a user-visible lifecycle.

## Scope And Precedence

1. Base YAML is loaded first.
2. The active profile document overrides matching base settings.
3. An environment placeholder such as `${NAME:fallback}` uses `fallback` only when `NAME` is absent.
4. Spring command-line and system properties have higher precedence.
5. If YAML does not bind a field, the literal constructor fallback in the properties class applies.

Credentials are never documented as values. Rows named `token`, `password`, `secret`, `api-key`, or `username` are redacted as `<operator supplied>`. An empty value means the feature is disabled or must be configured by an operator; it is not a usable sample credential.

## Intentional Interactive Defaults

| Setting | Default | Intent |
|---|---|
| `ClarifyGatewayStore.DEFAULT_TIMEOUT_SECONDS` | `86400` (1 day) | A clarify prompt is a human decision, not a short request. Two to seven days is recommended for unattended/asynchronous channels. |
| `BackendRestClientFactory.CONNECT_TIMEOUT` | `10 seconds` | Limits connection establishment only. |
| `BackendRestClientFactory.READ_TIMEOUT` | `10 minutes` | Covers ordinary backend requests. |
| `BackendRestClientFactory.STREAMING_READ_TIMEOUT` | `8 days` | Leaves margin above the recommended seven-day clarify window. |
| `MessageApiClient.STREAM_IDLE_TIMEOUT_MS` | `8 days` | Keeps Telegram delivery attached to the original blocked turn through a valid delayed reply. |

See `docs/clarify-timeout-policy.md` for expiry and lifecycle behavior.

## Backend Runtime

Authoritative source: `backend/src/main/resources/application.yml`. The value is the literal shipped YAML expression. In `${ENV:fallback}`, `fallback` is used only when `ENV` is unset. Credentials are redacted.

### Profile: `base`

| Property | Shipped default |
|---|---|
| `spring.application.name` | `java-agent` |
| `spring.datasource.url` | `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:java_agent}` |
| `spring.datasource.username` | `<operator supplied>` |
| `spring.datasource.password` | `<operator supplied>` |
| `spring.datasource.driver-class-name` | `org.postgresql.Driver` |
| `spring.datasource.hikari.maximum-pool-size` | `20` |
| `spring.datasource.hikari.minimum-idle` | `5` |
| `spring.datasource.hikari.connection-timeout` | `30000` |
| `spring.datasource.hikari.idle-timeout` | `600000` |
| `spring.datasource.hikari.max-lifetime` | `1800000` |
| `spring.datasource.hikari.leak-detection-timeout` | `60000` |
| `spring.jpa.open-in-view` | `false` |
| `spring.jpa.hibernate.ddl-auto` | `none` |
| `spring.jpa.properties.hibernate.format_sql` | `true` |
| `spring.jpa.properties.hibernate.jdbc.batch_size` | `30` |
| `spring.jpa.properties.hibernate.jdbc.fetch_size` | `50` |
| `spring.jpa.properties.hibernate.jdbc.batch_versioned_data` | `true` |
| `spring.jpa.properties.hibernate.order_inserts` | `true` |
| `spring.jpa.properties.hibernate.order_updates` | `true` |
| `spring.jpa.show-sql` | `false` |
| `spring.flyway.locations` | `classpath:db/migration` |
| `spring.flyway.baseline-on-migrate` | `true` |
| `spring.threads.virtual.enabled` | `true` |
| `spring.servlet.multipart.max-file-size` | `100MB` |
| `spring.servlet.multipart.max-request-size` | `100MB` |
| `springdoc.api-docs.path` | `/api-docs` |
| `springdoc.swagger-ui.path` | `/swagger-ui.html` |
| `server.port` | `${AGENT_SERVER_PORT:8090}` |
| `server.shutdown` | `immediate` |
| `server.servlet.context-path` | `/` |
| `server.servlet.session.cookie.http-only` | `true` |
| `server.tomcat.max-swallow-size` | `50MB` |
| `server.tomcat.max-http-form-post-size` | `50MB` |
| `agent.name` | `${AGENT_NAME:Джава агент}` |
| `agent.model.provider` | `${AGENT_MODEL_PROVIDER:openai-compatible}` |
| `agent.model.base-url` | `${AGENT_MODEL_BASE_URL:http://localhost:11434/v1}` |
| `agent.model.api-key` | `<operator supplied>` |
| `agent.model.model-name` | `${AGENT_MODEL_NAME:kimi-k2.6}` |
| `agent.model.timeout-seconds` | `${AGENT_MODEL_TIMEOUT_SECONDS:600}` |
| `agent.model.health-timeout-seconds` | `${AGENT_MODEL_HEALTH_TIMEOUT_SECONDS:10}` |
| `agent.model.max-retries` | `${AGENT_MODEL_MAX_RETRIES:3}` |
| `agent.model.max-tokens` | `${AGENT_MODEL_MAX_TOKENS:4096}` |
| `agent.model.temperature` | `${AGENT_MODEL_TEMPERATURE:0.7}` |
| `agent.model.reasoning-effort` | `${AGENT_MODEL_REASONING_EFFORT:100}` |
| `agent.model.fast-mode` | `${AGENT_MODEL_FAST_MODE:false}` |
| `agent.model.return-thinking` | `${AGENT_MODEL_RETURN_THINKING:false}` |
| `agent.model.thinking-field-name` | `${AGENT_MODEL_THINKING_FIELD_NAME:reasoning_content}` |
| `agent.model.max-image-size-bytes` | `${AGENT_MODEL_MAX_IMAGE_SIZE_BYTES:4194304}` |
| `agent.model.max-total-image-size-bytes` | `${AGENT_MODEL_MAX_TOTAL_IMAGE_SIZE_BYTES:20971520}` |
| `agent.model.image-jpeg-quality` | `${AGENT_MODEL_IMAGE_JPEG_QUALITY:0.85}` |
| `agent.auxiliary.enabled` | `${AGENT_AUXILIARY_ENABLED:false}` |
| `agent.auxiliary.provider` | `${AGENT_AUXILIARY_PROVIDER:openai-compatible}` |
| `agent.auxiliary.base-url` | `${AGENT_AUXILIARY_BASE_URL:}` |
| `agent.auxiliary.api-key` | `<operator supplied>` |
| `agent.auxiliary.model-name` | `${AGENT_AUXILIARY_MODEL_NAME:}` |
| `agent.auxiliary.timeout-seconds` | `${AGENT_AUXILIARY_TIMEOUT_SECONDS:600}` |
| `agent.auxiliary.max-retries` | `${AGENT_AUXILIARY_MAX_RETRIES:3}` |
| `agent.vision.provider` | `${AGENT_VISION_PROVIDER:}` |
| `agent.vision.base-url` | `${AGENT_VISION_BASE_URL:}` |
| `agent.vision.api-key` | `<operator supplied>` |
| `agent.vision.model-name` | `${AGENT_VISION_MODEL_NAME:}` |
| `agent.vision.timeout-seconds` | `${AGENT_VISION_TIMEOUT_SECONDS:600}` |
| `agent.vision.max-retries` | `${AGENT_VISION_MAX_RETRIES:3}` |
| `agent.vision.use-auxiliary-first` | `${AGENT_VISION_USE_AUXILIARY_FIRST:true}` |
| `agent.browser.cdp-url` | `${AGENT_BROWSER_CDP_URL:http://localhost:9222}` |
| `agent.browser.default-timeout-ms` | `${AGENT_BROWSER_DEFAULT_TIMEOUT_MS:120000}` |
| `agent.browser.page-load-timeout-ms` | `${AGENT_BROWSER_PAGE_LOAD_TIMEOUT_MS:120000}` |
| `agent.browser.max-tabs` | `${AGENT_BROWSER_MAX_TABS:5}` |
| `agent.browser.headless` | `${AGENT_BROWSER_HEADLESS:true}` |
| `agent.browser.executable-path` | `${AGENT_BROWSER_EXECUTABLE_PATH:}` |
| `agent.chromium.auto-start` | `${AGENT_CHROMIUM_AUTO_START:false}` |
| `agent.chromium.auto-install` | `${AGENT_CHROMIUM_AUTO_INSTALL:true}` |
| `agent.chromium.download-url` | `${AGENT_CHROMIUM_DOWNLOAD_URL:https://storage.googleapis.com/chromium-browser-snapshots}` |
| `agent.chromium.revision` | `${AGENT_CHROMIUM_REVISION:}` |
| `agent.chromium.launch-timeout-seconds` | `${AGENT_CHROMIUM_LAUNCH_TIMEOUT_SECONDS:120}` |
| `agent.chromium.headless` | `${AGENT_CHROMIUM_HEADLESS:true}` |
| `agent.chromium.executable-path` | `${AGENT_CHROMIUM_EXECUTABLE_PATH:}` |
| `agent.chromium.user-data-dir` | `${AGENT_CHROMIUM_USER_DATA_DIR:}` |
| `agent.chromium.extra-args` | `${AGENT_CHROMIUM_EXTRA_ARGS:}` |
| `agent.web.search-results` | `${AGENT_WEB_SEARCH_RESULTS:5}` |
| `agent.web.extract-timeout-seconds` | `${AGENT_WEB_EXTRACT_TIMEOUT_SECONDS:120}` |
| `agent.web.extract-max-chars` | `${AGENT_WEB_EXTRACT_MAX_CHARS:100000}` |
| `agent.web.extract-cache-dir` | `${AGENT_WEB_EXTRACT_CACHE_DIR:}` |
| `agent.web.search-provider` | `${AGENT_WEB_SEARCH_PROVIDER:ddg}` |
| `agent.web.searxng-url` | `${AGENT_WEB_SEARXNG_URL:}` |
| `agent.web.allowed-domains` | `${AGENT_WEB_ALLOWED_DOMAINS:}` |
| `agent.web.blocked-domains` | `${AGENT_WEB_BLOCKED_DOMAINS:}` |
| `agent.terminal.default-timeout-seconds` | `${AGENT_TERMINAL_DEFAULT_TIMEOUT_SECONDS:300}` |
| `agent.terminal.max-timeout-seconds` | `${AGENT_TERMINAL_MAX_TIMEOUT_SECONDS:1800}` |
| `agent.terminal.docker-enabled` | `${AGENT_TERMINAL_DOCKER_ENABLED:false}` |
| `agent.terminal.block-sudo` | `${AGENT_TERMINAL_BLOCK_SUDO:true}` |
| `agent.file.read-max-chars` | `${AGENT_FILE_READ_MAX_CHARS:100000}` |
| `agent.file.write-max-chars` | `${AGENT_FILE_WRITE_MAX_CHARS:100000}` |
| `agent.memory.enabled` | `${AGENT_MEMORY_ENABLED:true}` |
| `agent.memory.write-approval` | `${AGENT_MEMORY_WRITE_APPROVAL:false}` |
| `agent.memory.memory-char-limit` | `${AGENT_MEMORY_CHAR_LIMIT:2200}` |
| `agent.memory.user-char-limit` | `${AGENT_MEMORY_USER_CHAR_LIMIT:1375}` |
| `agent.memory.max-facts-per-user` | `${AGENT_MEMORY_MAX_FACTS_PER_USER:1000}` |
| `agent.memory.max-facts-per-query` | `${AGENT_MEMORY_MAX_FACTS_PER_QUERY:10}` |
| `agent.memory.similarity-threshold` | `${AGENT_MEMORY_SIMILARITY_THRESHOLD:0.75}` |
| `agent.memory.nudge-interval` | `${AGENT_MEMORY_NUDGE_INTERVAL:10}` |
| `agent.memory.background-review.enabled` | `${AGENT_MEMORY_BACKGROUND_REVIEW_ENABLED:true}` |
| `agent.memory.background-review.delay-ms` | `${AGENT_MEMORY_BACKGROUND_REVIEW_DELAY_MS:2000}` |
| `agent.memory.background-review.max-review-turns` | `${AGENT_MEMORY_BACKGROUND_REVIEW_MAX_TURNS:16}` |
| `agent.memory.background-review.max-input-tokens` | `${AGENT_MEMORY_BACKGROUND_REVIEW_MAX_INPUT_TOKENS:600000}` |
| `agent.skills.enabled` | `${AGENT_SKILLS_ENABLED:true}` |
| `agent.skills.max-skills-in-prompt` | `${AGENT_SKILLS_MAX_SKILLS_IN_PROMPT:20}` |
| `agent.skills.max-chars-per-skill` | `${AGENT_SKILLS_MAX_CHARS_PER_SKILL:4000}` |
| `agent.skills.inline-shell` | `${AGENT_SKILLS_INLINE_SHELL:true}` |
| `agent.skills.inline-shell-timeout` | `${AGENT_SKILLS_INLINE_SHELL_TIMEOUT:30}` |
| `agent.skills.creation-nudge-interval` | `${AGENT_SKILLS_CREATION_NUDGE_INTERVAL:10}` |
| `agent.skills.default-toolsets` | `[hermes-cli]` |
| `agent.session-search.max-results` | `${AGENT_SESSION_SEARCH_MAX_RESULTS:10}` |
| `agent.session-search.snippet-chars` | `${AGENT_SESSION_SEARCH_SNIPPET_CHARS:200}` |
| `agent.tool-output.max-chars` | `${AGENT_TOOL_OUTPUT_MAX_CHARS:50000}` |
| `agent.tool-output.truncate-warning-chars` | `${AGENT_TOOL_OUTPUT_TRUNCATE_WARNING_CHARS:12000}` |
| `agent.tool-output.timeout-seconds` | `${AGENT_TOOL_OUTPUT_TIMEOUT_SECONDS:300}` |
| `agent.tool-output.include-timestamps` | `${AGENT_TOOL_OUTPUT_INCLUDE_TIMESTAMPS:true}` |
| `agent.tool-output.terminal-max-chars` | `${AGENT_TOOL_OUTPUT_TERMINAL_MAX_CHARS:0}` |
| `agent.tool-output.read-file-max-lines` | `${AGENT_TOOL_OUTPUT_READ_FILE_MAX_LINES:0}` |
| `agent.tool-output.per-line-max-chars` | `${AGENT_TOOL_OUTPUT_PER_LINE_MAX_CHARS:0}` |
| `agent.tool-output.web-extract-max-chars` | `${AGENT_TOOL_OUTPUT_WEB_EXTRACT_MAX_CHARS:0}` |
| `agent.tool-output.persist-threshold-bytes` | `${AGENT_TOOL_OUTPUT_PERSIST_THRESHOLD_BYTES:51200}` |
| `agent.tool-output.turn-budget-bytes` | `${AGENT_TOOL_OUTPUT_TURN_BUDGET_BYTES:204800}` |
| `agent.context.max-tokens` | `${AGENT_CONTEXT_MAX_TOKENS:16000}` |
| `agent.context.target-tokens` | `${AGENT_CONTEXT_TARGET_TOKENS:12000}` |
| `agent.context.summary-chunk-tokens` | `${AGENT_CONTEXT_SUMMARY_CHUNK_TOKENS:2000}` |
| `agent.context.max-context-messages` | `${AGENT_CONTEXT_MAX_CONTEXT_MESSAGES:10000}` |
| `agent.compression.enabled` | `${AGENT_COMPRESSION_ENABLED:true}` |
| `agent.compression.summary-chunk-tokens` | `${AGENT_COMPRESSION_SUMMARY_CHUNK_TOKENS:2000}` |
| `agent.compression.abort-on-summary-failure` | `${AGENT_COMPRESSION_ABORT_ON_SUMMARY_FAILURE:false}` |
| `agent.compression.session-rotation.enabled` | `${AGENT_COMPRESSION_SESSION_ROTATION_ENABLED:true}` |
| `agent.delegation.enabled` | `${AGENT_DELEGATION_ENABLED:true}` |
| `agent.delegation.max-depth` | `${AGENT_DELEGATION_MAX_DEPTH:3}` |
| `agent.delegation.max-spawn-depth` | `${AGENT_DELEGATION_MAX_SPAWN_DEPTH:1}` |
| `agent.delegation.max-concurrent-children` | `${AGENT_DELEGATION_MAX_CONCURRENT_CHILDREN:10}` |
| `agent.delegation.default-timeout-seconds` | `${AGENT_DELEGATION_DEFAULT_TIMEOUT_SECONDS:300}` |
| `agent.delegation.child-timeout-seconds` | `${AGENT_DELEGATION_CHILD_TIMEOUT_SECONDS:0}` |
| `agent.delegation.orchestrator-enabled` | `${AGENT_DELEGATION_ORCHESTRATOR_ENABLED:true}` |
| `agent.delegation.blocked-tools` | `${AGENT_DELEGATION_BLOCKED_TOOLS:delegate_task,clarify,memory,send_message,cronjob,delete_file}` |
| `agent.mcp.enabled` | `${AGENT_MCP_ENABLED:false}` |
| `agent.mcp.osv-check-enabled` | `${AGENT_MCP_OSV_CHECK_ENABLED:true}` |
| `agent.mcp.servers[0].name` | `${AGENT_MCP_REPOMIX_NAME:repomix}` |
| `agent.mcp.servers[0].transport` | `stdio` |
| `agent.mcp.servers[0].command` | `repomix` |
| `agent.mcp.servers[0].args` | `[--mcp, --sandbox, .]` |
| `agent.mcp.server.enabled` | `${AGENT_MCP_SERVER_ENABLED:false}` |
| `agent.mcp.server.transport` | `${AGENT_MCP_SERVER_TRANSPORT:stdio}` |
| `agent.mcp.server.sse-endpoint` | `${AGENT_MCP_SERVER_SSE_ENDPOINT:/mcp/sse}` |
| `agent.mcp.server.message-endpoint` | `${AGENT_MCP_SERVER_MESSAGE_ENDPOINT:/mcp/message}` |
| `agent.mcp.server.name` | `${AGENT_MCP_SERVER_NAME:java-agent}` |
| `agent.mcp.server.version` | `${AGENT_MCP_SERVER_VERSION:1.0.0}` |
| `agent.gateway.busy-input-mode` | `${AGENT_BUSY_INPUT_MODE:interrupt}` |
| `agent.gateway.busy-ack-enabled` | `${AGENT_BUSY_ACK_ENABLED:true}` |
| `agent.gateway.telegram.bot-token` | `<operator supplied>` |
| `agent.gateway.telegram.webhook-url` | `${AGENT_GATEWAY_TELEGRAM_WEBHOOK_URL:}` |
| `agent.gateway.telegram.timeout-seconds` | `${AGENT_GATEWAY_TELEGRAM_TIMEOUT_SECONDS:30}` |
| `agent.gateway.telegram.allowed-user-ids` | `${AGENT_GATEWAY_TELEGRAM_ALLOWED_USER_IDS:}` |
| `agent.gateway.telegram.allowed-usernames` | `${AGENT_GATEWAY_TELEGRAM_ALLOWED_USERNAMES:}` |
| `agent.gateway.telegram.allow-by-default` | `${AGENT_GATEWAY_TELEGRAM_ALLOW_BY_DEFAULT:false}` |
| `agent.gateway.telegram.webhook.enabled` | `${AGENT_GATEWAY_TELEGRAM_WEBHOOK_ENABLED:false}` |
| `agent.gateway.telegram.webhook-secret` | `<operator supplied>` |
| `agent.gateway.telegram.long-polling.enabled` | `${AGENT_GATEWAY_TELEGRAM_LONG_POLLING_ENABLED:false}` |
| `agent.security.api-key` | `<operator supplied>` |
| `agent.security.approvals-enabled` | `${AGENT_SECURITY_APPROVALS_ENABLED:true}` |
| `agent.security.always-require-approval-tools` | `${AGENT_SECURITY_ALWAYS_REQUIRE_APPROVAL_TOOLS:}` |
| `agent.security.file-safety-enabled` | `${AGENT_SECURITY_FILE_SAFETY_ENABLED:true}` |
| `agent.security.url-safety-enabled` | `${AGENT_SECURITY_URL_SAFETY_ENABLED:true}` |
| `agent.security.allow-private-urls` | `${AGENT_SECURITY_ALLOW_PRIVATE_URLS:${HERMES_ALLOW_PRIVATE_URLS:false}}` |
| `agent.security.redact-enabled` | `${AGENT_SECURITY_REDACT_ENABLED:true}` |
| `agent.security.redact-secrets` | `${AGENT_SECURITY_REDACT_SECRETS:true}` |
| `agent.security.redact-pii` | `${AGENT_SECURITY_REDACT_PII:false}` |
| `agent.security.blocked-commands` | `${AGENT_SECURITY_BLOCKED_COMMANDS:}` |
| `agent.api.model-name` | `${AGENT_API_MODEL_NAME:java-agent}` |
| `agent.api.direct-model-requests` | `${AGENT_API_DIRECT_MODEL_REQUESTS:false}` |
| `agent.api.max-concurrent-runs` | `${AGENT_API_MAX_CONCURRENT_RUNS:10}` |
| `agent.api.chat-completion-toolsets` | `[${AGENT_API_CHAT_COMPLETION_TOOLSET:hermes-api-server}]` |
| `agent.api.cors-origins` | `${AGENT_API_CORS_ORIGINS:}` |
| `agent.core.max-turns` | `${AGENT_CORE_MAX_TURNS:100}` |
| `agent.core.tool-use-enforcement` | `${AGENT_CORE_TOOL_USE_ENFORCEMENT:auto}` |
| `agent.core.task-completion-guidance` | `${AGENT_CORE_TASK_COMPLETION_GUIDANCE:true}` |
| `agent.core.parallel-tool-call-guidance` | `${AGENT_CORE_PARALLEL_TOOL_CALL_GUIDANCE:true}` |
| `agent.core.auto-title-session` | `${AGENT_CORE_AUTO_TITLE_SESSION:true}` |
| `agent.core.reasoning-config` | `${AGENT_CORE_REASONING_CONFIG:medium}` |
| `agent.core.default-system-prompt` | `${AGENT_CORE_DEFAULT_SYSTEM_PROMPT:You are ${agent.name}. Use available tools when needed. Be concise. Return plain text unless JSON is requested.}` |
| `agent.core.http-client-timeout-seconds` | `${AGENT_CORE_HTTP_CLIENT_TIMEOUT_SECONDS:30}` |
| `agent.core.max-reference-file-bytes` | `${AGENT_CORE_MAX_REFERENCE_FILE_BYTES:100000}` |
| `agent.core.working-directory` | `${AGENT_CORE_WORKING_DIRECTORY:/workspace}` |
| `agent.core.coding-context` | `${AGENT_CORE_CODING_CONTEXT:off}` |
| `agent.core.http-user-agent` | `${AGENT_CORE_HTTP_USER_AGENT:AzhukovAgent/1.0}` |
| `agent.budget.enabled` | `${AGENT_BUDGET_ENABLED:true}` |
| `agent.budget.max-model-calls-per-turn` | `${AGENT_BUDGET_MAX_MODEL_CALLS_PER_TURN:100}` |
| `agent.budget.max-tool-executions-per-turn` | `${AGENT_BUDGET_MAX_TOOL_EXECUTIONS_PER_TURN:100}` |
| `agent.budget.max-tokens-per-turn` | `${AGENT_BUDGET_MAX_TOKENS_PER_TURN:200000}` |
| `agent.budget.max-tool-duration-ms-per-turn` | `${AGENT_BUDGET_MAX_TOOL_DURATION_MS_PER_TURN:600000}` |
| `agent.budget.run-budget-seconds` | `${AGENT_BUDGET_RUN_BUDGET_SECONDS:0}` |
| `agent.prompt-caching.enabled` | `${AGENT_PROMPT_CACHING_ENABLED:true}` |
| `agent.prompt-caching.track-stats` | `${AGENT_PROMPT_CACHING_TRACK_STATS:false}` |
| `agent.checkpoints.enabled` | `${AGENT_CHECKPOINTS_ENABLED:true}` |
| `agent.checkpoints.max-snapshots` | `${AGENT_CHECKPOINTS_MAX_SNAPSHOTS:20}` |
| `agent.checkpoints.max-size-mb` | `${AGENT_CHECKPOINTS_MAX_SIZE_MB:500}` |
| `agent.verify-on-stop.enabled` | `${AGENT_VERIFY_ON_STOP:false}` |
| `agent.usage.track-enabled` | `${AGENT_USAGE_TRACK_ENABLED:true}` |
| `agent.usage.show-cost` | `${AGENT_USAGE_SHOW_COST:false}` |
| `agent.usage.show-token-analytics` | `<operator supplied>` |
| `agent.image-gen.enabled` | `${AGENT_IMAGE_GEN_ENABLED:false}` |
| `agent.image-gen.provider` | `${AGENT_IMAGE_GEN_PROVIDER:pollinations}` |
| `agent.image-gen.api-key` | `<operator supplied>` |
| `agent.image-gen.model` | `${AGENT_IMAGE_GEN_MODEL:}` |
| `agent.tts.enabled` | `${AGENT_TTS_ENABLED:false}` |
| `agent.tts.provider` | `${AGENT_TTS_PROVIDER:edge}` |
| `agent.tts.api-key` | `<operator supplied>` |
| `agent.tts.model` | `${AGENT_TTS_MODEL:gpt-4o-mini-tts}` |
| `agent.tts.voice` | `${AGENT_TTS_VOICE:alloy}` |
| `agent.tts.edge.command` | `${AGENT_TTS_EDGE_COMMAND:}` |
| `agent.tts.edge.voice` | `${AGENT_TTS_EDGE_VOICE:ru-RU-DmitryNeural}` |
| `agent.tts.auto-tts` | `${AGENT_TTS_AUTO_TTS:false}` |
| `agent.transcription.enabled` | `${AGENT_TRANSCRIPTION_ENABLED:false}` |
| `agent.transcription.provider` | `${AGENT_TRANSCRIPTION_PROVIDER:openai}` |
| `agent.transcription.api-key` | `<operator supplied>` |
| `agent.transcription.model` | `${AGENT_TRANSCRIPTION_MODEL:whisper-1}` |
| `agent.cron.enabled` | `${AGENT_CRON_ENABLED:false}` |
| `agent.cron.max-parallel-jobs` | `${AGENT_CRON_MAX_PARALLEL_JOBS:10}` |
| `agent.cron.dispatch-interval-seconds` | `${AGENT_CRON_DISPATCH_INTERVAL_SECONDS:60}` |
| `agent.commentary-enabled` | `${AGENT_COMMENTARY_ENABLED:true}` |
| `agent.error.retry-attempts` | `${AGENT_ERROR_RETRY_ATTEMPTS:100}` |
| `agent.error.retry-delay-ms` | `${AGENT_ERROR_RETRY_DELAY_MS:1000}` |
| `agent.error.backoff-multiplier` | `${AGENT_ERROR_BACKOFF_MULTIPLIER:2}` |
| `agent.coding-context.enabled` | `${AGENT_CODING_CONTEXT_ENABLED:true}` |
| `agent.coding-context.min-score` | `${AGENT_CODING_CONTEXT_MIN_SCORE:0.5}` |
| `agent.tools.managed-gateway-enabled` | `${AGENT_TOOLS_MANAGED_GATEWAY_ENABLED:false}` |
| `agent.curator.enabled` | `${AGENT_CURATOR_ENABLED:true}` |
| `agent.curator.interval-hours` | `${AGENT_CURATOR_INTERVAL_HOURS:168}` |
| `agent.curator.min-idle-hours` | `${AGENT_CURATOR_MIN_IDLE_HOURS:2.0}` |
| `agent.curator.stale-after-days` | `${AGENT_CURATOR_STALE_AFTER_DAYS:30}` |
| `agent.curator.archive-after-days` | `${AGENT_CURATOR_ARCHIVE_AFTER_DAYS:90}` |
| `agent.curator.dry-run` | `${AGENT_CURATOR_DRY_RUN:false}` |
| `agent.curator.backup-keep` | `${AGENT_CURATOR_BACKUP_KEEP:5}` |
| `agent.curator.prune-builtins` | `${AGENT_CURATOR_PRUNE_BUILTINS:true}` |
| `agent.profile.name` | `${AGENT_PROFILE_NAME:default}` |
| `agent.profile.base-dir` | `${AGENT_PROFILE_BASE_DIR:}` |
| `management.server.port` | `${MANAGEMENT_SERVER_PORT:}` |
| `management.endpoints.web.exposure.include` | `health,info,metrics,prometheus` |
| `management.endpoint.health.show-details` | `when_authorized` |
| `management.endpoint.health.groups.readiness.include` | `db` |
| `management.endpoint.health.groups.liveness.include` | `livenessState,ping` |
| `management.endpoint.health.groups.infrastructure.include` | `mcp,browser,chromium,model` |
| `resilience4j.retry.configs.default.maxAttempts` | `3` |
| `resilience4j.retry.configs.default.waitDuration` | `1s` |
| `resilience4j.retry.configs.default.exponentialBackoffMultiplier` | `2` |
| `resilience4j.retry.instances.model.baseConfig` | `default` |
| `resilience4j.timelimiter.configs.default.timeoutDuration` | `120s` |
| `resilience4j.timelimiter.configs.default.cancelRunningFuture` | `true` |
| `resilience4j.timelimiter.instances.model.baseConfig` | `default` |

| Property | Shipped default | Source |
|---|---|---|
| `spring.jpa.properties.hibernate` | `org.hibernate.dialect.H2Dialect` | Backend `noop` profile dialect. |
| `agent.mcp.servers.args` | `[--mcp, --sandbox, .]` | Shipped Repomix stdio arguments. |
| `agent.mcp.servers.command` | `repomix` | Shipped Repomix stdio executable. |
| `agent.mcp.servers.transport` | `stdio` | Shipped MCP transport. |

### Profile: `dev`

| Property | Shipped default |
|---|---|
| `spring.config.activate.on-profile` | `dev` |
| `agent.gateway.telegram.webhook.enabled` | `${AGENT_GATEWAY_TELEGRAM_WEBHOOK_ENABLED:true}` |
| `agent.model.provider` | `${AGENT_MODEL_PROVIDER:openai-compatible}` |
| `agent.model.base-url` | `${OLLAMA_BASE_URL:https://ollama.com/v1}` |
| `agent.model.api-key` | `<operator supplied>` |
| `agent.model.model-name` | `${AGENT_MODEL_NAME:kimi-k2.6}` |
| `agent.model.timeout-seconds` | `600` |
| `agent.model.max-retries` | `2` |
| `agent.model.max-tokens` | `4096` |
| `agent.model.temperature` | `0.7` |
| `agent.model.reasoning-effort` | `100` |
| `agent.model.fast-mode` | `false` |
| `agent.chromium.auto-start` | `${AGENT_CHROMIUM_AUTO_START:false}` |
| `logging.level.com.azhukov.agent` | `DEBUG` |

### Profile: `noop`

| Property | Shipped default |
|---|---|
| `spring.config.activate.on-profile` | `noop` |
| `spring.datasource.url` | `jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false` |
| `spring.datasource.driver-class-name` | `org.h2.Driver` |
| `spring.datasource.username` | `<operator supplied>` |
| `spring.datasource.password` | `<operator supplied>` |
| `spring.jpa.hibernate.ddl-auto` | `create-drop` |
| `spring.jpa.properties.hibernate.dialect` | `org.hibernate.dialect.H2Dialect` |
| `spring.flyway.enabled` | `false` |
| `agent.memory.enabled` | `false` |
| `agent.skills.enabled` | `false` |
| `agent.chromium.auto-start` | `false` |
| `agent.chromium.auto-install` | `false` |
| `agent.model.provider` | `noop` |
| `server.shutdown` | `immediate` |

### Profile: `prod`

| Property | Shipped default |
|---|---|
| `spring.config.activate.on-profile` | `prod` |
| `agent.model.api-key` | `<operator supplied>` |
| `agent.model.model-name` | `${AGENT_MODEL_NAME:gpt-4o-mini}` |
| `logging.level.com.azhukov.agent` | `INFO` |

## Telegram Bot

Authoritative source: `telegram-bot/src/main/resources/application.yml`. The value is the literal shipped YAML expression. In `${ENV:fallback}`, `fallback` is used only when `ENV` is unset. Credentials are redacted.

### Profile: `base`

| Property | Shipped default |
|---|---|
| `spring.application.name` | `telegram-bot` |
| `spring.datasource.url` | `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:java_agent}` |
| `spring.datasource.username` | `<operator supplied>` |
| `spring.datasource.password` | `<operator supplied>` |
| `spring.datasource.driver-class-name` | `org.postgresql.Driver` |
| `spring.jpa.open-in-view` | `false` |
| `spring.jpa.hibernate.ddl-auto` | `none` |
| `spring.jpa.properties.hibernate.format_sql` | `true` |
| `spring.jpa.show-sql` | `false` |
| `spring.flyway.enabled` | `true` |
| `spring.flyway.locations` | `classpath:db/bot-migration` |
| `spring.flyway.baseline-on-migrate` | `true` |
| `spring.flyway.baseline-version` | `0` |
| `spring.flyway.table` | `flyway_bot_schema_history` |
| `spring.threads.virtual.enabled` | `true` |
| `server.port` | `${BOT_SERVER_PORT:8091}` |
| `server.shutdown` | `immediate` |
| `bot.token` | `<operator supplied>` |
| `bot.mode` | `${BOT_MODE:polling}` |
| `bot.agent-name` | `${BOT_AGENT_NAME:Джава агент}` |
| `bot.backend-url` | `${BOT_BACKEND_URL:http://localhost:8090}` |
| `bot.backend-api-key` | `<operator supplied>` |
| `bot.max-message-length` | `4096` |
| `bot.typing-refresh-interval` | `4s` |
| `bot.stream-edit-interval` | `800ms` |
| `bot.buffer-threshold` | `24` |
| `bot.heartbeat-interval-seconds` | `${BOT_HEARTBEAT_INTERVAL:30}` |
| `bot.initial-stream-text` | `` |
| `bot.streaming-max-chars` | `32768` |
| `bot.streaming-transport` | `${AGENT_STREAMING_TRANSPORT:edit}` |
| `bot.busy-mode` | `queue` |
| `bot.busy-input-mode` | `${BOT_BUSY_INPUT_MODE:interrupt}` |
| `bot.busy-ack-enabled` | `${BOT_BUSY_ACK_ENABLED:true}` |
| `bot.parse-mode` | `MarkdownV2` |
| `bot.register-commands` | `true` |
| `bot.rate-limit-per-second` | `25` |
| `bot.working-directory` | `${BOT_WORKING_DIRECTORY:${user.dir}}` |
| `bot.default-model` | `${BOT_DEFAULT_MODEL:${AGENT_MODEL_NAME:}}` |
| `bot.available-models` | `${BOT_AVAILABLE_MODELS:}` |
| `bot.reply-to-mode` | `${BOT_REPLY_TO_MODE:first}` |
| `bot.link-preview` | `${BOT_LINK_PREVIEW:true}` |
| `bot.media-delivery-enabled` | `${BOT_MEDIA_DELIVERY_ENABLED:true}` |
| `bot.footer.enabled` | `${BOT_FOOTER_ENABLED:true}` |
| `bot.footer.fields` | `[model, context_pct, cwd]` |
| `bot.reactions.enabled` | `${BOT_REACTIONS_ENABLED:true}` |
| `bot.text-batch.delay-ms` | `500` |
| `bot.text-batch.split-delay-ms` | `1200` |
| `bot.text-batch.fast-delay-ms` | `180` |
| `bot.group.require-mention` | `${BOT_GROUP_REQUIRE_MENTION:false}` |
| `bot.group.guest-mode` | `${BOT_GROUP_GUEST_MODE:false}` |
| `bot.group.observe-unmentioned` | `false` |
| `bot.group.exclusive-bot-mentions` | `false` |
| `bot.group.free-response-chats` | `[]` |
| `bot.group.allowed-topics` | `[]` |
| `bot.group.ignored-threads` | `[]` |
| `bot.group.dm-topics` | `[]` |
| `bot.polling.timeout-seconds` | `30` |
| `bot.polling.limit` | `100` |
| `bot.polling.reconnect-delay-ms` | `5000` |
| `bot.polling.reconnect-backoff-multiplier` | `1.5` |
| `bot.polling.reconnect-max-delay-ms` | `60000` |
| `bot.webhook.url` | `${BOT_WEBHOOK_URL:}` |
| `bot.webhook.secret` | `<operator supplied>` |
| `bot.webhook.path` | `/webhook/telegram` |
| `bot.webhook.port` | `8443` |
| `bot.auth.allowed-user-ids` | `${BOT_ALLOWED_USER_IDS:}` |
| `bot.auth.allowed-usernames` | `${BOT_ALLOWED_USERNAMES:}` |
| `bot.auth.allowed-chat-ids` | `${BOT_ALLOWED_CHAT_IDS:}` |
| `bot.auth.allow-by-default` | `${BOT_ALLOW_BY_DEFAULT:false}` |
| `bot.auth.admin-user-ids` | `${BOT_ADMIN_USER_IDS:}` |
| `bot.auth.user-allowed-commands` | `${BOT_USER_ALLOWED_COMMANDS:}` |
| `bot.auth.pairing.enabled` | `${BOT_PAIRING_ENABLED:false}` |
| `bot.auth.pairing.code-expiry-hours` | `1` |
| `bot.auth.pairing.max-pending` | `3` |
| `bot.display.tool-progress` | `${BOT_DISPLAY_TOOL_PROGRESS:all}` |
| `management.endpoints.web.exposure.include` | `health,info` |
| `management.endpoint.health.show-details` | `when_authorized` |

### Profile: `test`

| Property | Shipped default |
|---|---|
| `spring.config.activate.on-profile` | `test` |
| `spring.datasource.url` | `jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false` |
| `spring.datasource.driver-class-name` | `org.h2.Driver` |
| `spring.datasource.username` | `<operator supplied>` |
| `spring.datasource.password` | `<operator supplied>` |
| `spring.jpa.open-in-view` | `false` |
| `spring.jpa.hibernate.ddl-auto` | `create-drop` |
| `spring.flyway.enabled` | `false` |
| `bot.token` | `<operator supplied>` |
| `bot.mode` | `polling` |
| `bot.backend-url` | `http://localhost:9999` |

### Profile: `dev`

| Property | Shipped default |
|---|---|
| `spring.config.activate.on-profile` | `dev` |
| `bot.mode` | `polling` |
| `bot.auth.allow-by-default` | `true` |
| `logging.level.com.azhukov.agent.bot` | `DEBUG` |

## CLI

Authoritative source: `cli/src/main/resources/application.yml`. The value is the literal shipped YAML expression. In `${ENV:fallback}`, `fallback` is used only when `ENV` is unset. Credentials are redacted.

### Profile: `base`

| Property | Shipped default |
|---|---|
| `spring.main.web-application-type` | `none` |
| `spring.application.name` | `java-agent-cli` |
| `cli.backend-url` | `http://localhost:8090` |
| `cli.session-id` | `` |
| `cli.model` | `` |
| `logging.level.root` | `WARN` |
| `logging.level.com.azhukov.agent.cli` | `INFO` |

## Configuration-Property Constructor Fallbacks

The following values apply when their matching YAML/environment property is absent. Paths use the exact Java nested-class hierarchy to avoid ambiguity between fields with the same short name.

### Backend property fallbacks

Authoritative source: `backend/src/main/java/com/azhukov/agent/config/AgentProperties.java`.

| Field | Literal fallback |
|---|---|
| `AgentProperties.name` | `"Джава агент"` |
| `AgentProperties.commentaryEnabled` | `true` |
| `AgentProperties.ModelProperties.provider` | `"openai-compatible"` |
| `AgentProperties.ModelProperties.baseUrl` | `""` |
| `AgentProperties.ModelProperties.apiKey` | `<operator supplied>` |
| `AgentProperties.ModelProperties.modelName` | `""` |
| `AgentProperties.ModelProperties.timeoutSeconds` | `120` |
| `AgentProperties.ModelProperties.healthTimeoutSeconds` | `10` |
| `AgentProperties.ModelProperties.streamStallSeconds` | `180` |
| `AgentProperties.ModelProperties.maxRetries` | `3` |
| `AgentProperties.ModelProperties.maxTokens` | `4096` |
| `AgentProperties.ModelProperties.temperature` | `0.7` |
| `AgentProperties.ModelProperties.reasoningEffort` | `70` |
| `AgentProperties.ModelProperties.fastMode` | `false` |
| `AgentProperties.ModelProperties.returnThinking` | `false` |
| `AgentProperties.ModelProperties.thinkingFieldName` | `"reasoning_content"` |
| `AgentProperties.ModelProperties.maxImageSizeBytes` | `4 * 1024 * 1024` |
| `AgentProperties.ModelProperties.maxTotalImageSizeBytes` | `20 * 1024 * 1024` |
| `AgentProperties.ModelProperties.imageJpegQuality` | `0.85` |
| `AgentProperties.AuxiliaryProperties.enabled` | `false` |
| `AgentProperties.AuxiliaryProperties.provider` | `"openai-compatible"` |
| `AgentProperties.AuxiliaryProperties.baseUrl` | `""` |
| `AgentProperties.AuxiliaryProperties.apiKey` | `<operator supplied>` |
| `AgentProperties.AuxiliaryProperties.modelName` | `""` |
| `AgentProperties.AuxiliaryProperties.timeoutSeconds` | `600` |
| `AgentProperties.AuxiliaryProperties.maxRetries` | `3` |
| `AgentProperties.VisionProperties.provider` | `""` |
| `AgentProperties.VisionProperties.baseUrl` | `""` |
| `AgentProperties.VisionProperties.apiKey` | `<operator supplied>` |
| `AgentProperties.VisionProperties.modelName` | `""` |
| `AgentProperties.VisionProperties.timeoutSeconds` | `600` |
| `AgentProperties.VisionProperties.maxRetries` | `3` |
| `AgentProperties.VisionProperties.useAuxiliaryFirst` | `true` |
| `AgentProperties.BrowserProperties.cdpUrl` | `"http://localhost:9222"` |
| `AgentProperties.BrowserProperties.cloudProvider` | `"local"` |
| `AgentProperties.BrowserProperties.backend` | `""` |
| `AgentProperties.BrowserProperties.defaultTimeoutMs` | `120000` |
| `AgentProperties.BrowserProperties.pageLoadTimeoutMs` | `120000` |
| `AgentProperties.BrowserProperties.maxTabs` | `5` |
| `AgentProperties.BrowserProperties.headless` | `true` |
| `AgentProperties.BrowserProperties.executablePath` | `""` |
| `AgentProperties.ChromiumProperties.autoStart` | `true` |
| `AgentProperties.ChromiumProperties.autoInstall` | `true` |
| `AgentProperties.ChromiumProperties.downloadUrl` | `"https://storage.googleapis.com/chromium-browser-snapshots"` |
| `AgentProperties.ChromiumProperties.revision` | `""` |
| `AgentProperties.ChromiumProperties.launchTimeoutSeconds` | `120` |
| `AgentProperties.ChromiumProperties.headless` | `true` |
| `AgentProperties.ChromiumProperties.executablePath` | `""` |
| `AgentProperties.ChromiumProperties.userDataDir` | `""` |
| `AgentProperties.WebProperties.searchResults` | `5` |
| `AgentProperties.WebProperties.extractTimeoutSeconds` | `120` |
| `AgentProperties.WebProperties.extractMaxChars` | `100000` |
| `AgentProperties.WebProperties.extractCacheDir` | `""` |
| `AgentProperties.WebProperties.searchProvider` | `"ddg"` |
| `AgentProperties.WebProperties.searxngUrl` | `""` |
| `AgentProperties.TerminalProperties.defaultTimeoutSeconds` | `300` |
| `AgentProperties.TerminalProperties.maxTimeoutSeconds` | `1800` |
| `AgentProperties.TerminalProperties.dockerEnabled` | `false` |
| `AgentProperties.TerminalProperties.blockSudo` | `true` |
| `AgentProperties.FileProperties.readMaxChars` | `100000` |
| `AgentProperties.FileProperties.writeMaxChars` | `100000` |
| `AgentProperties.MemoryProperties.maxFactsPerUser` | `1000` |
| `AgentProperties.MemoryProperties.maxFactsPerQuery` | `10` |
| `AgentProperties.MemoryProperties.similarityThreshold` | `0.75` |
| `AgentProperties.MemoryProperties.writeApproval` | `false` |
| `AgentProperties.MemoryProperties.memoryCharLimit` | `2200` |
| `AgentProperties.MemoryProperties.userCharLimit` | `1375` |
| `AgentProperties.MemoryProperties.memoryEnabled` | `true` |
| `AgentProperties.MemoryProperties.nudgeInterval` | `10` |
| `AgentProperties.MemoryProperties.userProfileEnabled` | `true` |
| `AgentProperties.MemoryProperties.flushMinTurns` | `6` |
| `AgentProperties.BackgroundReviewProperties.enabled` | `true` |
| `AgentProperties.BackgroundReviewProperties.delayMs` | `2000` |
| `AgentProperties.BackgroundReviewProperties.maxReviewTurns` | `16` |
| `AgentProperties.BackgroundReviewProperties.maxInputTokens` | `600_000` |
| `AgentProperties.SkillsProperties.enabled` | `true` |
| `AgentProperties.SkillsProperties.maxSkillsInPrompt` | `20` |
| `AgentProperties.SkillsProperties.maxCharsPerSkill` | `4000` |
| `AgentProperties.SkillsProperties.defaultToolsets` | `new ArrayList<>(List.of("hermes-cli"))` |
| `AgentProperties.SkillsProperties.templateVars` | `true` |
| `AgentProperties.SkillsProperties.inlineShell` | `true` |
| `AgentProperties.SkillsProperties.inlineShellTimeout` | `30` |
| `AgentProperties.SkillsProperties.creationNudgeInterval` | `10` |
| `AgentProperties.SkillsProperties.hubRepo` | `"https://github.com/FerrPOINT/skills"` |
| `AgentProperties.SkillsProperties.writeApproval` | `false` |
| `AgentProperties.SkillsProperties.guardAgentCreated` | `false` |
| `AgentProperties.SessionSearchProperties.maxResults` | `10` |
| `AgentProperties.SessionSearchProperties.snippetChars` | `200` |
| `AgentProperties.ToolOutputProperties.maxChars` | `16000` |
| `AgentProperties.ToolOutputProperties.truncateWarningChars` | `12000` |
| `AgentProperties.ToolOutputProperties.timeoutSeconds` | `300` |
| `AgentProperties.ToolOutputProperties.includeTimestamps` | `true` |
| `AgentProperties.ToolOutputProperties.persistThresholdBytes` | `51200` |
| `AgentProperties.ToolOutputProperties.turnBudgetBytes` | `204800` |
| `AgentProperties.ToolOutputProperties.terminalMaxChars` | `0` |
| `AgentProperties.ToolOutputProperties.readFileMaxLines` | `0` |
| `AgentProperties.ToolOutputProperties.perLineMaxChars` | `0` |
| `AgentProperties.ToolOutputProperties.webExtractMaxChars` | `0` |
| `AgentProperties.TimeoutsProperties.McpTimeoutProperties.toolCall` | `0` |
| `AgentProperties.ContextProperties.maxTokens` | `16000` |
| `AgentProperties.ContextProperties.targetTokens` | `12000` |
| `AgentProperties.ContextProperties.summaryChunkTokens` | `2000` |
| `AgentProperties.ContextProperties.maxContextMessages` | `50` |
| `AgentProperties.ContextProperties.maxReferenceTokens` | `0` |
| `AgentProperties.ContextProperties.protectFirstN` | `3` |
| `AgentProperties.ContextProperties.protectLastN` | `20` |
| `AgentProperties.ContextProperties.targetRatio` | `0.20` |
| `AgentProperties.ContextProperties.thresholdPercent` | `0.50` |
| `AgentProperties.DelegationProperties.enabled` | `true` |
| `AgentProperties.DelegationProperties.maxDepth` | `3` |
| `AgentProperties.DelegationProperties.maxSpawnDepth` | `1` |
| `AgentProperties.DelegationProperties.maxConcurrentChildren` | `10` |
| `AgentProperties.DelegationProperties.defaultTimeoutSeconds` | `300` |
| `AgentProperties.DelegationProperties.childTimeoutSeconds` | `0` |
| `AgentProperties.DelegationProperties.orchestratorEnabled` | `true` |
| `AgentProperties.DelegationProperties.subagentAutoApprove` | `false` |
| `AgentProperties.DelegationProperties.maxIterations` | `50` |
| `AgentProperties.DelegationProperties.model` | `""` |
| `AgentProperties.DelegationProperties.provider` | `""` |
| `AgentProperties.DelegationProperties.reasoningEffort` | `""` |
| `AgentProperties.McpProperties.enabled` | `false` |
| `AgentProperties.McpProperties.osvCheckEnabled` | `true` |
| `AgentProperties.McpProperties.rateLimitMaxCalls` | `0` |
| `AgentProperties.McpProperties.rateLimitWindowSeconds` | `0` |
| `AgentProperties.McpProperties.lazyStartup` | `true` |
| `AgentProperties.McpProperties.ServerProperties.enabled` | `true` |
| `AgentProperties.McpProperties.ServerProperties.name` | `""` |
| `AgentProperties.McpProperties.ServerProperties.transport` | `"stdio"` |
| `AgentProperties.McpProperties.ServerProperties.command` | `""` |
| `AgentProperties.McpProperties.ServerProperties.baseUrl` | `""` |
| `AgentProperties.McpProperties.ServerProperties.timeout` | `0` |
| `AgentProperties.McpProperties.ServerProperties.timeoutSeconds` | `0` |
| `AgentProperties.McpProperties.ServerProperties.trust` | `"full"` |
| `AgentProperties.McpProperties.ServerProperties.oauthTokenUrl` | `""` |
| `AgentProperties.McpProperties.ServerProperties.stdioParentDeathWatchdog` | `true` |
| `AgentProperties.McpProperties.ServerProperties.oauthClientId` | `""` |
| `AgentProperties.McpProperties.ServerProperties.oauthClientSecret` | `""` |
| `AgentProperties.McpProperties.ServerProperties.oauthScopes` | `""` |
| `AgentProperties.McpProperties.Server.enabled` | `false` |
| `AgentProperties.McpProperties.Server.transport` | `"stdio"` |
| `AgentProperties.McpProperties.Server.sseEndpoint` | `"/mcp/sse"` |
| `AgentProperties.McpProperties.Server.messageEndpoint` | `"/mcp/message"` |
| `AgentProperties.McpProperties.Server.name` | `"java-agent"` |
| `AgentProperties.McpProperties.Server.version` | `"1.0.0"` |
| `AgentProperties.SecurityProperties.approvalsEnabled` | `true` |
| `AgentProperties.SecurityProperties.fileSafetyEnabled` | `true` |
| `AgentProperties.SecurityProperties.urlSafetyEnabled` | `true` |
| `AgentProperties.SecurityProperties.allowPrivateUrls` | `false` |
| `AgentProperties.SecurityProperties.redactEnabled` | `true` |
| `AgentProperties.SecurityProperties.redactSecrets` | `true` |
| `AgentProperties.SecurityProperties.redactPii` | `false` |
| `AgentProperties.SecurityProperties.apiKey` | `<operator supplied>` |
| `AgentProperties.ApiProperties.modelName` | `"java-agent"` |
| `AgentProperties.ApiProperties.chatCompletionToolsets` | `new ArrayList<>(List.of("hermes-api-server"))` |
| `AgentProperties.ApiProperties.directModelRequests` | `false` |
| `AgentProperties.ApiProperties.maxConcurrentRuns` | `10` |
| `AgentProperties.ApiProperties.ModelRouteProperties.model` | `""` |
| `AgentProperties.ApiProperties.ModelRouteProperties.provider` | `""` |
| `AgentProperties.ApiProperties.ModelRouteProperties.baseUrl` | `""` |
| `AgentProperties.ApiProperties.ModelRouteProperties.apiKey` | `<operator supplied>` |
| `AgentProperties.GatewayProperties.busyInputMode` | `"interrupt"` |
| `AgentProperties.GatewayProperties.busyAckEnabled` | `true` |
| `AgentProperties.TelegramProperties.botToken` | `""` |
| `AgentProperties.TelegramProperties.webhookUrl` | `""` |
| `AgentProperties.TelegramProperties.timeoutSeconds` | `30` |
| `AgentProperties.TelegramProperties.allowByDefault` | `false` |
| `AgentProperties.TelegramProperties.webhookSecret` | `""` |
| `AgentProperties.CoreProperties.maxTurns` | `100` |
| `AgentProperties.CoreProperties.toolUseEnforcement` | `"auto"` |
| `AgentProperties.CoreProperties.taskCompletionGuidance` | `true` |
| `AgentProperties.CoreProperties.parallelToolCallGuidance` | `true` |
| `AgentProperties.CoreProperties.autoTitleSession` | `true` |
| `AgentProperties.CoreProperties.reasoningConfig` | `"medium"` |
| `AgentProperties.CoreProperties.defaultSystemPrompt` | `"You are ${agent.name}. Use available tools when needed. Be concise. Return plain text unless JSON is requested."` |
| `AgentProperties.CoreProperties.httpClientTimeoutSeconds` | `30` |
| `AgentProperties.CoreProperties.maxReferenceFileBytes` | `100_000` |
| `AgentProperties.CoreProperties.workingDirectory` | `System.getProperty("user.dir")` |
| `AgentProperties.CoreProperties.codingContext` | `"off"` |
| `AgentProperties.CoreProperties.httpUserAgent` | `"AzhukovAgent/1.0"` |
| `AgentProperties.CoreProperties.soulMdPath` | `""` |
| `AgentProperties.CoreProperties.emptyResponseRetry` | `false` |
| `AgentProperties.CoreProperties.emptyBackoffBaseMs` | `5_000L` |
| `AgentProperties.CoreProperties.emptyBackoffCapMs` | `60_000L` |
| `AgentProperties.BudgetProperties.maxModelCallsPerTurn` | `100` |
| `AgentProperties.BudgetProperties.maxToolExecutionsPerTurn` | `100` |
| `AgentProperties.BudgetProperties.maxTokensPerTurn` | `200000` |
| `AgentProperties.BudgetProperties.maxToolDurationMsPerTurn` | `600000` |
| `AgentProperties.BudgetProperties.enabled` | `true` |
| `AgentProperties.BudgetProperties.runBudgetSeconds` | `0` |
| `AgentProperties.PromptCachingProperties.enabled` | `true` |
| `AgentProperties.PromptCachingProperties.trackStats` | `false` |
| `AgentProperties.CheckpointProperties.enabled` | `true` |
| `AgentProperties.CheckpointProperties.maxSnapshots` | `20` |
| `AgentProperties.CheckpointProperties.maxSizeMb` | `500` |
| `AgentProperties.VerifyOnStopProperties.enabled` | `false` |
| `AgentProperties.UsageProperties.trackEnabled` | `true` |
| `AgentProperties.UsageProperties.showCost` | `false` |
| `AgentProperties.UsageProperties.showTokenAnalytics` | `false` |
| `AgentProperties.ImageGenProperties.enabled` | `false` |
| `AgentProperties.ImageGenProperties.provider` | `"fal"` |
| `AgentProperties.ImageGenProperties.apiKey` | `<operator supplied>` |
| `AgentProperties.ImageGenProperties.model` | `""` |
| `AgentProperties.TtsProperties.enabled` | `false` |
| `AgentProperties.TtsProperties.provider` | `"edge"` |
| `AgentProperties.TtsProperties.apiKey` | `<operator supplied>` |
| `AgentProperties.TtsProperties.model` | `"gpt-4o-mini-tts"` |
| `AgentProperties.TtsProperties.voice` | `"alloy"` |
| `AgentProperties.TtsProperties.autoTts` | `false` |
| `AgentProperties.TtsProperties.EdgeProperties.command` | `""` |
| `AgentProperties.TtsProperties.EdgeProperties.voice` | `"ru-RU-DmitryNeural"` |
| `AgentProperties.TranscriptionProperties.enabled` | `false` |
| `AgentProperties.TranscriptionProperties.provider` | `"openai"` |
| `AgentProperties.TranscriptionProperties.apiKey` | `<operator supplied>` |
| `AgentProperties.TranscriptionProperties.model` | `"whisper-1"` |
| `AgentProperties.CronProperties.enabled` | `false` |
| `AgentProperties.CronProperties.maxParallelJobs` | `10` |
| `AgentProperties.CronProperties.dispatchIntervalSeconds` | `60` |
| `AgentProperties.CronProperties.nudgeFailureThreshold` | `3` |
| `AgentProperties.CronProperties.retryUnreachable` | `true` |
| `AgentProperties.ErrorProperties.retryAttempts` | `3` |
| `AgentProperties.ErrorProperties.availabilityRetryAttempts` | `20` |
| `AgentProperties.ErrorProperties.retryDelayMs` | `1000` |
| `AgentProperties.ErrorProperties.backoffMultiplier` | `2` |
| `AgentProperties.ErrorProperties.retryCapMs` | `120_000` |
| `AgentProperties.CodingContextProperties.enabled` | `true` |
| `AgentProperties.CodingContextProperties.minScore` | `0.5` |
| `AgentProperties.ToolProperties.managedGatewayEnabled` | `false` |
| `AgentProperties.CompressionProperties.enabled` | `true` |
| `AgentProperties.CompressionProperties.summaryChunkTokens` | `2000` |
| `AgentProperties.CompressionProperties.abortOnSummaryFailure` | `false` |
| `AgentProperties.CompressionProperties.summaryTimeoutSeconds` | `120` |
| `AgentProperties.CompressionProperties.totalCeilingSeconds` | `600` |
| `AgentProperties.CompressionProperties.SessionRotationProperties.enabled` | `true` |
| `AgentProperties.CuratorProperties.enabled` | `true` |
| `AgentProperties.CuratorProperties.intervalHours` | `24 * 7` |
| `AgentProperties.CuratorProperties.minIdleHours` | `2.0` |
| `AgentProperties.CuratorProperties.staleAfterDays` | `30` |
| `AgentProperties.CuratorProperties.archiveAfterDays` | `90` |
| `AgentProperties.CuratorProperties.pruneBuiltins` | `true` |
| `AgentProperties.CuratorProperties.dryRun` | `false` |
| `AgentProperties.CuratorProperties.backupKeep` | `5` |
| `AgentProperties.CuratorProperties.maxCuratorIterations` | `10` |
| `AgentProperties.ProfileProperties.name` | `"default"` |
| `AgentProperties.ProfileProperties.baseDir` | `""` |

### Telegram bot property fallbacks

Authoritative source: `telegram-bot/src/main/java/com/azhukov/agent/bot/config/BotProperties.java`.

| Field | Literal fallback |
|---|---|
| `BotProperties.token` | `<operator supplied>` |
| `BotProperties.mode` | `"polling"` |
| `BotProperties.agentName` | `"Джава агент"` |
| `BotProperties.backendUrl` | `"http://localhost:8090"` |
| `BotProperties.backendApiKey` | `""` |
| `BotProperties.maxMessageLength` | `4096` |
| `BotProperties.typingRefreshInterval` | `Duration.ofSeconds(4)` |
| `BotProperties.streamEditInterval` | `Duration.ofMillis(800)` |
| `BotProperties.bufferThreshold` | `24` |
| `BotProperties.busyMode` | `"queue"` |
| `BotProperties.busyInputMode` | `"interrupt"` |
| `BotProperties.busyAckEnabled` | `true` |
| `BotProperties.parseMode` | `"MarkdownV2"` |
| `BotProperties.registerCommands` | `true` |
| `BotProperties.rateLimitPerSecond` | `25` |
| `BotProperties.workingDirectory` | `System.getProperty("user.dir")` |
| `BotProperties.defaultModel` | `""` |
| `BotProperties.replyToMode` | `"first"` |
| `BotProperties.homeChatId` | `""` |
| `BotProperties.linkPreview` | `true` |
| `BotProperties.replaceOnStart` | `false` |
| `BotProperties.mediaDeliveryEnabled` | `true` |
| `BotProperties.streamingSilent` | `true` |
| `BotProperties.streamCursor` | `" ▉"` |
| `BotProperties.heartbeatIntervalSeconds` | `180` |
| `BotProperties.freshFinalTimeoutMs` | `60000` |
| `BotProperties.initialStreamText` | `""` |
| `BotProperties.streamingMaxChars` | `4096` |
| `BotProperties.streamingTransport` | `"edit"` |
| `BotProperties.cronDeliveryEnabled` | `true` |
| `BotProperties.GoalAutoContinue.enabled` | `false` |
| `BotProperties.GoalAutoContinue.maxTurns` | `3` |
| `BotProperties.SessionReset.mode` | `"both"` |
| `BotProperties.SessionReset.atHour` | `4` |
| `BotProperties.SessionReset.idleMinutes` | `1440` |
| `BotProperties.SessionReset.notify` | `true` |
| `BotProperties.Polling.timeoutSeconds` | `30` |
| `BotProperties.Polling.limit` | `100` |
| `BotProperties.Polling.reconnectDelayMs` | `5000` |
| `BotProperties.Polling.reconnectBackoffMultiplier` | `1.5` |
| `BotProperties.Polling.reconnectMaxDelayMs` | `60000` |
| `BotProperties.Polling.conflictMaxRetries` | `5` |
| `BotProperties.Webhook.url` | `""` |
| `BotProperties.Webhook.secret` | `<operator supplied>` |
| `BotProperties.Webhook.path` | `"/webhook/telegram"` |
| `BotProperties.Webhook.port` | `8443` |
| `BotProperties.Auth.allowByDefault` | `false` |
| `BotProperties.Pairing.enabled` | `false` |
| `BotProperties.Pairing.codeExpiryHours` | `1` |
| `BotProperties.Pairing.maxPending` | `3` |
| `BotProperties.Footer.enabled` | `false` |
| `BotProperties.Footer.fields` | `new ArrayList<>(List.of("model", "context_pct", "cwd"))` |
| `BotProperties.ReasoningDisplay.enabled` | `false` |
| `BotProperties.ReasoningDisplay.style` | `"code"` |
| `BotProperties.Reactions.enabled` | `false` |
| `BotProperties.TextBatch.delayMs` | `500` |
| `BotProperties.TextBatch.splitDelayMs` | `1200` |
| `BotProperties.TextBatch.fastDelayMs` | `180` |
| `BotProperties.Group.requireMention` | `false` |
| `BotProperties.Group.guestMode` | `false` |
| `BotProperties.Group.observeUnmentioned` | `false` |
| `BotProperties.Group.exclusiveBotMentions` | `false` |
| `BotProperties.DmTopic.chatId` | `""` |
| `BotProperties.DmTopic.topicName` | `""` |
| `BotProperties.DmTopic.threadId` | `null` |
| `BotProperties.DmTopic.iconColor` | `null` |
| `BotProperties.DmTopic.iconCustomEmojiId` | `null` |
| `BotProperties.DmTopic.skill` | `null` |
| `BotProperties.RichMessages.enabled` | `true` |
| `BotProperties.Display.toolProgress` | `"all"` |

### CLI property fallbacks

Authoritative source: `cli/src/main/java/com/azhukov/agent/cli/CliProperties.java`.

| Field | Literal fallback |
|---|---|
| `CliProperties.backendUrl` | `"http://localhost:8090"` |
| `CliProperties.newSession` | `false` |

## Direct Property Fallbacks

These settings are injected outside the three configuration-property classes.

| Property | Default | Source |
|---|---|---|
| `agent.delegate.stalled-after-seconds` | `900` seconds | `backend/src/main/java/com/azhukov/agent/service/StalledRunMonitor.java` |
| `agent.transcription.enabled` | `false` | `telegram-bot/src/main/java/com/azhukov/agent/bot/media/InboundMediaHandler.java` |
| `agent.tts.edge.command` | empty | `backend/src/main/java/com/azhukov/agent/service/tts/EdgeTtsProvider.java` |
| `agent.attachments.cache-root` | empty | `backend/src/main/java/com/azhukov/agent/service/AttachmentArtifactService.java` |
| `server.address` | empty | `backend/src/main/java/com/azhukov/agent/api/DashboardWebSocketGuard.java` |

## Maintenance Rule

Update this reference in the same commit as any changed default in `application.yml`, `AgentProperties`, `BotProperties`, `CliProperties`, a direct `@Value` expression, or an operational fixed default. Validate both the changed section and the full YAML inventory; a healthy process alone does not prove that the intended default won configuration precedence.

## Sources

- `backend/src/main/resources/application.yml`
- `telegram-bot/src/main/resources/application.yml`
- `cli/src/main/resources/application.yml`
- `backend/src/main/java/com/azhukov/agent/config/AgentProperties.java`
- `telegram-bot/src/main/java/com/azhukov/agent/bot/config/BotProperties.java`
- `cli/src/main/java/com/azhukov/agent/cli/CliProperties.java`
- `backend/src/main/java/com/azhukov/agent/core/tool/ClarifyGatewayStore.java`
- `shared/src/main/java/com/azhukov/agent/shared/http/BackendRestClientFactory.java`
- `telegram-bot/src/main/java/com/azhukov/agent/bot/core/MessageApiClient.java
