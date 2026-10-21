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
используют общий Base host slot. Windows application JVM route здесь ещё не
квалифицирован; Gradle API fixture имеет отдельную ограниченную qualification.

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

Dependency caches и штатная Gradle incremental работа сохраняются. Artifact
receipts, automatic configuration cache default и compiler-cache opt-in этого
peer tuple пока выключены до native qualification. Остальные SDK/UI/skills/
operator/global quality pins не меняются. Source counts не равны числу JUnit
methods и не являются результатом native execution. См. [coverage strategy](11-test-coverage-plan.md).
