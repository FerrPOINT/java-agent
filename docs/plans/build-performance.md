# Быстрые повторные проверки и сборка

Применить [стандарт Base](https://github.com/FerrPOINT/services-base/blob/2a8b5e905ab2a6fd4c12d0d2a70d9436e745765d/docs/BUILD_PERFORMANCE.md) без изменения runtime и SDK/UI/skills pins.
Product-owned команды описаны в .quality-gate.json. Сохранять полный обязательный
CI/DB/security/compatibility набор на интегрированной вехе. Во время реализации
scoped tests, reuse только deterministic exact-candidate evidence.
Проверить no-change, один компонент, dependency/toolchain change и corrupt output;
измерить duration и cache hit/miss. Сохранять прогретые caches и чистить own scratch.
