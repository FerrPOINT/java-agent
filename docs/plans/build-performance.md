# Быстрые повторные проверки и сборка

Применить [стандарт Base](https://github.com/FerrPOINT/services-base/blob/68cb8b930b0543a7ecb7f8a34c347b96fd0cd8ba/docs/BUILD_PERFORMANCE.md) без изменения runtime и SDK/UI/skills pins.
Product-owned команды описаны в .quality-gate.json. Сохранять полный обязательный
CI/DB/security/compatibility набор на интегрированной вехе. Во время реализации
scoped tests, reuse только deterministic exact-candidate evidence.
Проверить no-change, один компонент, dependency/toolchain change и corrupt output;
измерить duration и cache hit/miss. Сохранять прогретые caches и чистить own scratch.

Linux QA получает отдельные PostgreSQL 16 (миграции/repositories) и 17.6
(usage concurrency). JAVA_AGENT_TEST_DB_DISPOSABLE=true явно разрешает только
одноразовый database fixture из .quality-gate.json. Каждый test JVM создаёт
собственную схему; повтор не наследует committed records прошлого JVM. QA owner
удаляет весь собственный fixture после операции. Host Docker socket не нужен;
без external fixture сохраняется штатный Testcontainers-вариант.

Канонический Gradle entrypoint: `bash scripts/quality-gradle.sh --backend test slowTest jacocoTestReport bootJar --no-daemon --max-workers=2`.
Без `--backend` helper выбирает корневой multi-project build. Он фиксирует
`SOURCE_DATE_EPOCH` из exact candidate commit; явное значение сохраняется и остаётся
полноценным входом `build.time`. В повторном запуске одного кандидата время не меняется.
Без helper и без epoch сохраняется штатное время Spring Boot, поэтому такой запуск
может заново выполнять зависимые tasks. `slowTest` всегда выполняется заново:
внешнее состояние PostgreSQL не покрывается Gradle build cache.
