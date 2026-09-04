# ADR-009: Micrometer Metrics with Prometheus

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2025-08-12 |
| **Deciders** | Project lead |
| **Tags** | observability, metrics, monitoring |

## Context

The agent had no structured metrics. Monitoring was limited to log analysis, which is slow and error-prone. We needed:

1. Standard counters/timers for key operations (chat requests, tool executions, model calls).
2. Prometheus-compatible exposition format for scraping by Prometheus/Grafana.
3. Low overhead — metrics should not impact request latency.
4. Integration with Spring Boot Actuator (existing health/info endpoints).

Options considered:

1. **No metrics** — relying on logs only. Insufficient for production monitoring.
2. **Custom metrics endpoint** — hand-rolled counters exposed as JSON. Reinvents the wheel.
3. **Micrometer + Prometheus registry** — industry standard, Spring Boot integration, low overhead.

## Decision

Add `micrometer-registry-prometheus` dependency and `AgentMetrics` class. Key metrics:

- `agent.chat.requests` (counter) — total chat requests.
- `agent.chat.streaming` (counter) — streaming chat requests.
- `agent.tool.executions` (counter) — tool executions.
- `agent.model.calls` (counter) — LLM API calls.

Prometheus endpoint exposed at `/actuator/prometheus`. Metrics are auto-registered via Spring Boot Actuator integration.

## Consequences

**Positive:**

- Standard Prometheus scraping — works with existing Grafana dashboards.
- Low overhead (Micrometer is optimized for minimal allocation).
- Counters/timers are composable and taggable.
- Spring Boot Actuator integration is automatic.

**Negative:**

- Additional dependency (~500KB).
- Metrics naming conventions must be followed consistently.
- Cardinality risk if too many tag combinations are used (e.g., per-session tags).
- Prometheus is pull-only — for push-based monitoring, additional setup is needed.
