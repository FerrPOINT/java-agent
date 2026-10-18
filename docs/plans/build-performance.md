# Быстрые повторные проверки и сборка

Применить [стандарт Base](https://github.com/FerrPOINT/services-base/blob/197ead9d9b6cf6901e8f9dd31f43f719885502c3/docs/BUILD_PERFORMANCE.md) без изменения runtime и SDK/UI/skills pins.
Product-owned команды описаны в .quality-gate.json. При разработке/сборке —
unit/static; integration/slow/full coverage — вручную перед релизом.
Security/data/compatibility/live gates сохранены; reuse только для допустимых
deterministic exact-candidate проверок.
Проверить no-change, один компонент, dependency/toolchain change и corrupt output;
измерить duration и cache hit/miss. Сохранять прогретые caches и чистить own scratch.

Linux QA получает отдельные PostgreSQL 16 (миграции/repositories) и 17.6
(usage concurrency). JAVA_AGENT_TEST_DB_DISPOSABLE=true явно разрешает только
одноразовый database fixture из .quality-gate.json. Каждый test JVM создаёт
собственную схему; повтор не наследует committed records прошлого JVM. QA owner
удаляет весь собственный fixture после операции. Host Docker socket не нужен;
без external fixture сохраняется штатный Testcontainers-вариант.

Обычная сборка: `bash scripts/quality-gradle.sh --backend :test :bootJar --no-daemon --max-workers=2`.
Ручной release gate: `bash scripts/quality-gradle.sh --backend releaseCheck bootJar --no-daemon --max-workers=2`.
Без `--backend` helper выбирает корневой multi-project build. Он фиксирует
`SOURCE_DATE_EPOCH` из exact candidate commit; явное значение сохраняется и остаётся
полноценным входом `build.time`. В повторном запуске одного кандидата время не меняется.
Единая политика backend/bot/CLI находится в `gradle/build-info.gradle`.
Без helper/epoch используется явно unversioned epoch 0; candidate producer передаёт
реальное commit time через Docker build argument. Это input JAR, а не время запуска.
`slowTest` всегда выполняется заново:
внешнее состояние PostgreSQL не покрывается Gradle build cache.

Native unit/JAR qualification на Linux/amd64 выполнена для backend 9.8.0 и
совместного bot/CLI/shared 9.6.1, Temurin 25.0.4.1. Helper включает configuration
cache только для соответствующих обычных tasks/options, `problems=fail`;
toolchain changes и явные flags не наследуют этот default. Подготовка dependencies
Base разрешает также Groovy compiler classpath до offline запуска. Integration,
slow и coverage в этой квалификации не выполняются.

Notices вынесены в `gradle/ferrpoint-notices.gradle`: одна repository-owned
конфигурация вместо трёх копий; library JAR также получает notices. Изменение
notice файла выбирает JVM consumers и backend artifact; потеря input — ошибка.
Нативные JAR checks подтверждают bytes notices и frozen build time, а не только
успешный Gradle exit. Проверка full/slim builder reuse остаётся отдельным image gate.

Make entrypoints больше не выбирают unqualified root tasks для всех модулей.
Явный MODULE передаётся thin adapter `scripts/quality-make.sh`, который сохраняет
штатный `quality-gradle.sh` без изменения wrapper/cache policy. Backend и root
идут раздельно и последовательно; epoch общий, workers=2, packaging использует
существующий unit prerequisite. Полный coverage остаётся ручным; install/release
не получают частичный scope. Полные команды и ограничения —
[TESTING](../TESTING.md#явный-scope-makefile).

Сочетание explicit coverage с test/build/jar использует общий Make prerequisite:
один wrapper start на совместимый graph вместо второго старта ради report.
Union сохраняет CLI/shared units и запрошенные JAR; coverage-only не расширяется.
Тесты recording wrappers проверяют union и failure propagation; новые реальные
Make controls остаются явным skip без установленного GNU Make. Новая native
qualification actual coverage/JAR union и её CPU/RAM/wall пока не выполнена.

Проверки adapter/wrapper не запускают JVM. GNU Make dispatch, actual selected
Gradle graph и JAR остаются отдельными применимыми qualification gates;
не повторять закрытые backend4796 cases только ради Makefile/документации.
