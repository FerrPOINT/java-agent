#!/usr/bin/env python3
"""CLI e2e: drive the interactive java-agent CLI over stdin, verify output.

Each case: a list of lines fed to the REPL, plus expected substrings in the
combined stdout. Runs against the LIVE backend (health-gated).
"""
import subprocess
import sys
import time
import uuid
import requests

CLI_JAR = "/opt/java-agent/lib/java-agent-cli.jar"
WORKDIR = "/tmp/cli-e2e"
TIMEOUT = 180

CASES = [
    # (name, [lines...], [expected substrings...])
    ("help", ["/help", "/exit"], ["Available Commands", "/heartbeat", "/toolsets"]),

    ("health+version", ["/health", "/version", "/exit"], ["UP", "Java", "Backend"]),

    ("session-status", ["/status", "/exit"], ["Session"]),

    ("goal-lifecycle", [
        "/goal e2e-cli: verify goal lifecycle",
        "/goal",
        "/subgoal e2e-cli subgoal criterion",
        "/goal pause",
        "/goal resume",
        "/goal clear",
        "/exit",
    ], ["goal", "subgoal", "paused", "cleared"]),

    ("model-info", ["/model", "/exit"], ["app-test", "model"]),

    ("model-switch-same", ["/model app-test", "/model", "/exit"],
     ["app-test"]),

    ("toolsets-list", ["/toolsets", "/exit"], ["skills", "terminal"]),

    ("tools-list", ["/tools", "/exit"], ["terminal"]),

    ("skills-list", ["/skills", "/exit"], ["Skill"]),

    ("cron-list", ["/cron", "/exit"], []),

    ("suggestions", ["/suggestions seed", "/suggestions", "/exit"], ["suggestion", "Suggestion", "Seeded"]),

    ("memory-show", ["/memory", "/exit"], []),

    ("context", ["/context", "/exit"], ["sessionId", "messageCount", "tokenEstimate"]),

    ("history", ["/history", "/exit"], []),

    ("usage", ["/usage", "/exit"], []),

    ("credits", ["/credits", "/exit"], []),

    ("title", ["/title e2e-cli-title", "/status", "/exit"], []),

    ("heartbeat-set-clear", [
        "/heartbeat 5m e2e probe: answer with nothing changed",
        "/heartbeat",
        "/heartbeat clear",
        "/exit",
    ], ["Heartbeat", "heartbeat"]),

    ("undo", ["/undo", "/exit"], []),

    ("compress", ["/compress", "/exit"], ["compress", "Compress"]),

    ("checkpoint", ["/checkpoint e2e cp", "/checkpoints", "/exit"],
     ["checkpoint", "Checkpoint"]),

    ("save", ["/save", "/exit"], ["session"]),

    ("fast-mode", ["/fast", "/fast", "/exit"], []),

    ("personality", ["/personality terse and precise", "/exit"], ["personality", "Personality"]),

    ("approvals", ["/approvals", "/exit"], []),

    ("doctor", ["/doctor", "/exit"], []),

    ("insights", ["/insights", "/exit"], []),

    ("kanban", ["/kanban list", "/exit"], []),

    ("queue-empty", ["/queue", "/exit"], ["No prompt queued"]),

    ("learn-usage", ["/learn", "/exit"], ["learn", "Learn"]),

    ("init-usage", ["/init", "/exit"], ["init", "Init"]),

    ("refine", [
        "Ответь одним словом: ок",
        "/refine",
        "/exit",
    ], ["Reviewing this conversation in the background"]),

    ("bad-command", ["/nosuchcommand123", "/exit"],
     ["Unknown command", "unknown"]),

    ("chat-turn", ["Ответь ровно одним словом: ок", "/exit"], ["ок", "Ок", "ОК"]),

    ("tool-turn", ["Выполни через терминал: echo cli-e2e-77 && скажи вывод", "/exit"],
     ["cli-e2e-77"]),
]


def run_case(name, lines, expected):
    stdin = "\n".join(lines) + "\n"
    t0 = time.time()
    try:
        proc = subprocess.run(
            ["java", "-jar", CLI_JAR, "--new-session"],
            input=stdin, capture_output=True, text=True, timeout=TIMEOUT,
            cwd=WORKDIR,
        )
        out = proc.stdout + proc.stderr
    except subprocess.TimeoutExpired as e:
        return False, f"TIMEOUT after {TIMEOUT}s"
    dt = time.time() - t0

    # strip ansi
    import re
    out_clean = re.sub(r"\x1b\[[0-9;?]*[a-zA-Z]", "", out)

    missing = [exp for exp in expected if exp.lower() not in out_clean.lower()]
    # exit-error regression guard: /exit must NOT log a stacktrace
    exit_bug = "Command /exit failed" in out_clean
    if exit_bug:
        return False, "/exit logged as ERROR (regression of the graceful-exit fix)"
    if missing:
        return False, f"missing: {missing} (output tail: {out_clean[-300:]!r})"
    return True, f"{dt:.1f}s"


def main():
    try:
        h = requests.get("http://localhost:8090/actuator/health", timeout=60).json()
        assert h["status"] == "UP"
        print("backend health: UP")
    except Exception as e:
        print(f"BACKEND DOWN: {e}")
        return 2

    only = sys.argv[1:] if len(sys.argv) > 1 else None
    passed, failed = 0, []
    for name, lines, expected in CASES:
        if only and name not in only:
            continue
        ok, detail = run_case(name, lines, expected)
        mark = "✓" if ok else "✗"
        print(f"  {mark} {name} — {detail}")
        if ok:
            passed += 1
        else:
            failed.append(name)

    total = passed + len(failed)
    print(f"\nCLI RESULT: {passed}/{total} cases passed")
    if failed:
        print("FAILED:", ", ".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
