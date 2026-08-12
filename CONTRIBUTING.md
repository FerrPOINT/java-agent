# Contributing

## Build & Test

```bash
# Compile
./gradlew compileJava

# Run all tests (excludes slow/live/e2e)
./gradlew check

# Run slow integration tests (Testcontainers PostgreSQL)
./gradlew slowTest

# Build JAR
./gradlew bootJar

# Coverage report
./gradlew jacocoTestReport
```

### Running the backend

```bash
./gradlew :backend:bootJar
java -jar backend/build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev
```

For offline development without a real LLM or database:

```bash
./gradlew :backend:bootRun --args='--spring.profiles.active=noop'
```

## Code Conventions

### Lombok
- `@RequiredArgsConstructor` + `@Slf4j` on all Spring beans with final dependencies.
- `@Data` on JPA entities.
- `record` for DTOs and immutable core models.
- **No** `@Qualifier` on constructor params (Lombok doesn't support it — use a manual constructor).
- **No** logic in constructors (use `@PostConstruct` for derived fields).

### MapStruct
- `@Mapper(config = MapStructConfig.class)` — `componentModel = "spring"`, `unmappedTargetPolicy = ERROR`.
- Mappers in `persistence.mapper` (entity ↔ domain) or `api.mapper` (domain ↔ DTO).
- Unit-test mappers with `Mappers.getMapper(X.class)` — never mock.
- 5 mappers: `MessageMapper`, `SessionEntityMapper`, `DomainDtoMapper`, `OpenAiMapper`, `BotSessionMapper`.

### Testing
- `@ExtendWith(MockitoExtension.class)` + `@Mock` for service tests.
- Real mappers (never mocked) via `Mappers.getMapper(...)`.
- Call `init()` after `new` in tests for `@PostConstruct` derived fields.
- `@Tag("slow")` for Testcontainers integration tests.
- `@Tag("live")` for tests requiring external services (disabled in CI).
- Coverage gate: LINE ≥ 80%.

### Spring Patterns
- **Self-invocation**: `@Transactional` doesn't work on same-class calls. Extract to a separate `@Component`.
- **Virtual threads**: enabled globally (`spring.threads.virtual.enabled: true`). Use `Executors.newVirtualThreadPerTaskExecutor()` for parallel tool calls.
- **Graceful shutdown**: `server.shutdown: immediate` (Spring Boot 4.1.0 bug workaround).

### Layering
```
api (controllers + DTOs)
  ↓ mapper (MapStruct)
core (domain: AgentRuntime, tools, models)
  ↓ mapper (MapStruct)
persistence (JPA entities + repositories)
```
Controllers never touch entities directly. Bot layer is the exception — it uses entities as domain models.

## Branch Strategy

- `main` — stable, always deployable.
- `feature/<name>` — feature branches off `main`.
- `fix/<name>` — bug fix branches off `main`.
- Rebase before merge. Squash-merge into `main`.

## Commit Messages

```
<type>(<scope>): <subject>

<body>
```

Types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `build`.
Scope: module name (`backend`, `bot`, `cli`, `db`, `docs`).

Examples:
```
feat(backend): add session rotation support
fix(db): add FK constraints on usage_log
refactor(backend): split AgentController into 8 focused controllers
docs: add CHANGELOG and CONTRIBUTING
```

## How to Add New Things

### New Tool
1. Create a class in `core/tool/` implementing the tool interface.
2. Annotate with `@AgentTool` and register in the tool registry.
3. Add unit tests with `@ExtendWith(MockitoExtension.class)`.
4. The tool is auto-discovered by `ToolExecutionService`.

### New Bot Command
1. Create a class in `telegram-bot/.../bot/commands/impl/` extending `CommandHandler`.
2. Register in `CommandRegistry`.
3. Add tests in `telegram-bot/src/test/java`.

### New CLI Slash Command
1. Add a `register(...)` call in `SlashCommandRegistry.registerAll()`.
2. Add subcommand suggestions in `SlashAutoSuggest` if applicable.
3. Test via CLI REPL.

### New REST Endpoint
1. Add to the appropriate controller (or create a new one if it's a new domain).
2. Add `@Operation(summary = "...")` for OpenAPI docs.
3. If new controller, add `@Tag(name = "...")` at class level.
4. Add DTOs as records in `api/dto/`.
5. Write controller tests with `@WebMvcTest`.