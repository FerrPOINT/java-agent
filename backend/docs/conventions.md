# Conventions: Lombok / Records / MapStruct

## TL;DR

- **DTO и immutable core models** — `record`.
- **Spring beans / command handlers / indicators** — `@RequiredArgsConstructor` + `@Slf4j`.
- **JPA entities** — `@Entity` + Lombok `@Data`.
- **Mapping entity → domain → DTO** — MapStruct `@Mapper(config = MapStructConfig.class)`.

## Records

Use `record` for:

- All API DTOs in `api.dto`.
- All immutable core models in `core.model` (`Message`, `ToolCall`, `Session`, `ChatResponse`).
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

## MapStruct

### Configuration

`MapStructConfig` sets `componentModel = "spring"`, `unmappedTargetPolicy = IGNORE`, `nullValueCheckStrategy = ALWAYS`.

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
- Write unit tests with `Mappers.getMapper(X.class)`.

### Annotation processor order

Lombok runs before MapStruct. Both are configured in `backend/build.gradle`.

## Testing

- Use `Mappers.getMapper(...)` in mapper unit tests.
- Use real mappers in service tests; avoid mocking mappers unless the mapping itself is irrelevant.
- Add mapper tests for edge cases: nulls, enums, tool calls, empty collections.

## When NOT to migrate

- Constructors that perform validation, logging, or build runtime state not expressible with Lombok.
- Classes already minimal with no dependencies (plain handlers, validators) — Lombok gives no value.
- Bot layer uses JPA entities as domain models; no separate mapping needed there.
