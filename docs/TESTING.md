# Тестовые уровни

Обязательная общая политика: [Base TESTING](https://github.com/FerrPOINT/services-base/blob/ad724088e9a63b1e353fb5b28e4308e9ae6f45ae/docs/TESTING.md).
Выбор компонентов и native commands: `.quality-gate.json`.

## Разработка и сборка

Backend: `bash scripts/quality-gradle.sh --backend test`.
Bot/CLI/shared: `bash scripts/quality-gradle.sh :telegram-bot:test :cli:test :shared:test`.
`bootJar` и обычный `build/check` включают unit, без integration/slow.
Unit entrypoint не требует PostgreSQL fixture variables. DB/disposable guards
сохраняются в backend integration/slow/release; unit не подготавливает БД.
Ручная проверка: backend `releaseCheck` (полный coverage ratchet),
`integrationTest`, `slowTest`, необходимые `liveTest`/`e2eTest`.
Gradle inventory — gradle/test-lanes.json; `python scripts/check_test_lanes.py`
требует классификацию новых файлов. JaCoCo объединяет unit/integration/slow
для backend, unit/integration для bot; прежние coverage baselines сохраняются.

## Ручная релизная проверка

Полный прежний набор сохранён в `.github/workflows/release.yml`: только
workflow_dispatch на exact ref. Автоматический CI не поднимает PostgreSQL
и не запускает длительные/browser/live тесты.

Required security/data/compatibility checks и thresholds сохраняются.
Unit success не заменяет ручную приёмку; NOT RUN не означает PASS.

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
