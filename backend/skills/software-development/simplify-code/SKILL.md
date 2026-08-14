---
name: simplify-code
description: "Parallel 3-agent cleanup of recent code changes."
version: 1.0.0
author: Hermes Agent (adapted)
license: MIT
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [code-review, cleanup, refactor, delegation, subagent, parallel, simplify]
    related_skills: [test-driven-development, plan]
---

# Simplify Code — Parallel Review & Cleanup

Review your recent code changes with three focused reviewers running in
parallel, aggregate their findings, and apply the fixes worth applying.

**Core principle:** Three narrow reviewers beat one broad reviewer.

## When to Use

Trigger this skill when the user says any of:
- "simplify" / "simplify my changes" / "simplify these changes"
- "review my code" / "review my recent changes" / "clean up my changes"

## The Process

### Phase 1 — Identify the changes

Capture the diff to review:
```bash
git diff          # Default: uncommitted working-tree changes
git diff HEAD     # If that's empty, include staged changes
```

### Phase 2 — Launch three reviewers in parallel

Use `delegate_task` batch mode — pass all three tasks in one `tasks` array.

**Reviewer 1 — Code Reuse:** Review for code that duplicates functionality already in the codebase.

**Reviewer 2 — Code Quality:** Review for quality problems: redundant state, parameter sprawl, copy-paste-with-variation, leaky abstractions.

**Reviewer 3 — Efficiency:** Review for efficiency problems: unnecessary work, missed concurrency, hot-path bloat, TOCTOU anti-patterns.

### Phase 3 — Aggregate and apply

1. Merge findings into one list, deduping where reviewers overlap
2. Discard false positives
3. Resolve conflicts (correctness > readability > micro-perf)
4. Apply surviving fixes directly
5. Verify tests pass
6. Summarize what was changed

## Pitfalls

- Don't fan out wider than ~3 reviewers
- Give the WHOLE diff to each reviewer
- Reviewers search, they don't guess
- Apply ≠ rewrite — keep edits scoped to the diff