---
name: systematic-debugging
description: "4-phase root cause debugging: understand bugs before fixing."
version: 1.1.0
author: Hermes Agent (adapted)
license: MIT
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [debugging, troubleshooting, problem-solving, root-cause, investigation]
    related_skills: [test-driven-development, plan]
---

# Systematic Debugging

## Overview

Random fixes waste time and create new bugs. Quick patches mask underlying issues.

**Core principle:** ALWAYS find root cause before attempting fixes. Symptom fixes are failure.

## The Iron Law

```
NO FIXES WITHOUT ROOT CAUSE INVESTIGATION FIRST
```

## When to Use

Use for ANY technical issue:
- Test failures
- Bugs in production
- Unexpected behavior
- Performance problems
- Build failures
- Integration issues

## The Four Phases

### Phase 1: Root Cause Investigation

1. Read Error Messages Carefully — don't skip past errors or warnings
2. Reproduce Consistently — can you trigger it reliably?
3. Check Recent Changes — git diff, recent commits
4. Gather Evidence in Multi-Component Systems
5. Trace Data Flow — where does the bad value originate?

### Phase 2: Pattern Analysis

1. Find Working Examples — locate similar working code
2. Compare Against References
3. Identify Differences
4. Understand Dependencies

### Phase 3: Hypothesis and Testing

1. Form a Single Hypothesis — "I think X is the root cause because Y"
2. Test Minimally — smallest possible change, one variable at a time
3. Verify Before Continuing

### Phase 4: Implementation

1. Create Failing Test Case
2. Implement Single Fix — address the root cause
3. Verify Fix
4. If 3+ fixes failed: Question the architecture

## Red Flags

If you catch yourself thinking:
- "Quick fix for now, investigate later"
- "Just try changing X and see if it works"
- "Add multiple changes, run tests"

**ALL of these mean: STOP. Return to Phase 1.**