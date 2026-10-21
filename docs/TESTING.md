# Тесты и сборки Java Agent

Общий стандарт — `docs/BUILD_PERFORMANCE.md` из pinned `qualityTooling.path`
workspace inventory. Продукт владеет Gradle tasks, source membership и fixtures.
Подключение описано в [плане](plans/2026-10-21-scoped-quality-implementation.md).

## Повседневный цикл

`gradle/test-lanes.json` перечисляет каждый test/support source. Неизвестный,
удалённый или пересекающийся source требует review до компиляции. Unit не
компилирует manual sources; общая fixture находится в `testSupport`.
Аннотации, Groovy, processors и `processTestResources` сохраняются. Быстрые
проверки выполняются в подготовленном pinned environment; native heavy команды
используют общий Base host slot. Windows CLI baseline на source281a5a10/
qualitybb4999d2 прошёл183units/JAR; факты и ограничения — в Base
`docs/plans/java-native-explicit-clients-20261021.md`. Новый source/classpath
graph и остальные module controls требуют собственного native proof.

```bash
python3 scripts/check_test_lanes.py
make test MODULE=cli
make jar MODULE=backend
```

`make build/test/jar` выбирает module и объединяет совместимые goals в один
wrapper invocation. Backend использует свой wrapper9.8.0, клиенты — root9.6.1;
root clients graph не конфигурирует backend. `bootJar` зависит от unit `test`.
Unit не запускает полный JaCoCo. Лимиты heap/forks не являются actual RAM.

Root workspace: `./check-workspace.ps1 -Profile Unit -Repository java-agent`.
`-Component tooling -BaseRef HEAD` запускает только Python source guards.
`-Profile Build -PlanOnly -DetailedPlan` показывает выбранные checks/producers.
Docs не выбирают JVM; test-only не выбирает production JAR. Shared main выбирает
реальных consumers, Shared test — только Shared. Неизвестный input расширяет scope.

Windows ordinary native route подключён через `java-compose-v1` для selected
backend/CLI/Bot units/JAR и Shared unit. Root launcher сохраняет прежний default
`desktop-linux`; для выбора endpoint передавать `-NativeDockerContext` явно.
Явно пустой context даёт отказ до admission/preparation. Например из workspace root:

```powershell
./check-workspace.ps1 -Profile Build -Repository java-agent -Component cli -NativeDockerContext desktop-linux -PlanOnly -DetailedPlan
```

Selected CLI/Bot graph сохраняет `--quality-root`; existing native owner выполняет
нужные units и JAR в согласованной операции. Root9.6.1 и backend9.8.0 остаются
разными wrappers. Standalone selector —exact Base4a7a92bb; Root quality pin
независим и берётся из workspace inventory (PDLC3 квалифицировал CLI baseline
наbb4999d2). Совместимость selector bytes проверяется при exact adoption.
Подключение маршрута не является application qualification: все Gradle gates
сохраняют `cacheable=false`, compiler-cache switch отвергается, manual lanes
не входят в automatic native declaration.

## Runtime closure CLI

CLI — non-web REPL, использует core Boot starter для lifecycle/logging/YAML.
Spring HTTP client и Jackson2 предоставляет публичный Shared API; reactive-streams
остаётся явной зависимостью multipart. Embedded servlet server/MVC не являются
CLI runtime contract. Backend/Bot сохраняют собственные server dependencies.

`CliTransportClasspathTest` проверяет Boot type deduction без старта context,
настоящую конфигурацию клиента через mock transport: HTTPS URI/API-key,
JSON/JavaTime request, tolerant octet-stream DTO response и multipart resource/
origin/disposition. Реальный socket, TLS/proxy handshake и backend не запускаются;
не считать такой unit заменой ручной transport/live acceptance.
Проверять также фактический `BOOT-INF/lib` собранного JAR: один source declaration
не доказывает удаления транзитивного server graph. Native proof и измерения
изменённого graph фиксируются в каноническом Base ledger.

## Ручная полная проверка

```bash
python3 scripts/release_verify.py
```

Release verifier объединяет backend test/integration/slow/report/JAR в один
standalone graph, bot report и полные CLI/Shared tasks — в один root graph.
Повторного теста ради отчёта нет. `--skip-slow` несовместим с полной coverage.
LINE floors backend79.38% и bot82.47% остаются blocking через
`scripts/check_docs_standard.py --check-coverage`; отсутствующий report — ошибка.
Существующий `make coverage` генерирует reports, а не заменяет полный verifier.

`release.yml` запускается вручную и сохраняет прежние docs/readme/lint,
disposable PostgreSQL persistence/transaction checks и coverage ratchet.
Backend JAR строится в test job и передаётся с SHA256 следующему job. Exact JDK
проверяется у producer; повторная установка JDK/сборка у consumer не нужна.

Live/E2E, migrations/security/data/compatibility и необходимая runtime приёмка
остаются отдельными ручными gates. Для Gradle API source-set fixture используется
`scripts.tests.test_source_sets_native` с explicit pinned JAVA_HOME и
PDLC_GRADLE_HOME через host slot. Зелёный Python/unit-набор не доказывает эти gates.

21 октября на source `cc2ef3dc` native Gradle API fixture прошёл с JDK25.0.4.1
и root9.6.1: Java/Groovy unit/manual/shared membership, classpaths/resources,
configuration-cache reuse, task graphs и refusal unknown source. Application
sources не компилировались; integration/slow были только dry-run. Canonical
evidence находится в source Base: `docs/plans/java3-native-sourcesets-20261021.md`.
Это не backend9.8.0, native JAR/coverage proof или разрешение artifact/cache opt-in.

## Кэши и доказательства

Backend, CLI и bot не подключают Lombok/MapStruct к `testAnnotationProcessor`:
полный reviewed physical inventory, включая unit/shared/manual support,
не требует генерации этими processors. Удалены также дублирующие testCompileOnly
dependencies; main processors, MapStruct API и готовые main mappers сохранены.
`testSupport`/`manualTest` наследуют тот же облегчённый test processor classpath.
Source guard в `scripts.tests.test_test_lanes` проверяет весь inventory и
отличает `Mappers.getMapper`/API constants от generation annotations. Новая
generation annotation требует review graph/dependencies; ручной набор из
inventory не удаляется. Existing partition-source gate учитывает все test inputs.
Это согласованный перенос решения из PDLC1 без замены topology/toolchain/pins.

32Python selector/adoption tests PASS7,033с; source inventory584unit/
183integration/68slow/9live сохранился. Это не результат нового JVM запуска.
Actual CLI unit/JAR qualification после изменения starter и test processors,
а также Backend9.8.0/Bot и ручные source-set/coverage gates остаются отдельными.
Прежний183-unit CLI baseline относится к source281a5a10 и не квалифицирует
изменённые inputs. Host memory guard и compiler-cache opt-out сохраняются.

Dependency caches и штатная Gradle incremental работа сохраняются. CLI unit/JAR
явно разрешают native receipt eligibility с exact Base5acb6750 selector/caller;
это требует свежего opt-in native proof и readiness controls. Прежний99ac proof
с opt-out не повышается задним числом. Linux grouped units остаются одним
`cacheable:false` invocation; остальные native modules, automatic configuration
cache default и compiler-cache opt-in остаются выключены. Qualification и её
границы фиксируются в каноническом Base плане, а не выводятся из одного flag.
Clean/full-source contract сохраняется; dirty-context narrowing проверяется
отдельно. Остальные SDK/UI/skills/
operator/global quality pins не меняются. Source counts не равны числу JUnit
methods и не являются результатом native execution. См. [coverage strategy](11-test-coverage-plan.md).
