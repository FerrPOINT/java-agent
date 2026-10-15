# Быстрые повторные проверки и сборка

Применить [стандарт Base](https://github.com/FerrPOINT/services-base/blob/6ba8b64e7175ebc0175d4ac0b5ef130f6a7366df/docs/BUILD_PERFORMANCE.md) без изменения runtime и SDK/UI/skills pins.
Product-owned команды описаны в .quality-gate.json. Сохранять полный обязательный
CI/DB/security/compatibility набор на интегрированной вехе. Во время реализации
scoped tests, reuse только deterministic exact-candidate evidence.
Проверить no-change, один компонент, dependency/toolchain change и corrupt output;
измерить duration и cache hit/miss. Сохранять прогретые caches и чистить own scratch.

Канонический Gradle entrypoint: `bash scripts/quality-gradle.sh --backend test slowTest jacocoTestReport bootJar --no-daemon --max-workers=2`.
Без `--backend` helper выбирает корневой multi-project build. Он фиксирует
`SOURCE_DATE_EPOCH` из exact candidate commit; явное значение сохраняется и остаётся
полноценным входом `build.time`. В повторном запуске одного кандидата время не меняется.
Без helper и без epoch сохраняется штатное время Spring Boot, поэтому такой запуск
может заново выполнять зависимые tasks. `slowTest` всегда выполняется заново:
внешнее состояние PostgreSQL не покрывается Gradle build cache.
