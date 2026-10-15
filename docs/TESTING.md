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
