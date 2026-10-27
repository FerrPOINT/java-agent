# Быстрые и ручные проверки Java Agent

Общий стандарт — [Base BUILD_PERFORMANCE](https://github.com/FerrPOINT/services-base/blob/f72bb207fda8152f8db700367a7398bc1bc773d2/docs/BUILD_PERFORMANCE.md)
и [Base TESTING](https://github.com/FerrPOINT/services-base/blob/f72bb207fda8152f8db700367a7398bc1bc773d2/docs/TESTING.md).
Этот документ описывает продуктовый Gradle graph; SDK и quality pins независимы.

## Разработка и сборка

Использовать закреплённые wrappers: root 9.6.1, standalone backend 9.8.1.

```bash
cd backend
./gradlew test --no-daemon --max-workers=2
./gradlew bootJar --no-daemon --max-workers=2
```

Для отдельного клиента из корня:

```bash
./gradlew :telegram-bot:test --no-daemon --max-workers=2
./gradlew :cli:test --no-daemon --max-workers=2
./gradlew :shared:test --no-daemon --max-workers=2
```

`bootJar`, `build` и `check` используют unit graph. Не запускать test повторно
перед `bootJar`: внутри одного Gradle invocation зависимости выполняются один раз.
`gradle/test-lanes.json` задаёт полный reviewed inventory тестов и support sources.
Новые или удалённые файлы требуют актуализации inventory до компиляции.

`test` компилирует только unit sources и нужные shared fixtures.
`manualTest` компилируется отдельно для integration/slow/live. Unit не зависит
от compiled manual output; manual не подтягивает весь unit output ради fixtures.
Файлы остаются на прежних путях. Test resources готовятся штатной общей задачей.

Backend Testcontainers и bot-зависимость от backend owner/Flyway доступны только
manual compilation. Обычный bot unit не должен строить backend owner JAR.
Общий in-memory CDP transport имеет одного владельца в shared fixture;
unit не загружает вложенный helper из manual test class через reflection.

Проверки inventory без JVM:

```bash
python scripts/check_test_lanes.py
python -m unittest scripts.tests.test_test_lanes scripts.tests.test_release_verify
```

Числа в inventory — файлы, не число исполненных JUnit cases. Производительность,
heap и actual task graph проверяются отдельно на соответствующем JDK/wrapper.
Прежние heap/fork budgets сохраняются до измеренной квалификации нового профиля.

## Ручная веха

Из backend:

```bash
./gradlew integrationTest --no-daemon --max-workers=2
./gradlew releaseCheck --no-daemon --max-workers=2
./gradlew fullCheck --no-daemon --max-workers=2
```

`releaseCheck` проверяет reviewed unit + integration coverage scope,
LINE >=79.05%. `fullCheck` явно включает slow и комбинированный LINE >=79.38%.
Bot coverage сохраняет LINE >=82.47% через существующий docs ratchet.
Часть reviewed slow files может не иметь JUnit slow tag: их перенос меняет состав
прежнего fast execution set. Совпадение старого набора и достижение сохранённых
floors не объявляются доказанными до native coverage qualification.
Live/E2E остаются отдельным прямым запросом и не входят в эти команды.
Самостоятельный `jacocoTestReport` — ручная проверка с integration, не обычный build.

Slow-tagged файл не может оставаться в integration inventory: иначе JUnit tag
filter и file filter могут вместе исключить его из обоих наборов. Live+slow
остаётся live. Если slow помечен только один метод, выделить его в отдельный
slow test class с общим fixture: остальные методы сохраняют прежний integration
coverage scope. Assertions, production deadlines и пороги покрытия не ослабляются.

`python scripts/release_verify.py` является ручной release-командой. Она объединяет
tests, reports и запрошенные JARs в один invocation каждого совместимого wrapper.
`--run-slow` добавляет slow в те же graphs. Owner migration class уже входит
в bot slow lane; отдельный `identityOwnerTest` не дублируется в полном slow запуске.
Этот dedicated task сохраняется для точечной PostgreSQL-приёмки.

PostgreSQL/real waits запускаются только в изолированном тестовом окружении.
Manual workflow — `.github/workflows/integration.yml`. Automatic CI выполняет
unit/static и передаёт backend JAR из того же invocation, который проверил unit,
с SHA256SUMS. Это candidate, а не production/live acceptance.

## Границы текущего переноса

Этот checkout получил Gradle source partition и согласованные consumers.
Root quality manifest, полный native gate, Docker build, фактические resource
profiles и полная release/live приёмка квалифицируются отдельно. Native metadata
fixture не заменяет компиляцию и исполнение текущих application tests.
Прогретые Gradle dependencies/task caches сохраняются; `clean` не является
обычной подготовкой. Ресурсные измерения не выводятся из `maxHeapSize`.

## Выбор компонентов и повторная подготовка

Из workspace PDLC2:

```powershell
./check-workspace.ps1 -Profile Unit -Repository java-agent -Component cli -PlanOnly
./check-workspace.ps1 -Profile Build -Repository java-agent -BaseRef HEAD -PlanOnly
```

`-Component` задаёт явный scope; без него Base выбирает changed paths. Docs-only
не выбирает application producers, test-only не выбирает JAR. Shared main
расширяет проверки на реальных consumers; неизвестный путь сохраняет полный
fallback продукта. CI потребляет тот же exact packaged selector. Root quality,
SDK/UI/skills/operator pins независимы и не переключаются при этой adoption.

`make test MODULE=cli`, `make jar MODULE=bot` выбирают один модуль и необходимые
Shared dependencies. Совместимые goals объединяются в один вызов wrapper.
`--quality-root` конфигурирует только выбранный client dependency closure;
полный root graph остаётся доступен для ручной integration/coverage.
Bot owner/Flyway dependency сохраняется только в полном manual graph.
Raw bot/CLI Docker builders используют тот же selected Settings profile.

`bootBuildInfo` получает стабильный явный `SOURCE_DATE_EPOCH` из helper; raw
callers без этого input не записывают поле времени. Clock time больше не является
скрытым входом ресурсов. Подтверждение actual UP-TO-DATE на native остаётся gate.
Версии wrappers и heap/fork budgets сохранены. Automatic configuration cache
и compiler-cache grants не включены. Download/Gradle incremental caches сохраняются.
Для CLI unit/JAR выбран существующий native reuse profile JDK25.0.4.1/Gradle9.6.1:
новый source tuple получает собственные unit/JAR/tool/dependency proofs перед
reuse. Точный JAR path и root build/wrapper/properties inputs входят в контракт.
Остальные product gates сохраняют cacheable=false до своей native qualification.
Это не отключает штатную инкрементальность Gradle и не разрешает повторный install.
План и границы CLI qualification: [selected native route](plans/cli-native-reuse-20261027.md).

Ручной release workflow делегирует существующему integration workflow:
повторные jobs и новая оркестрация тестов не добавляются. Проверки deploy/parity
process protocols перенесены из automatic docs job в manual gate; их inventory
сохранён. Финальный native/Docker/coverage gate ещё не выполнен.
