# ADR-014: Plugin model scope — no dynamic JVM plugins in java-agent

Status: Accepted
Date: 2026-09-14
Wave: docs/35 WP-5

## Context

docs/35 WP-5 requires a decision on the plugin model before implementing
`PluginDashboardController`. Hermes mounts plugin-owned FastAPI routers under
`/api/plugins/<plugin>/...` and ships pip-installable plugins loaded into the
agent process. The Java port needs an equivalent-or-explicit decision.

## Decision

**java-agent does NOT support dynamic plugin loading.** The plugin surface
stays an explicit unsupported namespace:

1. **No arbitrary JVM bytecode** is loaded into the main process. Extending
   the agent happens through code changes in the repository (built-in
   tools/skills), not through runtime-loaded plugins.
2. `PluginDashboardController` remains the honest fallback: plugin routes
   answer 501/404 with an explicit "not supported" detail rather than
   pretending to be a functioning plugin host.
3. The integration seam that WOULD have been plugin-owned is **MCP** (WP-3):
   external capabilities attach as MCP servers with persisted, validated,
   revisioned config — not as in-process plugins.
4. Dashboard provider info (`/api/dashboard/plugins/hub`) reports the real
   built-in providers (memory provider, context compressor) only.

## Consequences

- No manifest/classloader/signature infrastructure to maintain or audit.
- `PluginDashboardController` 501s are **by-design** and excluded from the
  WP-12 "remove all 501" sweep.
- Skills remain the user-extension surface (DB + profile files, WP-5 hub
  installer with staged rollback).
- If in-process plugins become a real requirement, a new ADR must define the
  isolation boundary (separate process, typed route contract, permission
  model) before any implementation.

## Toolset catalog capability registry (same wave)

`ToolsetsController` now computes `available` from the live `ToolRegistry`:
a toolset is available only when it actually exposes registered tools.
Enabled-but-unregistered toolsets report `available=false` with
`unavailable_reason` instead of masquerading as functional. Explicitly
out-of-scope capabilities (video generation, Discord, Spotify, Home
Assistant, Yuanbao, cross-platform computer use) are not represented as
implemented functionality.
