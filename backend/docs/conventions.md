# Conventions: Lombok / Records / MapStruct

## TL;DR

- **DTO и immutable core models** — `record`.
- **Spring beans / command handlers / indicators** — `@RequiredArgsConstructor` + `@Slf4j`.
- **JPA entities** — `@Entity` + Lombok `@Data`.
- **Mapping entity → domain → DTO** — MapStruct `@Mapper(config = MapStructConfig.class)`.
- **Bot layer** — entities как domain models, отдельный mapping не нужен.

## Records

Use `record` for:

- All API DTOs in `api.dto`.
- All immutable core models in `core.model` (`Message`, `ToolCall`, `Session`, `ChatResponse`).
- Bot API DTOs in `bot.api`.
- Enums stay enums; small value objects with behavior may stay classes.

## Lombok

### `@RequiredArgsConstructor` on Spring beans

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRuntimeService {
    private final AgentRuntime agentRuntime;
    private final SessionRepository sessionRepository;
    // ...
}
```

- Only `final` dependencies are injected.
- Non-injected mutable state (caches, locks) must be `non-final` and initialized inline.
- Field declaration order = constructor parameter order (`@RAC` generates constructor in field order).

### `@Data` on JPA entities

```java
@Entity
@Table(name = "messages")
@Data
public class MessageEntity { ... }
```

- Provides getters, setters, equals/hashCode, toString.
- Be careful with `@Data` + `@OneToMany` lazy collections; override `toString()` or mark fields `@ToString.Exclude` if needed.

### `@Slf4j`

Replace manual `LoggerFactory.getLogger(...)` with `@Slf4j`.

### When NOT to use Lombok

| Case | Reason |
|------|--------|
| Constructor with logic (HttpClient.new, Executors.new, RestClient.builder, factory methods) | Not pure assignment |
| Null-checks in constructor (`x == null ? "" : x`) | Not pure assignment |
| `@Qualifier` on constructor param | Lombok `@RAC` doesn't support per-param annotations |
| Multiple constructors (overloading) | `@RAC` generates one constructor |
| Classes without DI dependencies | Lombok gives no value |

### `@PostConstruct` for derived fields

When a field is computed from an injected dependency:

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class WebSearchTool implements ToolHandler {
    private final AgentProperties agentProperties;  // injected
    private final ObjectMapper objectMapper;          // injected
    private int configuredLimit;                      // derived → non-final

    @PostConstruct
    void init() {
        configuredLimit = agentProperties.getWeb().getSearchResults();
    }
}
```

- In unit tests: call `init()` manually after `new WebSearchTool(...)`.
- `init()` must be package-private (no access modifier) so tests in the same package can call it.

### Inline init for runtime state

Fields not depending on injected dependencies (executors, caches, maps):

```java
private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "background-review");
    t.setDaemon(true);
    return t;
});
```

- No `@PostConstruct` needed — field is self-contained.
- Unit tests work without calling `init()`.

## MapStruct

### Configuration

`MapStructConfig` sets `componentModel = "spring"`, `unmappedTargetPolicy = ERROR`.

### Where to place mappers

| Layer | Package | Example |
|-------|---------|---------|
| entity → domain | `persistence.mapper` | `MessageMapper`, `SessionEntityMapper` |
| domain → DTO | `api.mapper` | `DomainDtoMapper` |
| OpenAI ↔ domain | `api.mapper` | `OpenAiMapper` |

### Rules

- Map directly field-to-field when names match.
- Use `@Named` helper methods only for non-trivial conversion (enums, nested objects).
- Prefer `default` methods over complex `@Mapping` expressions when conditional logic is needed.
- `roleToString` → `role.name().toLowerCase()` (e.g., "user", "assistant").
- `stringToRole` → `Role.valueOf(role.toUpperCase())`, null → `Role.USER`.
- Write unit tests with `Mappers.getMapper(X.class)`.

### When NOT to use MapStruct

- `buildResponse` in services, where DTO is assembled from multiple sources (Session + TurnResult + UsageTracker + Properties).
- Streaming chunk creation (DTO specific to SSE format).
- Bot layer: entities are domain models, no separate mapping needed.

## Testing

- Use `Mappers.getMapper(...)` in mapper unit tests.
- Use real mappers in service tests; avoid mocking mappers unless the mapping itself is irrelevant.
- Add mapper tests for edge cases: nulls, enums, tool calls, empty collections.
- After `new ServiceClass(...)` in tests, call `init()` if the class has `@PostConstruct` derived fields.

## Annotation processor order

Lombok runs before MapStruct. Both are configured in `backend/build.gradle` and `telegram-bot/build.gradle`:

```groovy
compileOnly 'org.projectlombok:lombok:1.18.38'
annotationProcessor 'org.projectlombok:lombok:1.18.38'
implementation 'org.mapstruct:mapstruct:1.6.3'
annotationProcessor 'org.mapstruct:mapstruct-processor:1.6.3'
```

## Mapper catalog

| Mapper | Direction | Methods |
|--------|-----------|---------|
| `MessageMapper` | `MessageEntity` ↔ `Message` | `toDomain`, `toEntity`, `roleToString`, `stringToRole`, `extractToolCall` |
| `SessionEntityMapper` | `SessionEntity` ↔ `Session` | `toDomain`, `toEntity` |
| `DomainDtoMapper` | `Session` → `SessionSummaryDto` | `toSessionSummaryDto`, `toSessionSummaryDtoList` |
| `OpenAiMapper` | Domain ↔ OpenAI DTOs | `toOpenAiMessage`, `toMessage`, `toOpenAiTool`, `toToolDefinition`, `toOpenAiToolCall`, `toOpenAiResponse`, `toChatResponse`, `roleToString` |