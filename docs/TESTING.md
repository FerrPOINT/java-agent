# Тестовые уровни

Обязательная общая политика: [Base TESTING](https://github.com/FerrPOINT/services-base/blob/ad724088e9a63b1e353fb5b28e4308e9ae6f45ae/docs/TESTING.md).
Выбор компонентов и native commands: `.quality-gate.json`.

Полная ручная приёмка: `python3 scripts/release_verify.py` выполняет по одному
graph на pinned wrapper, включая manual CLI/Shared classes и blocking coverage
ratchet. Обычный `:test` и генерация coverage report не заменяют этот набор.
Live/E2E остаются отдельными gates; эта source-поправка не является новым native
release proof.

## Разработка и сборка

### Явный scope Makefile

`make build MODULE=backend`, `make test MODULE=cli` и `make jar MODULE=bot`
делегируют `scripts/quality-make.sh` → существующий `quality-gradle.sh`.
Допустимые MODULE: `backend`, `cli`, `bot`, `shared`, `all`; default — `all`.
Это явный scope, не автоматический changed-path выбор. При изменении Shared
нужны реальные потребители; `MODULE=shared` проверяет только библиотеку,
для общего consumer gate выбирать `all` либо штатный selector workspace.

Build/test выполняют reviewed units и их необходимую compilation. Backend
идёт через standalone wrapper9.8.0, bot/CLI/shared через root9.6.1; `all` запускает
эти два graphs последовательно. Внутри graph — `--no-daemon --max-workers=2`,
прежние fork/heap limits и квалифицированные helper defaults сохранены.
Неизменные dependency caches не очищаются. Epoch фиксируется один раз до обоих
graphs, явный SOURCE_DATE_EPOCH сохраняется и проверяется штатным helper.

Опциональный Base compiler cache включается только ведущими switches
`--quality-root --base-compile-cache` для `:cli:test`, `:shared:test` и
`:cli:bootJar`. Base передаёт `BASE_JAVA_COMPILE_CACHE_ACTIVE=1`,
`PROFILE=root-clients-v1`, `NAMESPACE` и `POLICY_SHA256` (по 64 lowercase hex),
`POLICY` — абсолютный путь к plain `java-qa-compile-cache.init.gradle`;
последние четыре имени также имеют префикс `BASE_JAVA_COMPILE_CACHE_`.
Helper проверяет фактические bytes policy (не более 64 KiB), затем добавляет
только этот init script и `--build-cache`, сохраняя strict configuration-cache
default. Backend, bot, dependency preparation и произвольные init scripts
этим hook не разрешены; active environment без switch отклоняется.

Read-only context отражает проверенный contract отдельным `compiler_cache`:
он меняет полный `inputs_sha256`, сохраняя stable dependency/preparation `inputs`.
Без opt-in прежний context wire/hash не меняется. Cache volume/integrity принадлежат
Base; наличие hook не квалифицирует persistent compiler cache, автоматический
receipt reuse или конкретный native tuple. Для них нужна отдельная qualification.

Jar выбирает `:bootJar` нужного приложения с его unit prerequisite, без отдельного
повторного `test`; `MODULE=shared` выполняет `:shared:test :shared:jar`.
Совместный `make build test jar MODULE=cli` передаёт один packaging graph;
`make test coverage MODULE=all` и `make jar coverage MODULE=all` объединяют
запрошенные tasks в один вызов каждого wrapper, независимо от порядка goals.
Coverage остаётся явно запрошенным ручным graph. Его report tasks уже требуют
тесты; при сочетании с units сохраняются CLI/shared units, при сочетании с JAR
сохраняются выбранные bootJar. Один `make coverage MODULE=all` по-прежнему
выбирает только объявленные backend/bot reports, без добавления CLI/shared.
`make -j` не распараллеливает wrapper invocations. Installer/release сохраняют
полный комплект и требуют `MODULE=all` до recipes, не устанавливая частичный
набор рядом со старыми JAR. Install/deploy здесь не выполнялись.

`make coverage MODULE=backend|bot|all` остаётся ручным полным report graph с
unit/integration/slow и прежними floors. CLI/shared не объявляют JaCoCo report:
такой scope отклоняется, а не заменяется unit-only отчётом. Для релизного
blocking coverage ratchet использовать прежние releaseCheck/verification gates.

Make и adapter не владеют общим host slot. В Windows workspace запускать всю
heavy операцию через разрешённый Base owner/execution slot по root-инструкции;
дочерние wrappers не оборачивать повторно. Fast helper tests не стартуют JVM.
Makefile/adapter changes выбирают tooling checks, без application producer.

Host systemd helper `scripts/deploy-dev-systemd.sh` собирает только backend и bot:
сначала standalone backend helper, затем root bot helper, с общим frozen epoch
и `--max-workers=2`. Ошибка backend не запускает bot; `--skip-build` сохраняет
проверку наличия JAR без Gradle. Backend consumer использует канонический
`backend/build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar` из `java-artifacts.json`,
без fallback на legacy filename. Recording-wrapper class `DeployBuildProtocolTest`
в `scripts/tests/test_deploy_dev_systemd.py` останавливается до install/runtime;
она не квалифицирует настоящий JAR, повторное artifact reuse или deployment.

Scoped check: `python -B -m unittest scripts.tests.test_quality_gradle -q`.
Он проверяет реальный Bash adapter и helper с recording Gradle wrapper, error
propagation, scoped tasks и frozen epoch. Actual GNU Make dispatch проверяется
отдельным test, когда `make` доступен; отсутствие Make — явный skip. Эти fixtures
не квалифицируют настоящий Gradle compilation/JAR или скорость приложения.

PDLC1 сохраняет backend wrapper9.8.0, root9.6.1, LangChain4j1.21.0 и JLine4.4.7.
Перенос Make adapter не меняет toolchain/dependency/quality pins или eligibility:
backend unit/build receipts остаются отключены до отдельной qualification.

Backend: `bash scripts/quality-gradle.sh --backend :test`.
Bot/CLI/shared: `bash scripts/quality-gradle.sh --quality-root :telegram-bot:test :cli:test :shared:test`.
В новом Base selector эти три gates явно объединены через `gradle_unit_group`:
один native invocation содержит только выбранные module tasks и сохраняет
`member_gates` в plan/result. Одна CLI правка не добавляет bot/shared tests.
Backend9.8.0 не смешивается с root9.6.1. Старый pinned selector исполняет прежние
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
wrappers (9.6.1/9.8.0), Groovy 6, manual inventory/fixtures, coverage и измерение
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
каждого модуля; Shared9.6.1 и Shared9.8.0 не перезаписывают друг друга.
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

## Явный client graph и dependency-only preparation

`bash scripts/quality-gradle.sh --quality-root :cli:test` выбирает configuration
profile `clients-v1`: Shared, CLI и Telegram bot, без Backend. Без флага обычный
root graph сохраняет Backend, включая `:backend:bootRun`. В preparation inputs
входят build scripts всех трёх configured clients, даже при resolution CLI/Shared.
`gradle/java-preparation.json` v2 владеет configured logical modules и closure;
нужен совместимый Base v2 preparer без автоматического принятия v1 owners.

Ordinary Make units/JAR, CI root units и systemd bot JAR используют этот client
graph. Make coverage/report requests и standalone `make jar MODULE=shared`
сохраняют прежний full root graph: их tasks не входят в ordinary allowlist.
Ручные release gates остаются на прежнем пути. Это source adoption callers; native graph,
dependency parity и performance qualification нового tuple проверяются отдельно.

Production Docker recipes CLI/bot также вызывают `--quality-root`, копируют root
`gradle.properties` и не требуют Backend build script. Сохраняются прежние JAR,
notices и unit prerequisites. Recording-wrapper checks исполняют shell-команды
recipes без JVM/Docker; actual configured projects, dependency/JAR parity,
configuration-cache reuse и backend-only invalidation требуют native qualification.

Только preparation добавляет `--dependencies-only` после выбора graph:
`--quality-root --dependencies-only :cli:qualityDependencyInputs
:shared:qualityDependencyInputs` либо `--backend --dependencies-only
:qualityDependencyInputs :shared:qualityDependencyInputs`. Допустимы explicit
snapshot tasks и ограниченные прежние cache/worker options. Compilation, tests,
JAR, init scripts и property overrides запрещены. Gradle dispatcher проверяет
requested task names также при прямой передаче property.

`gradle/test-topology.gradle` задаёт общую sourceSet/configuration topology.
Dispatcher `gradle/test-lanes.gradle` загружает `gradle/test-membership.gradle`
и `gradle/test-lanes.json` только для ordinary compilation/testing. Полная
проверка inventory сохраняется перед compilation; dependency-only mode не
допускает эти tasks. External snapshot сохраняет production/unit dependencies,
processors и Groovy compiler inputs.

Backend, CLI и bot не подключают test Lombok/MapStruct processors или их
дублирующие testCompileOnly dependencies: полный reviewed inventory, включая
support/manual sources, не объявляет таких annotations. Main processors и
MapStruct API сохранены; тесты получают готовые main mappers. Общая topology
не передаёт лишние test processors в testSupport/manualTest. Source guard
проверяет все lanes, но native compilation этих source sets и прежняя test/JAR
parity после изменения dependency graph ещё требуют отдельной qualification.

Dependency indexes v2 содержат фактические configured project paths, включая `:`.
Read-only context reader проверяет profile/projects по product contract и
включает graph в dependency digest. Legacy v1 читается только как legacy;
смешанные версии/graphs отклоняются и не становятся v2 preparation provenance.
Flattened backend recipes копируют topology и membership для ordinary builds.

Source helper/JSON tests не квалифицируют новый Gradle tuple. Actual settings
и task graphs, dependency-manifest parity, inventory/backend-only layer reuse,
unknown-source refusal и exact Base caller adoption остаются native gate.
Этот source change не включает JAR/test receipt reuse.

## CLI без серверного web starter

CLI использует `spring-boot-starter` 4.1.0 для bootstrap, configuration properties,
logging и YAML. `application.yml` сохраняет `web-application-type: none`.
HTTP client принадлежит Shared: его `api` dependencies сохраняют `spring-web`,
Jackson2 databind и JavaTime; CLI сохраняет reactive-streams для multipart,
JLine и прежние test dependencies. Backend и bot starters не меняются.

Owning POM/Gradle metadata 4.1.0 у прежнего web starter включает Tomcat,
Web MVC, Boot HTTP converters и Boot Jackson3. CLI создаёт client/converter
самостоятельно через `BackendRestClientFactory`, а не Boot auto-configured builder.
Замена starter не является переходом на Jackson3 или разрешением исключать
необходимые библиотеки по package names. Resolved graph и actual JAR проверяются
на native вехе; снижение CPU/RAM/размера этим source изменением не измерено.

`BackendRestClientFactoryTest` использует публичный `RestClient.mutate()` и
`MockRestServiceServer.bindTo(RestClient.Builder)`: проверяет реальные default
headers, отсутствие пустого API key, shared Jackson2 mapper, JSON/ISO dates и
octet-stream JSON с unknown fields. `BackendClientWireTest` содержит три быстрых
unit cases: JSON, SSE и multipart boundary, включая binary bytes, sanitized
filename и disposition. `BackendClientTest` сохраняет прежние integration cases
и ручной lane; три wire cases перенесены без изменения bodies. Production factory
и transport ради тестов не меняются;
mock server не открывает socket и не проверяет настоящие timeout/TLS настройки.
Shared использует request assertions через уже подключённый AssertJ, без
Hamcrest-перегрузок Spring matchers и дополнительной test dependency.

Java checks добавлены, но до coordinated pinned-JVM gate не объявляются PASS.
На одной согласованной native вехе выполнить CLI/Shared units и один CLI bootJar,
сравнить resolved classpaths и `BOOT-INF/lib`, сохраняя весь нужный client graph.
Проверить packaged non-web bootstrap/property binding и обычный выход CLI с
disposable backend stub, JSON/octet-stream/SSE/multipart, а также неизменность
backend/bot graph. Тот же JAR и его digest передать дальше без повторной сборки.
Старые preparation indexes после изменения `cli/build.gradle` не считаются
совместимыми; toolchain/pins, heaps и noncacheable Gradle receipts сохраняются.
