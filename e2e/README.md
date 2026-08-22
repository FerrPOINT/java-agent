# E2E scenarios

Declarative end-to-end tests against a RUNNING java-agent backend
(default `http://localhost:8090/api/v1`). No mocks: real model turns,
real DB, real tool executions.

## Run

```bash
python3 e2e/run_e2e.py                     # all scenarios
python3 e2e/run_e2e.py 01-core-chat.yaml   # selected
python3 e2e/run_e2e.py --base http://host:8090/api/v1
```

Requires: backend UP (health-gated), `pyyaml`, `requests`.

## Scenario format

```yaml
name: my-check
steps:
  - name: step label
    step: turn              # streaming chat turn (message → model)
    message: "Сделай X"     # optional sessionId: "{session_id}"
    assert: [{ var: turn.text, contains: "X" }]

  - name: api call
    method: POST            # default GET
    path: /agent/refine     # /v1/* paths bind to server root
    body: { sessionId: "{session_id}" }
    extract: { id: id }     # response.path → var
    assert:
      - { accepted: true }              # bare key → response.accepted == true
      - { path: message, contains: x }  # path + condition
      - { status: 200 }                 # HTTP status
```

Step kinds: `turn` (streaming chat), `wait_for` (poll path until assert
passes), `sleep`, `capture`/`set` (vars), default = HTTP request.

Conditions: eq ne contains not_contains gt gte lt lte matches exists
len_gte len_eq. Vars: `turn` (summary: text/toolNames/error), extracted
values, `session_id` (last turn's session), `status`, `response`.

## Coverage map

| File | Area |
|---|---|
| 01-core-chat | streaming turn, history persistence, session continuity |
| 02-terminal-tool | exit-code round-trip, failure output to model |
| 03-suggestions | catalog seed/list/dismiss-latch/re-seed |
| 04-heartbeat-loop | set/status/pause/resume, LOOP_COMPLETE auto-stop, result ack |
| 05-refine | background review trigger + focus + unknown session |
| 06-session-mgmt | context/usage/title/undo/compress/snapshot |
| 07-model-tools | model switch, toolsets, tools, capabilities, skills |
| 08-goal-subgoal | goal lifecycle + subgoal append semantics |
| 09-memory | statement → review → memory row |
| 10-cron-crud | cron job create/pause/resume/delete/404 |
| 11-guardrails | unknown sessions, malformed bodies, stop |
| 12-file-tools | write_file/read_file/search_files through the agent |
| 13-memory-skills | memory recall, skills_list, skill_view, skills REST, audit |
| 14-web-tools | web_search (live), web_extract (live page) |
| 15-coding-tools | execute_code (python), patch, delete_file |
| 16-todo-search | todo tool + plan endpoint, session_search recall |
| 17-checkpoint | checkpoint create/list/diff/restore/delete/404 |
| 18-conversation-control | branch, queue, steer, plan, context |
| 19-kanban-insights | kanban add/done, insights aggregation |
| 20-approvals | pending list, truthful capabilities, config |
| 21-browser-tools | live CDP: navigate, snapshot, back |
| 22-delegate-clarify | delegate_task subagent (real result), clarify |
| 23-disabled-caps | TTS/imagegen/vision honest disabled reporting |
| 24-openai-compat | /v1/models, /v1/chat/completions round-trip |
| 25-process-gateway | process listing, cronjob tool, mcp_tool guard |

Bugs this suite has caught so far (keep the cases that found them):
CLI /toolsets hitting a nonexistent path (404 forever), toolset
enable/disable endpoints missing entirely, /subgoal replace-instead-of-append
(Hermes always appends), cron double-delete returning 200 instead of 404,
model-switch 400 without an existing session, DUPLICATE AgentRuntime bean
breaking delegate_task subagents entirely ("expected single matching bean
but found 2"), mcp_tool leaking a raw ConcurrentHashMap NPE on null
server_name, /exit ERROR stacktrace in the CLI, missing @Component on three
CLI command groups (prod CLI silently lacked /learn /init /heartbeat
/loop /suggestions).
