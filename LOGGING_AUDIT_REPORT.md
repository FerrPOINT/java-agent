# Logging Audit Report — java-agent Project

**Audit Date:** August 12, 2026  
**Scope:** `backend/src/main/java` and `telegram-bot/src/main/java`  
**Objective:** Identify all logging gaps — silent catches, missing logging on errors, wrong log levels, and missing logging configuration.

---

## Summary

| Severity | Count |
|----------|-------|
| CRITICAL | 6 |
| HIGH | 11 |
| MEDIUM | 8 |
| LOW | 9 |
| **Total** | **34** |

---

## CRITICAL — Silent Error Swallow

### C1. `SecretRedactor.java` — Custom regex pattern compilation failure silently swallowed
- **File:** `backend/src/main/java/com/azhukov/agent/security/SecretRedactor.java`
- **Line:** 37
- **Issue:** `catch (Exception ignored) {}` — when a custom secret redaction regex pattern fails to compile, the error is completely silently swallowed. A misconfigured pattern means secrets could be leaking into logs without the user knowing.
- **Fix:** `log.warn("Failed to compile custom secret redaction pattern '{}': {}", p, e.getMessage());`

### C2. `CheckpointManager.java` — `hashFile()` returns "ERROR" string without logging
- **File:** `backend/src/main/java/com/azhukov/agent/service/CheckpointManager.java`
- **Line:** 243–245
- **Issue:** `catch (Exception e) { return "ERROR"; }` — the exception is swallowed and a literal string "ERROR" is returned as a hash value. No log is emitted, and the caller has no way to distinguish a real hash from an error. This could lead to false "file changed" detections during restore.
- **Fix:** `log.warn("Failed to hash file {}: {}", file, e.getMessage()); return "ERROR";`

### C3. `CheckpointManager.java` — `setFilesJson` failure silently swallowed
- **File:** `backend/src/main/java/com/azhukov/agent/service/CheckpointManager.java`
- **Line:** 117–119
- **Issue:** `catch (Exception e) { entity.setFilesJson("[]"); }` — if JSON serialization of the files array fails, it silently falls back to "[]" with no log. This means a checkpoint could be saved with an empty file list, making it useless for restore/diff.
- **Fix:** `log.error("Failed to serialize checkpoint files JSON: {}", e.getMessage(), e); entity.setFilesJson("[]");`

### C4. `McpLifecycleManager.closeAll()` — Client close errors silently swallowed
- **File:** `backend/src/main/java/com/azhukov/agent/client/mcp/McpLifecycleManager.java`
- **Line:** 421
- **Issue:** `catch (Exception ignored) {}` — when shutting down MCP client connections, any close error is silently swallowed. This can mask resource leaks or incomplete cleanup.
- **Fix:** `log.debug("Error closing MCP client: {}", e.getMessage());`

### C5. `McpLifecycleManager.reconnect()` — Client close error silently swallowed
- **File:** `backend/src/main/java/com/azhukov/agent/client/mcp/McpLifecycleManager.java`
- **Line:** 292
- **Issue:** `catch (Exception ignored) {}` — when closing an existing MCP connection before reconnect, errors are silently swallowed.
- **Fix:** `log.debug("Error closing existing MCP client before reconnect: {}", e.getMessage());`

### C6. `FlywayConfig.isH2()` — SQLException silently swallowed
- **File:** `backend/src/main/java/com/azhukov/agent/config/FlywayConfig.java`
- **Line:** 34
- **Issue:** `catch (SQLException e) { return false; }` — if the database connection fails, it silently returns `false` (meaning "not H2"). This could cause Flyway to use PostgreSQL-specific init SQL on an H2 database, or vice versa, causing migration failures with no diagnostic information.
- **Fix:** `log.warn("Failed to determine database type: {}", e.getMessage()); return false;` (requires adding `@Slf4j` to the class)

---

## HIGH — Missing Logging on Important Operations

### H1. `GlobalExceptionHandler.handleValidation()` — No logging on validation errors
- **File:** `backend/src/main/java/com/azhukov/agent/api/GlobalExceptionHandler.java`
- **Line:** 76–86
- **Issue:** `MethodArgumentNotValidException` is handled and returned as a 400 response, but no log is emitted. Validation errors can indicate API misuse or attacks, and should be logged at DEBUG or WARN.
- **Fix:** Add `log.debug("Validation error: {}", errors);` or `log.warn("Validation error on request: {}", ex.getMessage());`

### H2. `GlobalExceptionHandler.handleConstraintViolation()` — No logging
- **File:** `backend/src/main/java/com/azhukov/agent/api/GlobalExceptionHandler.java`
- **Line:** 88–97
- **Issue:** `ConstraintViolationException` handler returns 400 without any logging.
- **Fix:** Add `log.warn("Configuration constraint violation: {}", details);`

### H3. `GlobalExceptionHandler.handleBadJson()` — No logging
- **File:** `backend/src/main/java/com/azhukov/agent/api/GlobalExceptionHandler.java`
- **Line:** 99–105
- **Issue:** `HttpMessageNotReadableException` (malformed JSON body) is handled without logging.
- **Fix:** Add `log.debug("Malformed JSON request body: {}", ex.getMessage());`

### H4. `GlobalExceptionHandler.handleIllegalArgument()` — No logging
- **File:** `backend/src/main/java/com/azhukov/agent/api/GlobalExceptionHandler.java`
- **Line:** 107–113
- **Issue:** `IllegalArgumentException` handler returns 400 without logging.
- **Fix:** Add `log.debug("Illegal argument in request: {}", ex.getMessage());`

### H5. `GlobalExceptionHandler.handleAgentException()` — No logging
- **File:** `backend/src/main/java/com/azhukov/agent/api/GlobalExceptionHandler.java`
- **Line:** 65–74
- **Issue:** `AgentException` (custom business logic exception) is handled without any logging. These are domain-specific errors that should be logged at WARN.
- **Fix:** Add `log.warn("Agent exception: {}", ex.getMessage());`

### H6. `GlobalExceptionHandler.isSseRequest()` — Exception silently swallowed
- **File:** `backend/src/main/java/com/azhukov/agent/api/GlobalExceptionHandler.java`
- **Line:** 42–44
- **Issue:** `catch (Exception e) { // Ignore }` — no log when failing to detect SSE request type.
- **Fix:** `log.debug("Failed to detect SSE request type: {}", e.getMessage());`

### H7. `ModelHealthIndicator` — No logging on health check failure
- **File:** `backend/src/main/java/com/azhukov/agent/health/ModelHealthIndicator.java`
- **Line:** 38–43
- **Issue:** When the model health check fails (model unreachable), the exception is caught and returned as `Health.down()` but no log is emitted. The health endpoint alone is insufficient — operators need log evidence.
- **Fix:** Add `@Slf4j` and `log.warn("Model health check failed: {}", e.getMessage());`

### H8. `BrowserHealthIndicator` — No logging on health check failure
- **File:** `backend/src/main/java/com/azhukov/agent/health/BrowserHealthIndicator.java`
- **Line:** 32–36
- **Issue:** Same as H7 — browser/CDP health check failure silently returns `Health.down()` with no log.
- **Fix:** Add `@Slf4j` and `log.warn("Browser/CDP health check failed: {}", e.getMessage());`

### H9. `BackgroundReviewService.executeWhitelistedTool()` — Tool execution error not logged
- **File:** `backend/src/main/java/com/azhukov/agent/core/memory/BackgroundReviewService.java`
- **Line:** 287–289
- **Issue:** `catch (Exception e) { return ToolResult.fail("Tool execution error: " + e.getMessage()); }` — the exception is caught and wrapped into a ToolResult but never logged. Failures in the self-improvement review loop are invisible.
- **Fix:** `log.warn("Background review tool '{}' execution failed: {}", call.name(), e.getMessage()); return ToolResult.fail(...);`

### H10. `AgentStreamingService.safeCompleteWithError()` — IllegalStateException silently swallowed
- **File:** `backend/src/main/java/com/azhukov/agent/service/AgentStreamingService.java`
- **Line:** 584–586
- **Issue:** `catch (IllegalStateException e) { // Emitter already completed — ignore }` — no log at all. While this is expected behavior (emitter already completed), a DEBUG log would help diagnose streaming lifecycle issues.
- **Fix:** `log.debug("SSE emitter already completed when trying to complete with error: {}", e.getMessage());`

### H11. `AgentStreamingService.send()` — IllegalStateException silently swallowed
- **File:** `backend/src/main/java/com/azhukov/agent/service/AgentStreamingService.java`
- **Line:** 596–597
- **Issue:** `catch (IllegalStateException e) { // Emitter already completed — ignore, don't log }` — explicitly says "don't log". Same as H10.
- **Fix:** `log.debug("SSE event not sent (emitter completed): {}", e.getMessage());`

---

## MEDIUM — Wrong Log Level

### M1. `DefaultAgentRuntime` — Memory hooks logged at DEBUG instead of WARN
- **File:** `backend/src/main/java/com/azhukov/agent/core/agent/DefaultAgentRuntime.java`
- **Lines:** 108, 146, 160, 171, 176, 428
- **Issue:** Multiple `log.debug(...)` for memory provider failures (onTurnStart, prefetch, syncTurn, queuePrefetch, onTurnStart prefetch, background review). Memory failures affect agent behavior — they should be at least WARN level so operators see them.
- **Fix:** Change all `log.debug(...)` to `log.warn(...)` for memory provider failures.

### M2. `MemoryManager` — All provider failures logged at DEBUG
- **File:** `backend/src/main/java/com/azhukov/agent/core/memory/MemoryManager.java`
- **Lines:** 104, 165, 227, 279, 300, 322, 343, 361, 367, 384, 406, 421, 438, 451, 464, 499, 524
- **Issue:** Every memory provider operation failure (handleToolCall, addProvider, getToolSchemas, prefetchAll, queuePrefetchAll, syncAll, onTurnStart, onSessionSwitch, onPreCompress, onDelegation, onMemoryWrite, initializeAll, flushPending, getSystemPrompt, shutdown) is logged at `DEBUG`. These are integration failures with external memory providers that affect agent recall capabilities.
- **Fix:** Use `log.warn(...)` for external provider failures, keep `DEBUG` only for builtin provider failures.

### M3. `McpLifecycleManager.scheduleToolRefresh()` — Tool refresh failure logged at DEBUG
- **File:** `backend/src/main/java/com/azhukov/agent/client/mcp/McpLifecycleManager.java`
- **Line:** 304
- **Issue:** `log.debug("Tool refresh for MCP server {} failed: {}", serverName, e.getMessage());` — scheduled tool refresh failures can mean stale tool definitions for hours. Should be WARN.
- **Fix:** `log.warn("Tool refresh for MCP server {} failed: {}", serverName, e.getMessage());`

### M4. `LangChain4jModelClient.persistUsage()` — Usage persistence failure logged at DEBUG
- **File:** `backend/src/main/java/com/azhukov/agent/client/langchain4j/LangChain4jModelClient.java`
- **Line:** 443
- **Issue:** `log.debug("Could not persist model usage: {}", e.getMessage());` — token usage tracking failures are operationally significant (billing, quota monitoring). Should be WARN.
- **Fix:** `log.warn("Could not persist model usage: {}", e.getMessage());`

### M5. `AgentStreamingService.sendMetadataEvent()` — Metadata send failure logged at DEBUG
- **File:** `backend/src/main/java/com/azhukov/agent/service/AgentStreamingService.java`
- **Line:** 479
- **Issue:** `log.debug("Failed to send stream metadata event: {}", e.getMessage());` — metadata event failures affect UI (context display, model info). While not critical, DEBUG makes it invisible in production (INFO level).
- **Fix:** `log.warn("Failed to send stream metadata event: {}", e.getMessage());`

### M6. `CronJobService.calculateDelaySeconds()` — `catch (Exception ignored)` at line 284
- **File:** `backend/src/main/java/com/azhukov/agent/service/CronJobService.java`
- **Line:** 284
- **Issue:** `catch (Exception ignored)` — when trying to parse a human-readable interval and failing, the exception is silently swallowed before falling through to cron expression parsing. This is expected flow control, but a DEBUG log would help diagnose misconfigured schedules.
- **Fix:** `log.debug("Schedule '{}' is not a human-readable interval, trying cron: {}", cronExpression, e.getMessage());`

### M7. `SkillBundleService` — IOException silently swallowed in mtime checks
- **File:** `backend/src/main/java/com/azhukov/agent/core/skill/SkillBundleService.java`
- **Lines:** 75, 86, 304, 310
- **Issue:** `catch (IOException ignored) {}` — four places where file modification time checks silently swallow IOException. While this is non-critical (mtime caching), a DEBUG log would help diagnose stale bundle cache issues.
- **Fix:** `log.debug("Could not read modification time: {}", e.getMessage());`

### M8. `DatabaseSkillManager.determineTrustLevelForSave()` — IllegalArgumentException silently swallowed
- **File:** `backend/src/main/java/com/azhukov/agent/core/skill/DatabaseSkillManager.java`
- **Line:** 392
- **Issue:** `catch (IllegalArgumentException ignored) {}` — when a stored trust level string doesn't match any enum value, the exception is silently swallowed and falls back to `AGENT_CREATED`. A corrupted trust level in the DB is a data integrity issue.
- **Fix:** `log.warn("Unknown trust level '{}' in database, defaulting to AGENT_CREATED", tl);`

---

## LOW — Minor Improvements

### L1. `DefaultUrlSafety.isEncodedPrivateIp()` — NumberFormatException silently ignored
- **File:** `backend/src/main/java/com/azhukov/agent/core/security/DefaultUrlSafety.java`
- **Lines:** 142, 161
- **Issue:** `catch (NumberFormatException e) { /* ignore */ }` — expected flow control for non-numeric IP parsing, but a TRACE/DEBUG log could help diagnose SSRF protection edge cases.
- **Fix:** Add `@Slf4j` and `log.trace("IP parsing failed for '{}': {}", host, e.getMessage());`

### L2. `AgentStreamingService` — `emitter.onError` callback only logs at WARN, no stack trace
- **File:** `backend/src/main/java/com/azhukov/agent/service/AgentStreamingService.java`
- **Line:** 136
- **Issue:** `log.warn("Stream error: {}", ex.getMessage());` — only logs the message, not the full exception. For diagnosing streaming issues, the stack trace is valuable.
- **Fix:** `log.warn("Stream error", ex);`

### L3. `AgentBackendClient` — All backend error responses logged at ERROR, but some are expected (4xx)
- **File:** `telegram-bot/src/main/java/com/azhukov/agent/bot/core/AgentBackendClient.java`
- **Lines:** Multiple (147, 207, 229, 251, 273, 298, etc.)
- **Issue:** All backend API failures are logged at `log.error(...)`, including client errors (400 Bad Request, 404 Not Found) which are expected operational errors, not system errors. Using ERROR for expected conditions pollutes error monitoring.
- **Fix:** Use `log.warn(...)` for 4xx errors and `log.error(...)` for 5xx and connection failures.

### L4. `TelegramClient` — All Telegram API failures logged at WARN, losing distinction
- **File:** `telegram-bot/src/main/java/com/azhukov/agent/bot/client/TelegramClient.java`
- **Lines:** Multiple (235, 317, 327, 354, 366, 396, etc.)
- **Issue:** Every Telegram API failure is `log.warn(...)`. Message-too-long (400) and chat-not-found (403) are different from rate-limiting (429) and server errors (5xx). Uniform WARN level makes it hard to prioritize.
- **Fix:** Use `log.debug(...)` for expected errors (message not modified, message too long) and `log.warn(...)` for unexpected ones.

### L5. `DefaultAgentRuntime.callModelWithRetry()` — InterruptedException break has no log
- **File:** `backend/src/main/java/com/azhukov/agent/core/agent/DefaultAgentRuntime.java`
- **Line:** 411–413
- **Issue:** `catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }` — the interruption is handled correctly but not logged. Makes it hard to trace why a model call retry loop was interrupted.
- **Fix:** `log.debug("Model call retry interrupted, stopping retries");`

### L6. `AgentBackendClient` — IOException in SSE watchdog silently swallowed
- **File:** `telegram-bot/src/main/java/com/azhukov/agent/bot/core/AgentBackendClient.java`
- **Line:** 421
- **Issue:** `catch (IOException ignored) {}` — when the SSE idle watchdog closes the reader, any IOException is silently swallowed. While expected (closing reader during readLine), a DEBUG log helps trace watchdog behavior.
- **Fix:** `log.debug("SSE watchdog reader close exception: {}", e.getMessage());`

### L7. No logback configuration in telegram-bot module
- **File:** `telegram-bot/src/main/resources/`
- **Issue:** The telegram-bot module has no `logback-spring.xml` file. It relies entirely on Spring Boot's default logging configuration. This means:
  - No secret/PII redaction in logs (the backend has `RedactingLogstashEncoder`/`RedactingLayout`)
  - No structured logging for production
  - No file-based logging configuration
- **Fix:** Create `telegram-bot/src/main/resources/logback-spring.xml` mirroring the backend's configuration, including the `RedactingLayout` for non-prod and `RedactingLogstashEncoder` for prod profiles.

### L8. Backend `logback-spring.xml` — No file appender configured
- **File:** `backend/src/main/resources/logback-spring.xml`
- **Issue:** Only a CONSOLE appender is configured. In production, logs typically need to go to files (or be collected by a log aggregation system). There's no async appender wrapping either, which could affect performance under heavy logging.
- **Fix:** Consider adding a file appender or confirming that container logging (stdout) is the intended production strategy. Add `<appender class="ch.qos.logback.classic.AsyncAppender">` wrapping the console appender for production.

### L9. `DefaultContextReferenceService` — NumberFormatException silently swallowed
- **File:** `backend/src/main/java/com/azhukov/agent/core/context/DefaultContextReferenceService.java`
- **Line:** 305
- **Issue:** `catch (NumberFormatException ignored) {}` — when parsing a line number from a reference specification fails, it's silently swallowed. While this is expected flow control (reference may not have line numbers), a DEBUG log helps diagnose malformed references.
- **Fix:** `log.debug("Reference line number parsing failed, treating as no line range: {}", e.getMessage());`

---

## Additional Observations

### A1. Consistent logging pattern in `AgentBackendClient`
The `AgentBackendClient` in telegram-bot has a very consistent pattern of `log.error(...)` in every catch block. While the logging is present (not a CRITICAL issue), the level is uniformly ERROR even for expected client errors. See L3 above.

### A2. `BotMessageProcessor` has good logging coverage
The `BotMessageProcessor` logs errors at the appropriate level with stack traces (`log.error("...", e.getMessage(), e)`) in all critical paths: update processing (line 129), command execution (line 183), backend call failure (line 377), queued message drain (line 402), streaming failure (line 527, 564), media file failure (line 797), error message send failure (line 817), TTS failure (line 849).

### A3. `LongPollingService` has good logging coverage
The polling service logs all error paths at appropriate levels: update parse errors (ERROR), update processing errors (ERROR), polling loop errors (WARN), conflict handling (WARN/ERROR), rate limiting (WARN).

### A4. `TelegramClient` has good logging coverage
Every API call failure logs at WARN with the method name and error message. The 429 rate limiting path has detailed logging including retry behavior.

### A5. `McpLifecycleManager` — reconnect close at line 292 and closeAll at line 421 are the only silent catches
The rest of the MCP lifecycle manager has good logging: connect failures (WARN), reconnect attempts (INFO/WARN), tool refresh failures (WARN), tool execution errors (WARN).

---

## Configuration Audit

### Backend Logging Configuration
- **`logback-spring.xml`:** Present and properly configured with:
  - Secret-redacting encoders for both prod (`RedactingLogstashEncoder`) and non-prod (`RedactingLayout`)
  - `com.azhukov.agent` package at INFO level
  - Root at INFO level
  - Profile-specific overrides in `application.yml` (DEBUG for dev, INFO for prod)

### Telegram-Bot Logging Configuration
- **`logback-spring.xml`:** **MISSING** — no logback configuration file exists
- **`application.yml`:** Has `logging.level.com.azhukov.agent.bot: DEBUG` for dev profile, but no prod-specific logging configuration
- **Secret redaction:** NOT configured — the `RedactingLayout`/`RedactingLogstashEncoder` classes are in the backend module and not available to the telegram-bot module
- **Recommendation:** Create `telegram-bot/src/main/resources/logback-spring.xml` with at minimum:
  ```xml
  <configuration>
    <property name="CONSOLE_LOG_PATTERN" 
              value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"/>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
      <encoder><pattern>${CONSOLE_LOG_PATTERN}</pattern></encoder>
    </appender>
    <logger name="com.azhukov.agent" level="INFO"/>
    <root level="INFO"><appender-ref ref="CONSOLE"/></root>
  </configuration>
  ```