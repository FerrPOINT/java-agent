#!/bin/sh
# Parent-death watchdog supervisor for stdio MCP subprocesses (WP-i,
# Hermes tools/mcp_stdio_watchdog.py parity).
#
# Problem: a stdio MCP server is spawned as a direct child of the JVM. The
# graceful closeAll() path reaps it, but a hard parent death (kill -9, crash,
# power loss) never runs teardown and orphans the server (plus its own
# descendants) until the next startup sweep at best.
#
# Fix: interpose this tiny POSIX sh supervisor between the agent and the
# real command. It:
#   1. spawns the real command in its OWN process group (setsid) so a
#      group-kill reaches the whole descendant tree the command spawns
#      (e.g. npx -> node);
#   2. relays stdin/stdout/stderr transparently (the MCP stdio protocol
#      talks over those pipes — the supervisor must be a no-op relay);
#   3. polls the direct POSIX parent identity; the instant the original
#      parent is gone (re-parented to init), it SIGTERMs the child group,
#      waits a bounded grace period, then SIGKILLs and exits.
#
# Gated to POSIX by the caller (McpLifecycleManager.createStdioClient).
# Standard utilities only (sh + ps + sleep + kill + setsid).

[ $# -ge 3 ] || { echo "mcp-stdio-watchdog: usage: --ppid <pid> -- <command...>" >&2; exit 2; }
[ "$1" = "--ppid" ] || { echo "mcp-stdio-watchdog: expected --ppid" >&2; exit 2; }
ppid_orig=$2
shift 2
[ "$1" = "--" ] && shift

POLL_INTERVAL=2
TERM_GRACE=3

kill_child_group() {
    kill -TERM "-$child" 2>/dev/null
    i=$TERM_GRACE
    while [ $i -gt 0 ] && kill -0 "$child" 2>/dev/null; do
        sleep 1
        i=$((i - 1))
    done
    kill -KILL "-$child" 2>/dev/null
}

# Own session+group: killpg reaches the whole tree the real command spawns
# without touching our own group or the parent's.
setsid "$@" &
child=$!
trap 'kill_child_group; exit 143' TERM INT

# Parent identity poll: once our original parent exits we are re-parented
# (ppid changes) — kill the child's group and leave.
while kill -0 "$child" 2>/dev/null; do
    cur_ppid=$(ps -o ppid= -p $$ 2>/dev/null | tr -d ' ')
    if [ -n "$cur_ppid" ] && [ "$cur_ppid" != "$ppid_orig" ]; then
        kill_child_group
        exit 0
    fi
    sleep $POLL_INTERVAL
done

wait "$child"
exit $?
