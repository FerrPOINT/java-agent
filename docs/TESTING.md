# Тестовые уровни

Обязательная общая политика: [Base TESTING](https://github.com/FerrPOINT/services-base/blob/ad724088e9a63b1e353fb5b28e4308e9ae6f45ae/docs/TESTING.md).
Выбор компонентов и native commands: `.quality-gate.json`.

## Разработка и сборка

Backend: `bash scripts/quality-gradle.sh --backend :test`.
Bot/CLI/shared: `bash scripts/quality-gradle.sh :telegram-bot:test :cli:test :shared:test`.
В новом Base selector эти три gates явно объединены через `gradle_unit_group`:
один native invocation содержит только выбранные module tasks и сохраняет
`member_gates` в plan/result. Одна CLI правка не добавляет bot/shared tests.
Backend9.8.0 не смешивается с root9.6.1. Старый pinned selector исполняет прежние
отдельные argv до adoption новой quality version; это не смена runtime/pins.
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
для backend, unit/integration для bot; прежние coverage baselines сохраняются.

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
Temurin 25.0.4.1 и wrapper 9.6.1/9.8.0. Версия читается из фактических properties,
а не комментариев. Init scripts, release/integration/slow, фильтр `--tests`,
неизвестные tasks/options и явные cache flags передаются без добавления default.
Configuration cache сохраняет task graph; Gradle up-to-date/build cache отдельно
проверяет outputs. Он не разрешает пропускать live/DB проверки.

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
