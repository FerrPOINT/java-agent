#!/usr/bin/env python3
"""E2E scenario runner for the running java-agent backend.

Scenarios live in e2e/scenarios/*.yaml (declarative: steps with requests,
extracts, asserts). The runner executes them against BASE (default
http://localhost:8090/api/v1) and prints a per-step PASS/FAIL report.

Usage:
  e2e/run_e2e.py                        # run everything in e2e/scenarios/
  e2e/run_e2e.py smoke chat.yaml        # run selected files
  e2e/run_e2e.py --base http://host:8090/api/v1
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
import traceback
import uuid
from pathlib import Path

import requests
import yaml

HERE = Path(__file__).parent
DEFAULT_BASE = "http://localhost:8090/api/v1"
BOT_BASE = os.getenv("E2E_BOT_BASE", "http://localhost:18091")
SSE_TIMEOUT = 180          # seconds for a full streaming turn
POLL_TIMEOUT = 120         # seconds for wait_for steps


# ─────────────────────────────────────────────────────────────────────
# Tiny assertion helpers
# ─────────────────────────────────────────────────────────────────────

class StepFailure(AssertionError):
    pass


def json_path(data, path: str):
    """Dot path with [index]/[*] and bare [N] roots: items[0].id, [0].role."""
    cur = data
    # tokenize: key, [N], [*] in order
    tokens = re.findall(r"\w+|\[\*?\d*\]", str(path))
    for tok in tokens:
        if tok.startswith("["):
            inner = tok[1:-1]
            if not isinstance(cur, list):
                raise StepFailure(f"path {path}: list index on {type(cur).__name__}")
            if inner == "*":
                continue
            i = int(inner)
            if i >= len(cur):
                raise StepFailure(f"path {path}: index {i} out of range ({len(cur)} items)")
            cur = cur[i]
        else:
            if not isinstance(cur, dict) or tok not in cur:
                raise StepFailure(
                    f"path {path}: key {tok!r} missing in {json.dumps(cur, default=str)[:200]}")
            cur = cur[tok]
    return cur


def check_condition(value, cond: str, expected):
    """Evaluate one assertion condition."""
    if cond == "eq":
        assert value == expected, f"expected {expected!r}, got {value!r}"
    elif cond == "ne":
        assert value != expected, f"expected != {expected!r}, got {value!r}"
    elif cond == "contains":
        if isinstance(value, list):
            assert expected in value, f"{expected!r} not in list ({len(value)} items)"
        else:
            assert expected in str(value), f"{expected!r} not in {str(value)[:200]!r}"
    elif cond == "not_contains":
        assert expected not in str(value), f"{expected!r} unexpectedly in {str(value)[:200]!r}"
    elif cond == "gt":
        assert value > expected, f"expected > {expected}, got {value}"
    elif cond == "gte":
        assert value >= expected, f"expected >= {expected}, got {value}"
    elif cond == "lt":
        assert value < expected, f"expected < {expected}, got {value}"
    elif cond == "lte":
        assert value <= expected, f"expected <= {expected}, got {value}"
    elif cond == "matches":
        assert re.search(expected, str(value)), f"{expected!r} doesn't match {str(value)[:120]!r}"
    elif cond == "exists":
        assert value is not None, f"expected non-null at path"
    elif cond == "len_gte":
        assert len(value) >= expected, f"expected len >= {expected}, got {len(value)}"
    elif cond == "len_eq":
        assert len(value) == expected, f"expected len == {expected}, got {len(value)}"
    else:
        raise StepFailure(f"unknown condition: {cond}")


# ─────────────────────────────────────────────────────────────────────
# SSE / streaming helpers
# ─────────────────────────────────────────────────────────────────────

def run_streaming_turn(base: str, body: dict, timeout: int = SSE_TIMEOUT) -> dict:
    """POST /agent/chat/stream, collect tokens/tool events; return summary."""
    tokens, tool_calls, tool_results = [], [], []
    session_id, error = None, None
    r = requests.post(f"{base}/agent/chat/stream", json=body, stream=True,
                      timeout=timeout)
    r.raise_for_status()
    for line in r.iter_lines(decode_unicode=True):
        if not line or not line.startswith("data:"):
            continue
        try:
            d = json.loads(line[5:])
        except json.JSONDecodeError:
            continue
        t = d.get("type")
        if t == "metadata" and d.get("sessionId"):
            session_id = d["sessionId"]
        elif t == "token" and d.get("token"):
            tokens.append(d["token"])
        elif t == "tool_calls":
            tool_calls.extend(d.get("toolCalls") or [])
        elif t == "tool_result":
            tool_results.append(d.get("toolResult"))
        elif t == "error":
            error = d.get("error")
    return {
        "sessionId": session_id,
        "text": "".join(tokens),
        "toolNames": [tc.get("name") for tc in tool_calls],
        "toolResultCount": len(tool_results),
        "error": error,
    }


def wait_for(fn, timeout: int = POLL_TIMEOUT, interval: float = 2.0):
    deadline = time.time() + timeout
    last_exc = None
    while time.time() < deadline:
        try:
            return fn()
        except Exception as e:  # noqa: BLE001 - poll until timeout
            last_exc = e
            time.sleep(interval)
    raise StepFailure(f"wait_for timed out after {timeout}s: {last_exc}")


# ─────────────────────────────────────────────────────────────────────
# Step execution
# ─────────────────────────────────────────────────────────────────────

class Runner:
    def __init__(self, base: str):
        self.base = base
        self.vars: dict[str, object] = {"bot_base": BOT_BASE}
        self.session_ids: list[str] = []

    def subst(self, value):
        """Replace {var} placeholders from the vars store."""
        if isinstance(value, str):
            for k, v in self.vars.items():
                value = value.replace("{" + k + "}", str(v))
            return value
        if isinstance(value, dict):
            return {k: self.subst(v) for k, v in value.items()}
        if isinstance(value, list):
            return [self.subst(v) for v in value]
        return value

    def do_request(self, step: dict):
        method = step.get("method", "GET").upper()
        path = str(self.subst(step["path"]))
        # Explicit absolute URLs support E2E checks for separate adapters
        # (for example the Telegram bot on port 8091).
        if path.startswith("http://") or path.startswith("https://"):
            url = path
        # /v1/* and /api/v2/* controllers live outside the /api/v1 prefix.
        elif path.startswith("/v1/") or path.startswith("/api/"):
            url = self.base.rsplit("/api/v1", 1)[0] + path
        else:
            url = self.base + path
        body = self.subst(step.get("body", {}))
        params = self.subst(step.get("params", {}))
        headers = self.subst(step.get("headers", {}))
        timeout = step.get("timeout", 30)
        r = requests.request(method, url, json=body or None, params=params or None,
                             headers=headers or None, timeout=timeout)
        # Store basics even on failure so asserts can inspect status
        self.vars["status"] = r.status_code
        self.vars["response"] = safe_json(r)
        self.track_session_in_response(self.vars["response"])
        return r

    def track_session_in_response(self, response):
        """Track any sessionId the backend reports — covers branch sessions,
        compression rotation (new id after /compress) and background forks;
        all of them must be cleaned up, not just the turn's session."""
        if not isinstance(response, dict):
            return
        for key in ("sessionId", "session_id", "id"):
            v = response.get(key)
            if isinstance(v, str) and len(v) == 36 and v.count("-") == 4:
                self.session_ids.append(v)

    def do_turn(self, step: dict):
        """Streaming chat turn; message + optional sessionId; captures summary."""
        msg = self.subst(step["message"])
        sid = self.subst(step.get("sessionId", "")) or None
        body = {"sessionId": sid or str(uuid.uuid4()), "message": msg}
        summary = run_streaming_turn(self.base, body, timeout=step.get("timeout", SSE_TIMEOUT))
        if summary["sessionId"]:
            self.vars["session_id"] = summary["sessionId"]
            self.session_ids.append(summary["sessionId"])
        self.vars["turn"] = summary
        self.vars["status"] = 200
        return summary

    def run_step(self, step: dict, idx: int):
        kind = step.get("step") or ("request" if "path" in step else "turn")
        label = step.get("name", f"step {idx}")

        if kind == "turn":
            summary = self.do_turn(step)
            return label, summary
        if kind == "wait_for":
            target = step["path"]
            cond = step.get("assert", {})
            def probe():
                self.do_request({"path": target})
                self.check_asserts(cond, label)
                return self.vars["response"]
            wait_for(probe, timeout=step.get("timeout", POLL_TIMEOUT))
            return label, self.vars["response"]
        if kind == "sleep":
            time.sleep(step.get("seconds", 1))
            return label, {"slept": step.get("seconds", 1)}
        if kind == "capture":
            value = self.subst(step["value"])
            self.vars[step["as"]] = value
            return label, {step["as"]: value}
        if kind == "set":
            self.vars[step["as"]] = step["value"]
            return label, {step["as"]: step["value"]}

        # default: plain request
        r = self.do_request(step)
        if "assert" in step:
            self.check_asserts(step["assert"], label)
        if "extract" in step:
            self.do_extracts(step["extract"])
        return label, self.vars.get("response")

    def check_asserts(self, asserts: dict | list, label: str):
        items = asserts if isinstance(asserts, list) else [asserts]
        for a in items:
            if "path" in a:
                value = json_path(self.vars["response"], a["path"])
            elif "var" in a:
                value = self.vars.get(a["var"])
            elif "status" in a:
                value = self.vars.get("status")   # bare 'status' → HTTP status
                check_condition(value, "eq", a["status"])
                continue
            elif len(a) == 1:
                # single-key assert {dismissed: true} → response.dismissed eq true
                path, expected = next(iter(a.items()))
                value = json_path(self.vars["response"], str(path))
                check_condition(value, "eq", expected)
                continue
            else:
                value = self.vars["response"]
            for cond, expected in a.items():
                if cond in ("path", "var", "name"):
                    continue
                check_condition(value, cond, expected)

    def do_extracts(self, extracts: dict):
        for path, var in extracts.items():
            value = json_path(self.vars["response"], path)
            self.vars[var] = value
            # Track every session id extracted from a response (branch/fork
            # sessions have their own id — cleanup must cover them too).
            if var in ("id", "session_id", "sessionId"):
                s = value if isinstance(value, str) else None
                if s is not None and len(s) == 36 and s.count("-") == 4:
                    self.session_ids.append(s)

    def run_scenario(self, file: Path) -> tuple[bool, list[str]]:
        scenario = yaml.safe_load(file.read_text())
        name = scenario.get("name", file.stem)
        steps = scenario.get("steps", [])
        print(f"\n▶ {name}  [{file.name}, {len(steps)} steps]")
        log: list[str] = []
        ok = True
        try:
            for i, step in enumerate(steps, 1):
                label = step.get("name", f"step {i}")
                t0 = time.time()
                try:
                    _, detail = self.run_step(step, i)
                    dt = time.time() - t0
                    brief = summarize(detail)
                    print(f"  ✓ {label} ({dt:.1f}s) {brief}")
                    log.append(f"PASS {label}")
                except Exception as e:  # noqa: BLE001
                    ok = False
                    dt = time.time() - t0
                    print(f"  ✗ {label} ({dt:.1f}s): {e}")
                    log.append(f"FAIL {label}: {e}")
                    if not scenario.get("continue_on_error"):
                        break
        finally:
            self.cleanup_sessions()
        return ok, log

    def cleanup_sessions(self):
        """Delete every session this scenario created — e2e runs must not
        litter the live DB (961 orphan 'New chat' sessions accumulated before
        this guard existed). Failures here are logged, never fatal."""
        root = self.base.rsplit("/api/v1", 1)[0]
        for sid in self.session_ids:
            try:
                r = requests.delete(f"{root}/api/v2/sessions/{sid}", timeout=15)
                if r.status_code not in (200, 404):
                    print(f"  ⚠ cleanup: session {sid} -> HTTP {r.status_code}")
            except Exception as e:  # noqa: BLE001
                print(f"  ⚠ cleanup: session {sid} failed: {e}")
        # Rotation-tail sweep: mid-turn compression rotates the session; the
        # metadata id of the LAST turn is tracked, but earlier links in the
        # chain (or a rotation on the very first turn) can escape. Rotation
        # children are recognizable by the " (compressed)" title suffix.
        try:
            for _ in range(4):  # chains are shallow
                r = requests.get(f"{root}/api/v2/sessions?limit=200", timeout=15)
                r.raise_for_status()
                victims = [s["id"] for s in r.json().get("data", [])
                           if isinstance(s.get("title"), str)
                           and s["title"].endswith(" (compressed)")]
                if not victims:
                    return
                for v in victims:
                    requests.delete(f"{root}/api/v2/sessions/{v}", timeout=15)
        except Exception as e:  # noqa: BLE001
            print(f"  ⚠ rotation sweep failed: {e}")


def safe_json(r) -> object:
    try:
        return r.json()
    except Exception:  # noqa: BLE001
        return {"_raw": r.text[:500], "_status": r.status_code}


def summarize(detail) -> str:
    if isinstance(detail, dict):
        if "text" in detail:
            t = detail["text"].replace("\n", " ")
            tools = ",".join(filter(None, detail.get("toolNames", [])))
            s = f"→ {t[:80]!r}"
            if tools:
                s += f" [tools: {tools}]"
            if detail.get("error"):
                s += f" ERR={detail['error']}"
            return s
        if "status" in detail and len(str(detail)) < 100:
            return str(detail)[:80]
    return ""


def main(argv):
    base = DEFAULT_BASE
    args = argv[1:]
    if "--base" in args:
        i = args.index("--base")
        base = args[i + 1]
        args = args[:i] + args[i + 2:]

    files = [HERE / "scenarios" / a for a in args] if args else \
        sorted((HERE / "scenarios").glob("*.yaml"))
    if not files:
        print("no scenarios found in", HERE / "scenarios")
        return 2

    # health gate
    try:
        h = requests.get(base.rsplit("/api/v1", 1)[0] + "/actuator/health/readiness", timeout=15).json()
        assert h.get("status") == "UP", h.get("status")
        print(f"backend health: UP ({base})")
    except Exception as e:  # noqa: BLE001
        print(f"BACKEND DOWN: {e}")
        return 2

    results, all_logs = {}, []
    for f in files:
        runner = Runner(base)
        try:
            ok, log = runner.run_scenario(f)
        except Exception:  # noqa: BLE001
            ok, log = False, [traceback.format_exc()]
        results[f.name] = ok
        all_logs.extend(log)

    print("\n" + "═" * 60)
    failed = [n for n, ok in results.items() if not ok]
    print(f"RESULT: {len(results) - len(failed)}/{len(results)} scenarios passed")
    final_sweep(base)
    if failed:
        print("FAILED:", ", ".join(failed))
        return 1
    return 0


def final_sweep(base: str) -> None:
    """Post-suite backstop: background-review forks (refine/learn) create
    their sessions ASYNC, after the per-scenario cleanup already ran. Delete
    every remaining e2e session (user_id 'user-1' is the runner's identity —
    the real Telegram user owns '754334329')."""
    root = base.rsplit("/api/v1", 1)[0]
    try:
        r = requests.get(f"{root}/api/v2/sessions?limit=200&userId=user-1", timeout=15)
        r.raise_for_status()
        victims = [s["id"] for s in r.json().get("data", [])]
        for v in victims:
            requests.delete(f"{root}/api/v2/sessions/{v}", timeout=15)
        if victims:
            print(f"  ⚙ final sweep: {len(victims)} background-fork session(s) removed")
    except Exception as e:  # noqa: BLE001
        print(f"  ⚠ final sweep failed: {e}")


if __name__ == "__main__":
    sys.exit(main(sys.argv))
