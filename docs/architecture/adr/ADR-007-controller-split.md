# ADR-007: Controller Split (8 Focused Controllers)

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2025-08-12 |
| **Deciders** | Project lead |
| **Tags** | architecture, api, controllers |

## Context

The `AgentController` class had grown to ~100 endpoints covering chat, sessions, memory, skills, checkpoints, runtime settings, kanban, and curator. This caused:

1. The file to be ~800 lines, making it hard to navigate and maintain.
2. Merge conflicts when multiple developers worked on different domains.
3. Unclear ownership boundaries — any endpoint could be anywhere.
4. Swagger UI showing all endpoints under a single tag with no grouping.

## Decision

Split `AgentController` into 8 focused controllers by domain:

| Controller | Domain |
|------------|--------|
| `AgentChatController` | Chat, streaming, steer, stop, approvals, TTS, transcription |
| `SessionController` | Session lifecycle, compression, undo, model switching, snapshots |
| `MemoryController` | Memory CRUD, pending memory approval |
| `SkillController` | Skill listing, reload, bundle management |
| `CheckpointController` | Checkpoint create, list, diff, restore, delete |
| `RuntimeSettingsController` | Config, reasoning, tools, goals, credits, codex runtime |
| `KanbanController` | Todo/kanban board |
| `CuratorController` | Curator status, run, pause, resume |

All controllers share the `/api/v1` base path. `@Tag` annotations group them in Swagger UI.

## Consequences

**Positive:**

- Each controller is 50–200 lines, easy to navigate.
- Clear domain boundaries — developers know where to add endpoints.
- Swagger UI groups endpoints by domain tag.
- Fewer merge conflicts.

**Negative:**

- More files to navigate (8 instead of 1).
- Shared `/api/v1` prefix means controllers must coordinate path naming to avoid collisions.
- Some cross-domain endpoints (e.g., session snapshot in SessionController) blur boundaries.
