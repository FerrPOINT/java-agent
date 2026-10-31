# Тесты и сборки Java Agent

## Явный scope обычного Make

`make build/test/jar/coverage` требуют `MODULE=backend|cli|bot|shared`.
Без значения или с пустым MODULE команда отказывает до wrapper;
полный обход выполняется только с явным `MODULE=all`.
`install/release` сохраняют полный graph и candidate epoch.
Root changed-path selection и независимые pins не меняются.
Проверка adapter использует инертный Gradle consumer, без JVM/сборки.

## Make: стабильные dev inputs и candidate epoch

Обычные `make build/test/jar/coverage` без `SOURCE_DATE_EPOCH` передают
стабильный unversioned epoch `0`: docs/test commit не меняет buildInfo через
время HEAD. Это не обещает нулевую compilation при изменении production inputs.
Прогретые Gradle task/dependency caches сохраняются; `clean` не добавляется.

`make install` и `make release` явно передают адаптеру `--candidate-epoch`:
он фиксирует время commit один раз до backend/root wrappers. Явно заданный
`SOURCE_DATE_EPOCH` сохраняется и проверяется прежним Gradle helper.
При прямом вызове адаптера candidate packaging обозначать так:

```bash
bash scripts/quality-make.sh jar all --candidate-epoch
# Reports and packaging share the same candidate epoch and wrapper invocation:
bash scripts/quality-make.sh jar all --coverage --candidate-epoch
```

`--candidate-epoch` разрешён только с `jar`; повторные и неизвестные switches
отвергаются до wrapper. Настоящие install/release здесь не запускаются.
Это developer Make contract. Native QA, release verifier и прямой
`quality-gradle.sh` сохраняют свою candidate epoch/receipt policy; старый
native proof не становится доказательством нового commit. Проверка между
commit для native artifact отдельно от текущих unit receipts пока не включена.

Regression: `python3 -B -m unittest scripts.tests.test_make_epoch -q`.
Проверяются настоящий Bash/Git/Make adapter и инертный Gradle consumer; это
не application Gradle qualification или release acceptance.

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
разными wrappers. Standalone selector —exact Base194f8c14; Root quality pin
независим и берётся из workspace inventory. Historical sourcea53060fa/qualityA3
прошёл186CLIunits/JAR и три ordinary Build REUSED (медиана6,594с); это отдельный
exact tuple, а не proof будущих изменений. CLI unit/build имеют явный native reuse
consent; остальные Gradle gates сохраняют `cacheable=false`. Compiler-cache switch
отвергается, manual lanes не входят в automatic native declaration.

Preparation v4 сохраняет selected root graph v3 и разрешает Base передавать только
module src/skills trees выбранной dependency closure. Вся configuration, unknown
paths и общий source/content/clean guard сохраняются; ordinary command использует
одну view для export, producer binding, проверки и cleanup. V2/v3 products сохраняют
полный context. V4 требует совместимого exact quality packet; старыйA3 его отвергает.
Изменившиеся physical context/QA recipe требуют нового native proof; historical
A3 receipt не переносится. Docs/sibling dirty reuse этим срезом не квалифицируется.

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

Кандидатная compiler-cache qualification имеет отдельный явный маршрут:
Base передаёт `JAVA_QA_COMPILE_CACHE_QUALIFICATION=1` в declared Compose invocation,
runner вызывает `--base-compile-cache --base-compile-cache-qualification` до tasks.
Требуются полная pinned cache environment/policy и bounded CLI/Shared graph с
workers2. Partial/ambient flags, другие tasks и workers3 отклоняются до Gradle.
Этот режим служит native проверке кандидата и не включает cache в обычных сборках.
Обычный cache producer разрешается только через Base, который проверяет qualified
owner, exact report/policy и общий allocation до подготовки и повторно после slot.
Shell сохраняет pinned policy, полный environment, offline CLI/Shared и workers2;
статус owner принадлежит Base. Candidate не получает обычный допуск. Фактическая
активация cache, formal qualification и совместимый exact packet ещё отдельны.

Совместимый Base CLI поддерживает явный запрос без подготовки:

```text
python -B <exact-base>/scripts/java_compose_qa.py --workspace <workspace-root> --docker-context desktop-linux --component cli --profile build --cli-max-workers 2 --compiler-cache-id <id> --compiler-cache-owner-sha256 <owner-sha256> --compiler-cache-allocation-sha256 <allocation-sha256> --plan-only
```

Это нижний producer entrypoint после проверки готового артефакта; обычный root
quality caller остаётся первым маршрутом разработки. PlanOnly не проверяет
qualified readiness и не выдаёт artifact receipt. Root native CLI3 не понижается
до CLI2: cache profile выбирается явно, source поддержку не считать внедрением.
Новый CLI сам владеет Base host slot, отдельный execution-slot wrapper не нужен.

## Выбор configured projects для обычных client tasks

`gradle/java-preparation.json` v3 сохраняет доступные root modules и их зависимости,
но `--quality-root` конфигурирует только выбранную dependency closure. Для CLI это
root/CLI/Shared; Bot получает root/Bot/Shared; совместный запрос включает оба клиента.
Настройка `clients-selected-v1` и actual configured projects входят в dependency
index и seal. Reader отвергает index полного прежнего `clients-v1`, недостающую
Shared и посторонние projects. V3 требует совместимый exact Base quality packet;
старый reader отказывает до подготовки, автоматически pins не обновляются.

Штатный `scripts/quality-gradle.sh --quality-root ...` выводит selection только из
закрытого списка задач. CLI/Bot Docker builders передают явный собственный компонент.
Raw `./gradlew` без quality profile сохраняет полный graph для ручных checks.
Shell boundary проверяется `scripts.tests.test_quality_root_graph` в unit/CI без
запуска Gradle/JVM; фактический Gradle graph, units/JAR и resource effect требуют
новой native qualification. Исторический FFE receipt не переносится на новый source.
Полный source/config input guard пока сохранён: docs/test-only reuse и отсутствие
Docker invalidation при изменении sibling files этим изменением не квалифицированы.

## Измеренный native CLI worker profile

`native_execution.cli_max_workers=3` выбирает три workers только для Windows
native selected CLI. Его Base packet/selector194f8c14 содержит согласованные
producer argv и pre/post readiness guards. Linux CI, portable gate argv,
Backend/Bot/Shared и mixed/full requests сохраняют два workers. Heap/forks и
compiler cache не меняются. Произвольные или повторные workers overrides
отвергаются bounded helper до Gradle.

Команда пользователя прежняя: `check-workspace.ps1 -Profile Build -Repository
java-agent -Component cli -NativeDockerContext desktop-linux`; `-PlanOnly`
показывает явный native profile. Измерения девяти CLI samples и условия adoption
хранятся в Base `docs/plans/java3-cli-worker-profiles-20261022.md` принятого packet.
Raw benchmark не создаёт reusable receipts; новый exact source/caller tuple
требует своей native qualification. Прежний workers2 proof сохраняется отдельно.

## Точный маршрут documentation validator

`scripts/verify_readme.py` и `scripts/tests/test_verify_readme.py` выбирают `tooling:readme-unit`.
Fast для source validator также сохраняет `docs:readme`; Unit/Build не выбирают application producers.
Source command: `python -B -m unittest scripts.tests.test_verify_readme`.
Mixed backend и неизвестные source paths сохраняют своих consumers; actual CI
и ручные release gates остаются прежними.
