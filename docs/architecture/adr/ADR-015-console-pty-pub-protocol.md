# ADR-015: Console task protocol — durable cursor, PTY and pub channel semantics

Status: Accepted
Date: 2026-09-14
Wave: docs/35 WP-9

## Context

The dashboard console surface (`/api/console`) runs guarded background
commands through `ProcessTool` with in-memory output rings. WebSocket output
streaming exists, but the Hermes protocol requires reconnect/cursor replay,
durable task state, cancellation confirmation, an interactive PTY lane and a
pub channel registry. docs/34 gap 1 tracks this as a route-shaped stub.

## Decision

1. **Durable console tasks (V61)**. `console_tasks` + `console_task_output`
   persist task identity, ownership (user/profile/session), guarded command,
   state machine (`running → completed|failed|cancelled|timeout`) and the
   redacted output with a monotonic per-task sequence. Reconnect replays
   strictly after the client cursor — no duplicates, no gaps. Retention
   (24h TTL + max rows per task) is swept on access.
2. **Execution stays behind the existing guards**. Commands pass
   `CommandGuard` + terminal safety before a task row is created; WebSocket
   input NEVER reaches a shell directly — interactive writes go through the
   same validated task/PTY boundary.
3. **PTY lane (`/api/console/pty`)**: pseudo-terminal sessions via
   `script -qfc <shell>` child processes with explicit workspace/cwd,
   ownership binding, input/resize/read/close operations, bounded
   binary-safe output rings, cursor reconnect and process-group cleanup on
   close/idle-timeout. PTY capability reports unavailable when the host has
   no `script` utility (fail-closed, no fallback to plain pipes).
4. **Pub channels**: named in-memory registry with per-channel ACL
   (profile/session scope), bounded retained-event rings and replay after
   cursor. `EventService` bridges agent events into subscribed channels;
   slow consumers drop oldest frames (documented backpressure) instead of
   growing unbounded queues. Restart semantics: pub is best-effort live
   transport — durable replay belongs to console tasks and the run event log.
5. **WebSocket auth**: the existing dashboard handshake guard (auth + Origin
   check) covers every new endpoint; authorization is re-checked after
   handshake on each frame (task ownership, channel ACL).

## Consequences

- Console tasks survive restarts; output replay is exact-by-sequence.
- PTY is real where the host allows it, honestly unavailable otherwise.
- Pub stays in-memory by design; no fake durability claims.
- Frame schema: `{"type":"output|exit|error|started","id":...,"seq":...,
  "lines":[...]}` with `seq` as the reconnect cursor.
