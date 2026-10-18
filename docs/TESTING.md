# Тестовые уровни

Обязательная общая политика: [Base TESTING](https://github.com/FerrPOINT/services-base/blob/9d8f01b92eeeb51e9693660365e3edad1a8899b8/docs/TESTING.md).
Выбор компонентов и native commands: `.quality-gate.json`.

## Разработка и сборка

PDLC2 сохраняет upstream backend wrapper9.8.1, LangChain4j1.22.0 и JLine4.4.8.
Source reader controls для9.8.1 проверяют URL/layout/integrity; native tuple
квалифицируется отдельно. Прежние сертификаты9.8.0 и dependency versions
не являются новым proof9.8.1. Portable reader по-прежнему выдаёт
`reuse_qualified:false`; его receipts и13 root/manual Gradle gates не включаются.
Только backend:unit/backend:build дают явный consent native Base profile9d8f01b9:
Linux/x86_64, Temurin25.0.4.1/Eclipse Adoptium, backend Gradle9.8.1, полные
JDK/wrapper/distribution identities и проверенная unit dependency closure.
Первый qualification run выполняется через matching candidate tooling с
`--no-reuse`, затем проверяются actual warm/invalidation/loss и original proofs.
До применимых controls exact workspace packet не принимается. Source opt-in
не является native PASS. Текущая qualification/adoption хранится в Base ledger
и source inventory; SDK/UI/skills/operator pins этим не меняются.

Ниже сохранены исходные source/native наблюдения. Их прежнее указание
15 noncacheable gates описывает соответствующий этап; текущий consent ограничен
двумя backend native gates выше и не расширяет portable/manual eligibility.


Backend: `bash scripts/quality-gradle.sh --backend :test`.
Bot/CLI/shared: `bash scripts/quality-gradle.sh :telegram-bot:test :cli:test :shared:test`.
В новом Base selector эти три gates явно объединены через `gradle_unit_group`:
один native invocation содержит только выбранные module tasks и сохраняет
`member_gates` в plan/result. Одна CLI правка не добавляет bot/shared tests.
Backend9.8.1 не смешивается с root9.6.1. Старый pinned selector исполняет прежние
отдельные argv до adoption новой quality version; это не смена runtime/pins.
Обычный GitHub CI получает `java_root_tasks` от product selector и передаёт
только эти allowlisted tasks одним вызовом: CLI-only не выбирает bot/Shared units,
bot-only не выбирает CLI/Shared units. Изменение Shared сохраняет backend и
все root consumers; manual release продолжает проверять полный набор.
Backend использует точные `:test`/`:bootJar`: unqualified `test` выбирает также
Shared subproject и дублирует его проверку в последующем root graph.
`bootJar` и обычный `build/check` включают unit, без integration/slow.
Backend artifact gate объявляет `covers_units: [backend:unit]`: новый Base
выполняет один `:bootJar` graph с его `:test` prerequisite, затем отдельно
сертифицирует unit inputs/reports без второго Gradle-вызова. При test-only
выбирается unit; artifact reuse не подменяет свежий test proof. Shared log
принадлежит producer. Старый quality packet выполняет прежние два argv до adoption.
JAR output и test-result directory заданы явно; wrapper/shared Gradle scripts
входят в inputs. Base receipt reuse настоящего JAR/units остаётся выключенным
до qualification SOURCE_DATE_EPOCH, toolchain и Gradle home/init configuration.
Source gates теперь объявляют `source_date_epoch: git-head` с явным
`environment_inputs: [SOURCE_DATE_EPOCH]`. Новый Base до fingerprint вычисляет
effective epoch exact candidate, передаёт его producer и указывает в result.
Непустое явное значение имеет приоритет; пустое соответствует прежнему helper
default. Неизвестная policy/нечисловое значение отклоняются. Shared producer и
unit обязаны иметь одинаковый epoch contract. Сам shell helper сохраняет прежний
fallback для standalone запуска, но Base уже передаёт frozen значение.

Build time — реальный JAR input, поэтому новый commit с implicit epoch меняет
его даже при прежних application bytes. Нельзя выдавать старое встроенное время
за новую сборку. При сохранённом explicit epoch одинаковые production inputs
могут использовать прежний artifact; свежий test proof всё равно относится к
новому commit. Эта source qualification не включает настоящий JAR receipt reuse
и не закрывает qualification JDK/Gradle home/init или artifact handoff.

Новый Base reader использует `scripts/gradle-context.py` из product root:
реальный `JAVA_HOME/bin/java` (PATH только без JAVA_HOME), content inventory JDK,
wrapper bytes, Gradle home properties/init scripts, daemon JVM criteria и hashes
effective flags. Probe не запускает Gradle, install или приложение и не печатает
значения JVM options/секретов. Inventory streaming/bounded, old-mtime byte changes
меняют identity; external links не обходятся. Injection options удалены только
из version probe, окружение настоящего Gradle не меняется.

`context_argv/context_pattern` сохраняют legacy `java -version` для старого
reader. Новый reader проверяет context перед/после исполнения и перед reuse;
`reuse_qualified: false` запрещает receipt даже при ошибочном `cacheable: true`.
Сейчас false возвращается всегда: Gradle distribution/resolved input closure
и настоящий JAR handoff ещё не квалифицированы. Все 15 Gradle gates сохраняют
`cacheable: false`; pins автоматически не обновляются. Полный JDK inventory
переиспользуется для совместимых unit keys внутри одного checked snapshot,
а свежие boundary checks не пропускаются.

Scoped contract checks: `python -B -m unittest scripts.tests.test_gradle_context`.
JVM в них mocked; Linux symlink fixture проверен отдельно, без Gradle/сборки.
Unit entrypoint не требует PostgreSQL fixture variables. DB/disposable guards
сохраняются в backend integration/slow/release; unit не подготавливает БД.
Ручная проверка: backend `releaseCheck` (полный coverage ratchet),
`integrationTest`, `slowTest`, необходимые `liveTest`/`e2eTest`.
Gradle inventory — gradle/test-lanes.json; `python scripts/check_test_lanes.py`
требует классификацию новых файлов. JaCoCo объединяет unit/integration/slow
для backend и bot; прежние coverage baselines сохраняются.

### Раздельная компиляция unit и ручных lanes

Backend unit heap cap —1536m; `maxParallelForks=1` и `forkEvery=750` сохранены.
Ручные backend integration/slow/live сохраняют4g, e2e —2g. Unit tuning не
наследуется ими как новый лимит. Gradle daemon budget остаётся1g. Эти значения —
caps, не measured usage; native qualification/фактические CPU/RAM/time публикует
Base, а unit task логирует свой применённый cap перед исполнением.

`gradle/test-lanes.json` содержит полный `files` inventory и `support` ownership.
Существующие пути тестов сохранены. `test` компилирует unit sources и unit-only
helpers; `testSupport` — общие pure fixtures; `manualTest` — integration/slow/live
и их helpers. Ручные Test tasks используют manual output, main и shared support,
без unit classes. Ресурсы приходят из штатного `processTestResources`, без второй
копии. Все ручные lanes могут использовать один manual compilation output,
но обычный build их не запускает. Testcontainers объявлены только в
`manualTestImplementation`; общий JUnit/Mockito tooling остаётся у потребителей.

Изменение shared fixture касается обеих сторон. Unit→manual ссылки, включая
reflection, заменяются общей fixture; assertions не удаляются. Неизвестные,
устаревшие и пересекающиеся source entries блокируются до compilation.
Metadata-only dependency preparation не требует materialized test sources.
Обычный и ручной CI выполняют Python inventory check до подготовки Gradle;
release coverage и ограничения forks/heap сохранены.

Сейчас inventory содержит 584 unit, 186 integration, 68 slow, 9 live source files;
это не число выполненных test cases. С учётом helpers unit/shared compilation
выбирает 590 из 861 source files; 271 manual-only source не входит в этот graph.
Эти числа описывают source ownership и не доказывают экономию CPU/RAM/времени.

Короткая проверка без JVM: `python -B -m unittest scripts.tests.test_test_lanes`.
Ручной native DSL control: `python -B -m unittest scripts.tests.test_source_sets_native`
в существующем host execution slot, с `JAVA_HOME` принятого JDK и
`PDLC_GRADLE_HOME` установленного принятого Gradle. Он работает offline с builtin
Java/Groovy plugins, проверяет classpaths/resources, unit/manual dry-run graphs,
configuration cache reuse и отказ неизвестного source. Он не компилирует
fixtures/приложение, не запускает Test workers и не устанавливает dependencies.
Gate `tooling:source-sets-native` относится только к ручному integration profile;
`tooling:test-lane-inventory` — static при разработке/обычной сборке.

На общей native вехе остаются фактические compiler/Test/JAR consumers, оба
wrappers (9.6.1/9.8.1), Groovy 6, manual inventory/fixtures, coverage и измерение
полного необходимого graph. До их квалификации 15 Gradle gates остаются
noncacheable, JAR receipt reuse не включается; installed pins не меняются.

Spring Test contexts (`@SpringJUnitConfig`/`@SpringJUnitWebConfig`) и реально
созданные application contexts относятся к integration. Импорт или mock
`ApplicationContext`, как и standalone MockMvc с mocked dependencies, не
означают запуск контекста. Validator также запрещает manual tags в unit inventory;
его короткие проверки: `python -m unittest scripts.tests.test_test_lanes`.

Unit-проверка Telegram log redaction использует mock HTTP transport и проверяет
фактическое сообщение лога; запуск с fake token и немедленный stop не изолируют
настоящий HTTP client от сети. Bot conflict counter проверяется прямым вызовом
с нулевым retry budget, без backoff. Реальный start/stop с filesystem/PID lock
сохранён отдельно в `LongPollingServiceConflictLifecycleTest` (integration).

## Подготовительный snapshot зависимостей Gradle

`gradle/quality-inputs.gradle` регистрирует `qualityDependencyInputs` в backend,
Shared, CLI и bot. Это явная подготовительная операция; обычные test/bootJar
не добавляют её автоматически. Task создаёт индекс внешних dependency files
с отдельными production/unit roles и SHA-256, а не build/test receipt.
Plugin classpath, annotation processors, unit agent и inferred Groovy compiler
учитываются; project outputs принадлежат source/build keys и не компилируются
ради индекса. Groovy compiler files материализуются без унаследованных builtBy
связей с Shared. Число файлов/байты ограничены, hashing потоковый и общий для
двух roles модуля, публикация JSON атомарная.

В разрешённом owned Linux runner под общим host slot, с уже подготовленными
downloads, можно выполнить отдельно:

```bash
bash scripts/quality-gradle.sh --backend :qualityDependencyInputs :shared:qualityDependencyInputs --offline --no-daemon --max-workers=1 --configuration-cache --configuration-cache-problems=fail
bash scripts/quality-gradle.sh :cli:qualityDependencyInputs :telegram-bot:qualityDependencyInputs :shared:qualityDependencyInputs --offline --no-daemon --max-workers=1 --configuration-cache --configuration-cache-problems=fail
```

Неполный cache требует штатной подготовки зависимостей; application compile
не используется для её замены. Индекс лежит в `build/quality/gradle-<version>-dependency-inputs.json`
каждого модуля; Shared9.6.1 и Shared9.8.1 не перезаписывают друг друга.
Явный snapshot всегда заново читает bytes, включая old-mtime изменения;
configuration cache сохраняет совместимый task model. Не запускать эту команду
перед каждым build: будущий qualified preparation adapter должен проверять
готовый индекс/контекст и выполнять её только при missing/stale proof.

Для artifact key брать production closure нужных модулей; для unit — unit.
Hash всего combined index либо shared download store не заменяет этот выбор.
Сам индекс ещё не квалифицирует Gradle distribution/prepared resolution identity,
не разрешает JAR reuse и не меняет `cacheable: false`. Native JAR/output/unit
handoff controls и отдельное exact adoption остаются обязательными.

### Read-only inspection подготовленных inputs

`scripts/gradle-context.py --prepared-module <component> --role production|unit`
добавляет к existing JDK/config snapshot содержимое выбранной distribution и
external dependency files нужных modules/role. Component IDs: `backend`, `shared`,
`cli`, `bot`; для standalone backend дополнительно `--backend`.
Модули передаются повторяющимся флагом; caller обязан выбрать полную потребляемую
project closure, включая Shared для текущих приложений. Это normal repository
layout; произвольный flat layout или cache root не подбирается автоматически.

После отдельной preparation, в той же owned Linux environment/home:

```bash
python3 scripts/gradle-context.py --backend --prepared-module backend --prepared-module shared --role production
python3 scripts/gradle-context.py --prepared-module cli --prepared-module shared --role unit
```

Reader не вызывает Gradle, не скачивает distribution, не пишет index и не
исправляет cache. Existing selected-JDK version probe остаётся read-only JVM
вызовом. При missing/invalid input он завершает проверку с ошибкой: preparation
выполняет её владелец под host slot, а не скрытый fallback этого reader.

Поддержаны только committed wrappers9.6.1/9.8.0/9.8.1, их HTTPS distribution URL и
явный `GRADLE_USER_HOME/wrapper/dists` layout. URL cache directory соответствует
[Gradle PathAssembler9.8](https://github.com/gradle/gradle/blob/v9.8.0/platforms/core-runtime/wrapper-shared/src/main/java/org/gradle/wrapper/PathAssembler.java);
unsigned MD5/base36 используется лишь для поиска, integrity — SHA-256.
Проверяются готовность wrapper marker, единственный distribution root/launcher
и bounded content inventory. Лишняя installed version не меняет выбранный context.

Index reader проверяет schema/module/wrapper/roles, уникальные JSON keys,
согласованность records и текущие bytes/hash выбранных external files. Пути
ограничены выбранным Gradle home, aliases/escapes отвергаются. File dependencies
вне него требуют будущего явно квалифицированного contract. Совместимые modules
читают общий external file один раз. В production hash не входят unit-only
dependency bytes либо hash всего index/store. Index budget4MiB/module;
выбранная общая closure ограничена4096files/2GiB, hashing потоковый.

Этот opt-in snapshot **не является READY или artifact receipt**. Он не доказывает,
что index получен из текущей configuration: Base source owner описан ниже,
а native qualification/adoption и JAR proof ещё открыты. Ответ
сохраняет `reuse_qualified:false`; все15 ordinary/manual Gradle declarations
по-прежнему используют legacy context arguments и `cacheable:false`.

### Base owner подготовки dependency image

`gradle/java-preparation.json` — единственный product-native descriptor входов
preparation и project dependency closure. Правила/состояние принадлежат Base
`scripts/prepare_java.py`. Этот descriptor вынесен из `.quality-gate.json`, чтобы
правки несвязанных CI gates не сбрасывали dependency layer; второй runner не создаётся.

Source QA image получает descriptor, wrappers/build scripts и context reader до
application source/SOURCE_DATE_EPOCH. В admitted Docker RUN Base вызывает только
`qualityDependencyInputs` нужной closure и атомарно сохраняет owner seal после
успешного producer и свежих input/output checks. Shared входит в closure приложений.
Изменение test/source/docs не обновляет dependency environment само по себе.
Неполный/изменённый seal не признаётся готовым; неизвестный owner сохраняется.

Gradle cache mounts используют окончательные пути root/backend homes. Cache
в новой Base recipe проецируется из preparation stage в отдельный final stage
по тем же paths; index paths не переписываются. Это исключает наследование
временного payload слоя перед отдельным RUN rename. IDs downloads прежние;
root/backend homes и версии раздельны. Алгоритм и qualification принадлежат Base;
эта source recipe не меняет автоматически принятую quality/operator version.
Base source QA выбирает независимые `qa-root-deps`/`qa-backend-deps`, либо
объединяет их в `qa-all-deps`. Backend-only не готовит root wrapper; CLI/bot
готовят root indices своей closure. CLI и CLI+Shared нормализуются одинаково.
Root по-прежнему содержит multi-project configuration metadata; это не обещание
исключить чужую plugin configuration. Source/application COPY пока общий.
Требуется BuildKit/buildx: legacy all-stage fallback не используется.

Native QA до и после выбранных tasks выполняет только read-only `check`; miss
останавливает compilation без скрытого запуска resolver. Helper не берёт второй
host slot: это внутренний child уже admitted image producer/QA, а не Windows
entrypoint с собственным admission. Legacy outer QA по-прежнему требует штатный
внешний slot owner. `READY` dependency environment не означает test/JAR receipt.

Принятие source path требует native проверки нового image layer, cold/warm,
home/cache materialization, actual resolved inputs и invalidation; затем JAR/unit
handoff и exact caller adoption. До этого нет утверждения об actual speedup,
измеренном RAM либо готовности Windows root quality adapter.

## Ручная релизная проверка

Полный прежний набор сохранён в `.github/workflows/release.yml`: только
workflow_dispatch на exact ref. Автоматический CI не поднимает PostgreSQL
и не запускает длительные/browser/live тесты.

Required security/data/compatibility checks и thresholds сохраняются.
Unit success не заменяет ручную приёмку; NOT RUN не означает PASS.

`gradle/build-info.gradle` задаёт одинаковую deterministic policy backend/bot/CLI:
helper фиксирует SOURCE_DATE_EPOCH из commit, Docker принимает его как build arg.
Без epoch bare build явно unversioned (epoch 0). Изменение epoch меняет JAR input.
CI выбирает backend и bot/CLI/shared graphs отдельно: CLI-only не запускает backend,
shared запускает оба. Root tasks передаются одним совместимым Gradle вызовом.

Для обычных unit/JAR tasks helper автоматически добавляет строгий configuration
cache (`problems=fail`) только на квалифицированных Linux/amd64,
Temurin 25.0.4.1 и root wrapper9.6.1 либо backend9.8.0/9.8.1. Версия читается из фактических properties,
а не комментариев. Init scripts, release/integration/slow, фильтр `--tests`,
неизвестные tasks/options и явные cache flags передаются без добавления default.
Configuration cache сохраняет task graph; Gradle up-to-date/build cache отдельно
проверяет outputs. Он не разрешает пропускать live/DB проверки.

Backend9.8.1 strict cache квалифицирован на immutable Linux/amd64/Temurin25.0.4.1
image от abb28f06: full unit/JAR task graph cold/warm dry-run,8existing unit
cases и JAR, unchanged UP-TO-DATE и cached Test action после удаления только
JUnit output. JAR остался byte-identical прежнему6917ff24; contexts одинаковы.
Подробный proof: Base source commit0ac2fe74,
`docs/plans/data/java981-configuration-cache-20261018.json`.
Полный4796 ordinary suite квалифицирован отдельно; он не повторялся как strict
suite в этом bounded control. Configuration model reuse внутри одной owned
среды не включает cross-operation test/JAR receipts. Base15receipts остаются
выключенными до отдельных context/cache-loss/cancel/retention controls.

Full/slim/backend, bot и CLI Docker producers передают `--max-workers=2`,
как обычные quality commands. Это ограничение Gradle worker leases, не лимит
всех JVM threads или всей памяти процесса. Heap/fork settings тестов и ручных
lanes сохраняются; меньший heap требует отдельного измерения aggregate peak/GC.
Runtime recipes сохраняют прежние cache flags; strict configuration-cache default
публичного helper не распространяется на raw Gradle без отдельной qualification.

Быстрый tooling-набор включает `python -B -m unittest
scripts.tests.test_quality_gradle scripts.tests.test_deploy_dev_docker -q`.
Deployment helper проверяется через заглушки команд: сначала единственная сборка
backend/bot, затем Compose `volume-init`, затем остановка host services и запуск
готовых образов с `--no-build`. Ошибка сборки/init или отсутствующая прежняя БД
останавливает сценарий до переключения runtime. Сам deploy этот unit не выполняет.
Maintenance-сервис использует тот же backend image и именованные volumes текущего
Compose project; отдельного Alpine image и вычисления имени volume из имени папки нет.

`gradle/ferrpoint-notices.gradle` задаёт единые JAR inputs для всех модулей:
LICENSE/NOTICE/THIRD_PARTY_NOTICES.md из корня репозитория. Обычный root build,
standalone backend и flattened Docker layout используют один источник. Отсутствие
любого файла останавливает конфигурацию; notices не исчезают из standalone JAR
из-за другого `rootProject.projectDir`.

## Source QA: mixed unit/JAR graph и handoff

Base source `verify-java-qa.ps1` поддерживает `-Component backend,cli -Profile
build -ArtifactComponent backend`: Backend выполняет unit+bootJar, CLI только
unit. Для плана добавить `-PlanOnly`; он не обращается к Docker. Без отдельного
ArtifactComponent Build сохраняет JAR для всех выбранных приложений. Shared
не имеет самостоятельного bootJar; его library dependency остаётся в graph.
Совместимые root tasks выполняются вместе, backend wrapper отдельно.

`java-artifacts.json` задаёт filenames/Start-Class/notices/manifest, сверяемые
с исходными Gradle declarations быстрым dependency-inputs contract test.
Этот descriptor не входит в environment preparation inputs: его изменение
не требует повторной установки зависимостей. Base QA копирует выбранные JAR
в private output и проверяет main manifest/entrypoint member, notices из frozen
source, bounded bytes/hash и отсутствие stale siblings/aliases. Report связывает
JAR с source head/epoch и QA image; после cleanup и source guards повторная
проверка bytes предшествует успешной публикации. Unit-only не копирует libs.

Это контракт source path. ZIP fixtures не доказывают actual class loading,
embedded build epoch, resolved classpaths или native artifact handoff; эти
controls и native qualification/exact Windows root adoption остаются открыты. Все15 Gradle
gates по-прежнему noncacheable, prepared context не выдаёт reuse qualification.

## Source Windows quality bridge

`.quality-gate.json/native_execution` объявляет только ordinary Backend unit/JAR
и bot/CLI/Shared unit через Base `java-compose-v1`. Generic source quality runner
сохраняет исходные gate IDs и объединяет selected graph в один QA operation;
на Linux сохраняется прежнее Gradle grouping. Tool/argv/epoch/fixture contracts
проверяются до dispatch. В контейнере исходные context patterns проверяются по
readiness snapshot выбранного wrapper до и после tasks, без второго JDK inventory.

После exact tooling adoption root `-Repository java-agent -Component backend,cli
-Profile Build -NativeDockerContext desktop-linux -PlanOnly` показывает Backend JAR
и CLI unit. План не обращается к Docker. Старый pinned launcher при новом flag
даёт явный отказ, а обычные прежние команды сохраняются. До qualification path
требует clean checkout; он не разрешает JAR/test receipt reuse. Ручные
integration/slow/release остаются отдельными Linux fixture gates.

Parent держит один общий host slot через native cleanup/certification/publication,
не создаёт subprocess reentrance через env. Отчёт содержит каждый selected unit
proof и producer JAR identity; shared wall учитывается один раз. Missing/changed
context/source/output либо failing unit не дают зелёный результат. Source tests
используют protocol fixtures, Git и PowerShell; actual Linux image/JAR/resources,
dirty context и полный installed root путь ещё требуют native proof.

## Измеренный unit budget

Linux qualification: backend 4805 cases, 76 с с запуском Gradle/JVM;
bot/CLI/shared 1660 cases, 47 с. Цель прогретого runner: backend до 90 с,
остальные вместе до 60 с; cold preparation учитывается отдельно.
Inventory: 586 unit, 183 integration, 68 slow, 9 live test files.
25 файлов с измеренными ожиданиями/долгими cases перенесены в slow;
реальные DNS URL safety проверки — в integration. Изолированные URL safety
с контролируемым resolver остаются unit. Assertions и production code сохранены.
Первичная загрузка Mockito/инструментации может занимать несколько секунд;
это отдельно от искусственных задержек и внешних зависимостей.
