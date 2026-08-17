---
name: test-driven-development
description: "TDD: enforce RED-GREEN-REFACTOR, tests before code."
version: 1.1.0
author: Hermes Agent (adapted)
license: MIT
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [testing, tdd, development, quality, red-green-refactor]
    related_skills: [systematic-debugging, plan]
---

# Test-Driven Development (TDD)

## Overview

Write the test first. Watch it fail. Write minimal code to pass.

**Core principle:** If you didn't watch the test fail, you don't know if it tests the right thing.

## The Iron Law

```
NO PRODUCTION CODE WITHOUT A FAILING TEST FIRST
```

## Red-Green-Refactor Cycle

### RED — Write Failing Test

Write one minimal test showing what should happen.
- One behavior per test
- Clear descriptive name
- Real code, not mocks (unless truly unavoidable)

### Verify RED — Watch It Fail

MANDATORY. Never skip.
- Test fails (not errors from typos)
- Failure message is expected
- Fails because the feature is missing

### GREEN — Minimal Code

Write the simplest code to pass the test. Nothing more.
- Don't add features, refactor other code, or "improve" beyond the test
- Cheating is OK in GREEN: hardcode return values, copy-paste, skip edge cases

### Verify GREEN — Watch It Pass

MANDATORY.
- Test passes
- Other tests still pass
- Output pristine (no errors, warnings)

### REFACTOR — Clean Up

After green only:
- Remove duplication
- Improve names
- Extract helpers
- Simplify expressions

Keep tests green throughout. Don't add behavior.

## Why Order Matters

Tests written after code pass immediately. Passing immediately proves nothing:
- Might test the wrong thing
- Might test implementation, not behavior
- Might miss edge cases you forgot

Test-first forces you to see the test fail, proving it actually tests something.

## Verification Checklist

- [ ] Every new function/method has a test
- [ ] Watched each test fail before implementing
- [ ] Wrote minimal code to pass each test
- [ ] All tests pass
- [ ] Tests use real code (mocks only if unavoidable)
- [ ] Edge cases and errors covered