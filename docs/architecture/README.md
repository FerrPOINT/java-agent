# Architecture Documentation

## Главный архитектурный закон

Обязательное решение владельца от 2026-11-16:
[одна актуальная модель Base](https://github.com/FerrPOINT/services-base/blob/a25aefd6381088aef3f1d7b2f30b8e4aa2fc5e0f/docs/REPO_STANDARDS.md#current-contract-and-modes).
У правила один владелец; замена контракта включает миграцию данных, обновление
всех consumers и удаление старого пути, включая read-only readers/verifiers.
Соседние версии, compatibility-слои и fallback на старое запрещены. Сохранность
бизнес-данных и аудита обеспечивается актуальной моделью по этому закону.
Описанные ниже прежние переходные механизмы — состояние реализации, которое
подлежит проверке; они не дают исключения из главного закона.

Открытый архитектурный долг и порядок устранения: [аудит Base](https://github.com/FerrPOINT/services-base/blob/4ac9fcebcb215ce5c7073d197c752a60e66e8cda/docs/plans/single-current-model-audit-20261116.md).

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
| [ADR-016](adr/ADR-016-reproducible-build-toolchain.md) | Exact Temurin JDK and committed Gradle wrappers | Proposed; exact-source gates required |

## Quick Stats

| Metric | Value |
|--------|-------|
| Java source files | 494 |
| Test files | 515 |
| REST endpoints | 132 |
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

## Единый README verifier

Base `scripts/verify_readme.py --root .` исполняет универсальные проверки;
продукт хранит только `readme-requirements.json`. Forge docs aggregator вызывает
тот же CLI. Контракт и negative tests принадлежат Base; SDK adoption и actual
docs gate проверяются отдельно от исходных изменений и UI pin.

## Актуальные compression и attachment contracts

Компрессия принимает `focusTopic` и `keepLastN`; прежнее поле `focus`
отклоняется. CLI и бот отправляют `focusTopic`. Общие статические функции
исполнителя принадлежат `TurnExecutorUtils`; методов-переадресаций в
`TurnExecutor` нет. Skills bundles читаются через `/api/v1/agent/bundles`.
OAuth lookup всегда включает профиль.

Скачанное Telegram-вложение сохраняется локально и регистрируется через
`POST /api/v1/attachments` до запуска чата. ID, content hash и duplicate
подтверждаются ответом владельца. Локальный путь не является альтернативным
входом: отказ или неизвестный результат регистрации останавливают отправку
агенту. При Unknown файл остаётся для сверки, автоматического повтора upload
нет. Текст старых сообщений сохраняется как исходный аудит; фиктивные artifact
IDs при миграции не создаются. Данные, доступные только по прежнему пути,
требуют реальной регистрации байтов перед новым использованием.

## Единый Cron API

Актуальный [Cron API](../cron-api.md) принадлежит `CronJobController`;
`CronJobService` сохраняет правила конфигурации, профильный доступ и ownership.
Два прежних compatibility controllers удалены. Текущий API поддерживает
script/noAgent, model settings, monitor/continuity и attachment session без
второго CRUD API и route aliases. Source и regression cases подготовлены;
compiled API/CLI/bot qualification учитывается отдельно.

## Владельцы approval и execution guardrails

`ToolApprovalPolicy` отвечает за MCP admission и запросы существующей
`ApprovalQueue`. Execution rules принадлежат одному `ToolCallGuardrail`:
повторные одинаковые failures, advisory no-progress, per-turn quotas и
ссылки на byte-identical результаты. Повторные успешные reads не блокируются.
Все counters принадлежат UUID сессии; reset нового turn и cleanup затрагивают
только её. Запуск без session scope является ошибкой, global bucket отсутствует.

`AgentSecurityConfig` создаёт по одному policy/guardrail bean; удалённые
`ToolGuardrails` и `ToolLoopGuardrail` не являются альтернативными режимами.
Sync и streaming используют один текущий before/after contract. Tool result
reference меняет только context copy после реального вызова, не выполняет retry.

Решение approval связано с исходным request ID и атомарно потребляется один раз.
Session ID не выбирает решение. Неиспользуемые `ApprovalGate` и
`CommandApprovalManager`, фиктивные session/always grants и EA/SC callbacks
удалены; реальный runtime не имел consumer их allowlist или producer prompts.
API и CLI используют [текущий контракт](../tool-approval.md).
