# Java3: prerequisites for scoped quality adoption

Status: source research complete; native adoption remains open. Date: 2026-10-21.

## Decision and boundary

Do not add a docs-only product descriptor that can report a green Unit profile
while omitting Java unit coverage. Do not move the current full CI to manual
execution until a complete fast replacement exists. This change adds only this
plan; it does not change Gradle, tests, wrappers, CI, dependencies, pins or runtime.

The smallest coherent next implementation slice is the reviewed test source
partition plus its existing Gradle wiring and source validator, followed by the
product descriptor/helper and CI routing. Use the existing Base executor and
canonical selector packet; do not create another executor or cache framework.
Before executing tests/builds, read the build-performance and testing standards
from the workspace's pinned `qualityTooling.path` in `source-workspace.json`.

## Verified source state

- Java3 source: `942ebafee4c3f98623708947a1393174000e7731`, initially clean.
- Reference Java1 source: `3c17f478251575b18a69d0d8a1426e1ea81c8859`.
- Historical initial descriptor: `87666f15e9c93fd6235e25de6a0449ce13b4d4ad`.
  It offered legacy integrated tests and `bootJar -x test`; copying that contract
  would not satisfy the current unit-before-artifact rule.
- Java: exact Temurin runtime `25.0.4.1+1-LTS`; CI setup version
  `25.0.4+101.0.LTS`. Root wrapper is 9.6.1; standalone backend wrapper is 9.8.0.
  Both wrapper property files and `backend/settings.gradle` match the reference
  byte for byte. Keep both wrappers; no toolchain upgrade is required here.
- Actual modules: `backend`, `telegram-bot`, `cli`, `shared`. The standalone
  backend includes sibling `shared`, including its alternate Docker layout.
- No product descriptor, test partition, quality helper or selector packet is
  installed in Java3. Source directories still combine manual and unit tests.
- Backend `test` excludes live/slow/e2e tags but finalizes `jacocoTestReport`;
  bot test also finalizes coverage. Untagged `BotApplicationTest` starts Spring.
  Tag filters alone neither isolate fast units nor prevent manual compilation.
- Backend test forks remain serialized (`maxParallelForks = 1`), with
  `forkEvery = 750`. The configured 4 GiB test heap and 10 GiB Gradle heap are
  ceilings, not measured consumption. Do not tune them from these numbers.

## Prerequisite map

| Boundary | Existing reference files | Required Java3 adaptation |
| --- | --- | --- |
| Reviewed membership | `gradle/test-lanes.json`, `scripts/check_test_lanes.py`, `scripts/tests/test_test_lanes.py` | Review every actual Java/Groovy test and helper; reject missing, stale or overlapping entries. Port the validator behavior and tests together. |
| Independent compilation | `gradle/test-lanes.gradle`, `gradle/test-topology.gradle`, `gradle/test-membership.gradle` | Preserve current source paths but create separate unit/manual/support outputs. Unit compilation must not depend on manual classes. Keep annotation processors and Groovy support. |
| Module wiring | `backend/build.gradle`, `telegram-bot/build.gradle`, `cli/build.gradle`, `shared/build.gradle` | Apply partition scripts in all four modules; preserve dependencies, JVM opens, fixtures, serialized forks and test discovery. Remove automatic full coverage only when equivalent manual coverage is wired. |
| Graph selection | `settings.gradle`, `backend/settings.gradle`, `gradle/java-preparation.json` | Root clients profile configures bot/CLI/shared without backend. Preserve the default all-module graph and existing standalone shared fallback. Validate schema and exact graph; do not silently fall back. |
| Dependency preparation | `gradle/quality-inputs.gradle`, `scripts/quality-gradle.sh`, `scripts/gradle-context.py`, `scripts/tests/test_gradle_context.py`, `scripts/tests/test_dependency_inputs.py` | Port supported bounded operation modes with actual wrappers and graph inputs. Preparation must not compile tests or trigger manual work. Environment/cache sharing requires compatible inputs. |
| Source provenance and packaging | `gradle/build-info.gradle`, `gradle/ferrpoint-notices.gradle`, `java-artifacts.json` | Current modules inline buildInfo/notices. Extract without dropping required notices or changing artifact identity; verify actual JAR filename, Start-Class and source date before declaring reuse. |
| Product entrypoints | `.quality-gate.json`, `scripts/quality-make.sh`, `scripts/tests/test_quality_gradle.py`, `Makefile` | Map docs/backend/bot/CLI/shared/tooling and dependency consumers. Stop `make test` from doing a separate compile invocation and `make jar` from repeating preparation. Keep explicit manual release commands. |
| Native qualification | `scripts/tests/test_source_sets_native.py` | Preserve as a separate manual native test. Prove compiled source membership, shared fixtures, processors, resources and failure behavior on the exact Java3 tuple. Do not substitute source scans for this gate. |
| Scoped CI | `.github/workflows/ci.yml`, `.github/workflows/release.yml`, selector packet | Add only after fast graph exists. Preserve original CI jobs in manual release, classify before JVM setup, and check out the same exact head in classifier and consumers. |

All reference-only files above are currently missing in Java3 except the existing
module/settings/Makefile/CI files. The current root `settings.gradle` differs and
has no optional clients graph. The descriptor is the final connection of these
prerequisites, not a substitute for them.

## Exact test and fixture compatibility

The read-only inventory comparison found 850 actual Java3 Java/Groovy sources
under the four `src/test` trees. All 850 paths exist in the 862-entry reference
inventory; 787 file bodies match byte for byte and 63 differ. There are no
Java3-only paths. Twelve reference paths are absent. Identical files are evidence
for reuse of review, not proof that the reference classpath or tier is valid here.

An in-memory projection of reference membership onto the 850 Java3 sources was
passed to the existing `compilation_layout` validator without writing an inventory.
It correctly refused the first crossed boundary:

```text
Unit/shared source depends on a manual class; extract a shared fixture:
backend/src/test/java/com/azhukov/agent/tools/browser/BrowserServiceBranchTest.java
```

`BrowserServiceBranchTest` still consumes `CdpClientExtraTest.FakeWebSocket`.
The reference extracts `CdpTestWebSocket`; port the real fixture boundary rather
than making a manual class part of unit compilation. Independently, the boundary
scan found `GatewayLifecycleServiceTest` still contains `Thread.sleep(50)` while
the reference classifies its revised body as unit. Review its event synchronization
or keep it manual until equivalent deterministic behavior is established. The
scan is conservative and requires review; its output is not a timing benchmark.

Support files matching the reference exactly:

- manual: `TestJavaAgentApplication`, `core/agent/MockModelClient`;
- unit: `core/budget/AgentPropertiesStub`, `client/mcp/McpTestFactory`;
- shared: `tools/fixtures/MockArgsTool`, `MockEchoTool`, `MockFailingTool`.

The backend `persistence/PostgresTestContainer.java` exists but differs. It needs
review with disposable-DB guards and the repository fixtures. Do not replace it
with shared runtime credentials or assume external DB state is disposable.

Reference-only paths, each requiring an explicit port-or-exclude decision:

- `backend/src/test/java/com/azhukov/agent/client/langchain4j/LangChain4jHttpWireTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/DisposablePostgres.java`
- `backend/src/test/java/com/azhukov/agent/persistence/DisposablePostgresTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/PostgresActiveServicesTestContainer.java`
- `backend/src/test/java/com/azhukov/agent/support/BackendIntegrationTestBase.java`
- `backend/src/test/java/com/azhukov/agent/support/NoopSpringIntegrationTest.java`
- `backend/src/test/java/com/azhukov/agent/tools/browser/CdpTestWebSocket.java`
- `backend/src/test/java/com/azhukov/agent/tools/file/TomlFileValidationTest.java`
- `cli/src/test/java/com/azhukov/agent/cli/BackendClientWireTest.java`
- `cli/src/test/java/com/azhukov/agent/cli/JLineBoundaryTest.java`
- `telegram-bot/src/test/java/com/azhukov/agent/bot/BotIntegrationTestBase.java`
- `telegram-bot/src/test/java/com/azhukov/agent/bot/polling/LongPollingServiceConflictLifecycleTest.java`

The missing wire, lifecycle, file-validation and disposable-DB regression tests
must not be copied merely to complete an inventory. Associate each with its
production change and fixture before adding it; preserve the Java3 test meaning.

Changed common sources requiring review before inheriting reference tiers:

- `backend/src/test/java/com/azhukov/agent/JavaAgentApplicationTests.java`
- `backend/src/test/java/com/azhukov/agent/api/AdvertisedSessionEndpointsContractTest.java`
- `backend/src/test/java/com/azhukov/agent/api/AgentControllerBotToLlmIntegrationTest.java`
- `backend/src/test/java/com/azhukov/agent/api/AgentControllerIntegrationTest.java`
- `backend/src/test/java/com/azhukov/agent/api/AgentControllerStreamingLazyInitTest.java`
- `backend/src/test/java/com/azhukov/agent/api/AgentControllerStreamingTest.java`
- `backend/src/test/java/com/azhukov/agent/api/AgentControllerTest.java`
- `backend/src/test/java/com/azhukov/agent/api/ChatCompletionsContractTest.java`
- `backend/src/test/java/com/azhukov/agent/api/ChatCompletionsToolLoopTest.java`
- `backend/src/test/java/com/azhukov/agent/api/RuntimeSettingsControllerTest.java`
- `backend/src/test/java/com/azhukov/agent/client/LangChain4jModelClientLiveTest.java`
- `backend/src/test/java/com/azhukov/agent/client/ResilienceBeansExistTest.java`
- `backend/src/test/java/com/azhukov/agent/config/AgentConfigNoopProfileTest.java`
- `backend/src/test/java/com/azhukov/agent/config/AgentConfigProfilesTest.java`
- `backend/src/test/java/com/azhukov/agent/config/FlywayMigrationTest.java`
- `backend/src/test/java/com/azhukov/agent/config/SecurityConfigTest.java`
- `backend/src/test/java/com/azhukov/agent/config/ShutdownConfigTest.java`
- `backend/src/test/java/com/azhukov/agent/core/tool/ToolExecutionServiceRetryTest.java`
- `backend/src/test/java/com/azhukov/agent/core/tool/ToolRegistryWiringInvariantTest.java`
- `backend/src/test/java/com/azhukov/agent/e2e/AttachmentFlowE2ETest.java`
- `backend/src/test/java/com/azhukov/agent/e2e/SessionPersistenceE2ETest.java`
- `backend/src/test/java/com/azhukov/agent/e2e/ToolExecutionE2ETest.java`
- `backend/src/test/java/com/azhukov/agent/gateway/GatewayLifecycleServiceTest.java`
- `backend/src/test/java/com/azhukov/agent/gateway/telegram/TelegramBotApiClientLiveTest.java`
- `backend/src/test/java/com/azhukov/agent/gateway/telegram/TelegramLongPollingServiceTokenLogTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/PostgresTestContainer.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/AttachmentArtifactRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/CheckpointRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/CompressionLockRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/CronJobRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/DeliveryWorkItemRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/GatewayHomeAndReceiptRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/McpConfigRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/McpOAuthFlowRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/McpOAuthProfileIsolationTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/McpOAuthRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/MemoryRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/MemoryWriteAuditRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/MessageRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/OpenAiRunStateRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/PendingMemoryRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/ProfileRuntimeRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/SessionRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/SkillRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/TodoRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/persistence/repository/UsageRepositoryTest.java`
- `backend/src/test/java/com/azhukov/agent/tools/browser/BrowserServiceBranchTest.java`
- `backend/src/test/java/com/azhukov/agent/tools/browser/CdpClientExtraTest.java`
- `backend/src/test/java/com/azhukov/agent/tools/memory/SkillToolsIntegrationTest.java`
- `backend/src/test/java/com/azhukov/agent/tools/web/SearXngSearchProviderTest.java`
- `backend/src/test/java/com/azhukov/agent/tools/web/WebSearchToolTest.java`
- `cli/src/test/java/com/azhukov/agent/cli/BackendClientTest.java`
- `cli/src/test/java/com/azhukov/agent/cli/CliFixesTest.java`
- `cli/src/test/java/com/azhukov/agent/cli/P1CliFixesTest.java`
- `cli/src/test/java/com/azhukov/agent/cli/SlashCommandRegistryTest.java`
- `shared/src/test/java/com/azhukov/agent/shared/http/BackendRestClientFactoryTest.java`
- `telegram-bot/src/test/java/com/azhukov/agent/bot/BotApplicationTest.java`
- `telegram-bot/src/test/java/com/azhukov/agent/bot/commands/CommandRegistryTest.java`
- `telegram-bot/src/test/java/com/azhukov/agent/bot/commands/HelpCommandTest.java`
- `telegram-bot/src/test/java/com/azhukov/agent/bot/config/BotPropertiesTest.java`
- `telegram-bot/src/test/java/com/azhukov/agent/bot/core/BotMessageProcessorTest.java`
- `telegram-bot/src/test/java/com/azhukov/agent/bot/polling/LongPollingServiceConflictRetryTest.java`
- `telegram-bot/src/test/java/com/azhukov/agent/bot/typing/TypingManagerTest.java`

## Coherent execution order and acceptance

1. Review the 63 changed source bodies and missing fixture closures. Produce a
   complete Java3 membership inventory; reuse review only for unchanged bodies
   whose dependencies remain compatible. Keep unknown new tests fail-closed.
2. Port the partition scripts, validator and module wiring as one slice. Preserve
   manual resources via the existing `processTestResources` output, annotation
   processors, Groovy sources and helper closure. Verify both standalone backend
   and root clients topology without changing wrappers.
3. Move full coverage from automatic unit finalizers to an explicit manual graph.
   Backend full coverage includes unit/integration/slow and keeps LINE floor
   79.38%; bot keeps 82.47%. Preserve `liveTest` and `e2eTest` selection separately.
   Missing required reports still fail. Preserve PostgreSQL session-persistence
   and transaction-boundary checks and their disposable ownership conditions.
4. Connect product helper, Makefile and descriptor to the reviewed tasks. Native
   artifact gates stay `cacheable=false` until qualified on this peer tuple.
   Test-only changes select tests while preserving unchanged production artifacts.
   Shared changes select actual backend/bot/CLI consumers. Docs-only selects no
   Java producer; an unknown path broadens its product/component checks.
5. Package the canonical selector from exact Base
   `81467ded7038631691566182c6cfc1106fcd7e84` using
   `scripts/package_quality_selector.py`; verify with `--check`. Do not add
   optional Rust/frontend helpers or change SDK/UI/skills/operator/global quality
   pins. Do not reuse the whole current Java1 descriptor without adapting inputs.
6. Capture original CI bytes under an ignored product-owned location before the
   CI edit. Preserve the existing docs/readme/test/build/lint obligations in the
   manual workflow. After fast unit replacement is connected, route automatic
   checks by affected component and reuse one prepared environment per graph.
   Backend 9.8.0 and root 9.6.1 remain separate wrapper invocations. No duplicate
   test invocation for a coverage report within the same graph.
7. Run scoped stdlib/static checks during implementation, then one coordinated
   native gate through the Base host slot after the integrated slice. Prove units
   compile no manual sources; unit failure prevents new JAR publication; docs
   and test-only paths avoid production work; repeats have explained preparation
   and cache decisions. Measure CPU, committed memory/RSS, I/O and total time.
   Keep integration/slow/full coverage/live/long soak as explicit manual gates.

## Evidence and limits of this research

This plan is based on Git state, committed source, wrappers, in-memory inventory
comparison and a read-only call to the existing source-partition validator.
There was no JVM/Gradle/native/app/Docker execution, install, deployment or
runtime access. No application speedup, prepared environment, artifact reuse,
CI success or resource consumption is claimed. Source scans expose prerequisites;
native classpath, compilation and test correctness remain to be qualified.
