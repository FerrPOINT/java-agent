# Портирование security/guardrails из Hermes

Цель: повторить в java-agent минимальный набор guardrails, достаточный для безопасного запуска инструментов и хранения сообщений.

## Python-источники

| Файл | Ответственность |
|---|---|
| `agent/tool_guardrails.py` | Loop-level guardrail controller, repeated-failure / no-progress detection |
| `agent/file_safety.py` | Write denylist, safe-root, read block for credentials, cross-profile warnings |
| `agent/redact.py` | Secret redaction in text, terminal output, URL params, headers, bodies |
| `agent/message_sanitization.py` | Sanitize prompt/response before API send and storage |
| `hermes_cli/input_sanitize.py` | User-input prompt sanitization (control chars, Unicode normalization) |
| `hermes_cli/urllib_security.py` | Credential-aware URL opener, private-IP / insecure-transport blocking |
| `tools/url_safety.py` | SSRF-safe HTTP client wrapper, URL redaction |
| `tools/approval.py` | Dangerous-command detection, approval gate, smart/cron/yolo modes |
| `tools/tirith_security.py` | Pre-exec command security scan via Tirith policy engine |
| `tools/file_tools.py`, `tools/terminal_tool.py` | Consumers of the validators above |
| `agent/tool_executor.py`, `run_agent.py`, `agent/conversation_loop.py` | Integration points |

## Java package layout

```
com.azhukov.agent.security
├── GuardrailDecision
├── GuardrailAction
├── GuardrailConfig
├── ToolCallGuardrail
├── FileSafetyValidator
├── CommandApprovalManager
├── ApprovalMode
├── SecretRedactor
├── MessageSanitizer
├── UserInputSanitizer
├── UrlSafetyHandler
└── SsrfSafeHttpClient
```

## Python → Java mapping

| Python | Java | Статус |
|---|---|---|
| `ToolCallGuardrailController` | `ToolCallGuardrail` | port |
| `ToolGuardrailDecision` | `GuardrailDecision` | port |
| `ToolCallGuardrailConfig` | `GuardrailConfig` | port |
| `agent/file_safety` | `FileSafetyValidator` | port |
| `tools/approval.py` | `CommandApprovalManager` + `ApprovalMode` | port |
| `tools/tirith_security.py` | `TirithSecurityScanner` (stub) | defer |
| `agent/redact.py` | `SecretRedactor` | port |
| `agent/message_sanitization.py` | `MessageSanitizer` | port |
| `hermes_cli/input_sanitize.py` | `UserInputSanitizer` | port |
| `hermes_cli/urllib_security.py` | `UrlSafetyHandler` | port |
| `tools/url_safety.py` | `SsrfSafeHttpClient` | port |
| `acp_adapter/edit_approval.py` | `EditApprovalManager` | defer |

## Интеграция с AgentRuntime / ToolRegistry

1. При инициализации `DefaultAgentRuntime` создаётся `ToolCallGuardrail` из `agent.tool-loop.guardrails`.
2. Перед вызовом инструмента:
   - plugin hook `resolve_pre_tool_block` (defer в MVP);
   - `guardrail.beforeCall(toolName, args)`;
   - при `block`/`halt` возвращается synthetic `ToolResult` без выполнения.
3. После вызова:
   - `guardrail.afterCall(toolName, args, result, failed)`;
   - при `warn`/`halt` добавляется observation и устанавливается halt-флаг для early turn exit.
4. `ReadFileTool`/`WriteFileTool`/`PatchTool` вызывают `FileSafetyValidator`.
5. `TerminalTool` вызывает `CommandApprovalManager` и `SecretRedactor`.
6. `WebSearchTool`/`WebExtractTool` используют `UrlSafetyHandler` + `SsrfSafeHttpClient`.
7. Все сообщения перед отправкой к LLM и перед сохранением проходят `MessageSanitizer` и `SecretRedactor`.
8. Пользовательский ввод перед `AgentRuntime.runTurn` проходит `UserInputSanitizer`.

## Конфигурация

| Параметр | Тип | Смысл |
|---|---|---|
| `agent.tool-loop.guardrails.warnings-enabled` | boolean | включить warnings |
| `agent.tool-loop.guardrails.hard-stop-enabled` | boolean | включить halt |
| `agent.tool-loop.guardrails.warn-after.exact-failure` | int | warn после N подряд ошибок |
| `agent.tool-loop.guardrails.warn-after.same-tool-failure` | int | warn после N ошибок одного инструмента |
| `agent.tool-loop.guardrails.warn-after.idempotent-no-progress` | int | warn при отсутствии прогресса |
| `agent.tool-loop.guardrails.hard-stop-after.*` | int | halt thresholds |
| `security.allow-private-urls` | boolean | разрешить приватные IPs |
| `security.redact-secrets` | boolean | включить маскирование |
| `security.tirith-enabled` | boolean | включить Tirith (defer) |
| `security.website-blocklist` | List<String> | заблокированные хосты |
| `approvals.mode` | String | smart/cron/yolo |
| `approvals.timeout-seconds` | int | таймаут ожидания подтверждения |
| `command-allowlist` | List<String> | постоянный allowlist |

## Critical invariants

- Guardrails default to **fail-closed** (block unknown / no allow-all by default).
- Approval gate never blocks pure read-only commands unless they touch sensitive paths.
- Secret redaction runs on tool output **before** it enters context / logs.
- No user prompt passes to the model without `UserInputSanitizer`.
- URL fetch never contacts private-IP ranges unless explicitly allowed.

## Рекомендуемый порядок

1. `GuardrailDecision`, `GuardrailConfig`, `ToolCallGuardrail` (no-op/transparent).
2. `SecretRedactor` + `MessageSanitizer`.
3. `FileSafetyValidator` + `UserInputSanitizer`.
4. `UrlSafetyHandler` + `SsrfSafeHttpClient`.
5. `CommandApprovalManager` (initially `yolo`/`smart` modes).
6. `TirithSecurityScanner` stub (defer).
