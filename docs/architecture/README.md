# Architecture Documentation

> Java Agent — Spring Boot 4.1 + Java 25 + LangChain4j + MCP + Telegram Bot

This directory contains the architecture documentation for the Java Agent project.
All diagrams use **Mermaid** syntax (renders inline in GitLab/GitHub).

## Index

| Document | Description |
|----------|-------------|
| [C4 Model](c4-model.md) | System architecture using C4 model (Context → Container → Component → Code) |
| [Component Diagram](component-diagram.md) | Module-level dependencies across backend / telegram-bot / cli |
| [Sequence Diagrams](sequence-diagrams.md) | Key runtime flows: chat turn, streaming, tool execution, compression, curator cycle, Telegram message processing |
| [ERD](erd.md) | Entity-Relationship diagram from JPA entities and Flyway migrations |
| [Design Patterns](design-patterns.md) | Catalog of design patterns used throughout the codebase |
| [ADRs](adr/) | Architecture Decision Records |

## ADRs

| ADR | Title | Status |
|-----|-------|--------|
| [ADR-001](adr/ADR-001-virtual-threads-vs-reactive.md) | Virtual Threads over Reactive for Concurrency | Accepted |
| [ADR-002](adr/ADR-002-sqlite-to-postgresql.md) | Migration from SQLite to PostgreSQL | Accepted |
| [ADR-003](adr/ADR-003-langchain4j-for-llm-abstraction.md) | LangChain4j as LLM Client Abstraction | Accepted |
| [ADR-004](adr/ADR-004-mcp-sdk-2.0.md) | MCP Java SDK 2.0 for Tool Protocol | Accepted |
| [ADR-005](adr/ADR-005-spring-boot-4.1-java-25.md) | Spring Boot 4.1 + Java 25 Platform | Accepted |
| [ADR-006](adr/ADR-006-api-key-authentication.md) | API Key Authentication | Accepted |
| [ADR-007](adr/ADR-007-controller-split.md) | Controller Split (8 Focused Controllers) | Accepted |
| [ADR-008](adr/ADR-008-agentic-loop-deduplication.md) | Agentic Loop Deduplication | Accepted |
| [ADR-009](adr/ADR-009-micrometer-metrics.md) | Micrometer Metrics with Prometheus | Accepted |
| [ADR-010](adr/ADR-010-testcontainers.md) | Testcontainers for Integration Tests | Accepted |

## Quick Stats

| Metric | Value |
|--------|-------|
| Java source files | 490 |
| Test files | 515 |
| REST endpoints | 114 |
| CLI slash commands | 92 |
| Bot commands | 56 (+ 10 aliases) |
| Flyway migrations | 26 (V1–V26) |
| Gradle modules | 3 (backend, telegram-bot, cli) |
| MapStruct mappers | 5 |

## How to Read

1. Start with [C4 Model](c4-model.md) for the big picture.
2. Read the [ADRs](adr/) to understand why key decisions were made.
3. Refer to [Sequence Diagrams](sequence-diagrams.md) for runtime behavior.
4. Check [Component Diagram](component-diagram.md) for module dependencies.
5. Use [ERD](erd.md) for the data model.
6. Browse [Design Patterns](design-patterns.md) for implementation conventions.