# Быстрые повторные проверки и сборка

Применить [стандарт Base](https://github.com/FerrPOINT/services-base/blob/ad724088e9a63b1e353fb5b28e4308e9ae6f45ae/docs/BUILD_PERFORMANCE.md) без изменения runtime и SDK/UI/skills pins.
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

Обычная сборка: `bash scripts/quality-gradle.sh --backend test bootJar --no-daemon --max-workers=2`.
Ручной release gate: `bash scripts/quality-gradle.sh --backend releaseCheck bootJar --no-daemon --max-workers=2`.
Без `--backend` helper выбирает корневой multi-project build. Он фиксирует
`SOURCE_DATE_EPOCH` из exact candidate commit; явное значение сохраняется и остаётся
полноценным входом `build.time`. В повторном запуске одного кандидата время не меняется.
Единая политика backend/bot/CLI находится в `gradle/build-info.gradle`.
Без helper/epoch используется явно unversioned epoch 0; candidate producer передаёт
реальное commit time через Docker build argument. Это input JAR, а не время запуска.
`slowTest` всегда выполняется заново:
внешнее состояние PostgreSQL не покрывается Gradle build cache.
