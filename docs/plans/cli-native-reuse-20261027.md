# PDLC2: обычная сборка выбранного CLI и reuse JAR

Исходный checkout: `d077fead8942937a9d040363f79eaab7b8d9c047`, clean.
Канонические правила: Base `docs/BUILD_PERFORMANCE.md`, `docs/PERFORMANCE_SPEC.md`;
продуктовые команды и ручные tiers — [TESTING](../TESTING.md).

## Реализация

1. Сохранить current source/tooling/lockfiles и отдельные Gradle wrappers.
   Клиентский граф использует root Gradle 9.6.1 и JDK 25.0.4.1; backend 9.8.2
   не включается в выбранную CLI операцию. Source-only PlanOnly уже подтвердил
   CLI unit + JAR под одним native adapter.
2. Исправить декларацию JAR на точный `cli/build/libs/cli-0.0.1-SNAPSHOT.jar`:
   native handoff не принимает wildcard. Подключить существующий qualified
   CLI reuse profile для `cli:unit`/`cli:build`, не меняя согласие остальных gates.
   Сохранить в artifact inputs root build files, properties и wrapper entrypoints;
   native snapshot продолжает проверять свой полный source/tool contract.
   Это source opt-in; старый PDLC1 receipt не переносится в PDLC2.
3. Выполнить scoped selector/inventory checks. После свежего census один
   штатный Root Build квалифицирует новый commit: выбранные CLI units, точный
   JAR, tool/dependency/context/cleanup guards. На ошибке candidate не публикуется.
4. Проверить три одинаковых Root вызова под inert common slot: оба результата
   REUSED, original JAR/receipts/logs неизменны; install/export/compile — 0.
   Отдельно зафиксировать QA CPU/cgroup memory и границы whole-call измерения.

## Неизменные границы

Рабочая команда из PDLC2:

```powershell
./check-workspace.ps1 -Profile Build -Repository java-agent -Component cli `
  -NativeDockerContext desktop-linux
```

Сохраняются workers=2, heap/forks, full manual graph, test inventory, backend,
bot и Shared unit consent, exact quality/SDK/UI/skills/operator pins и runtime.
Общий host slot один. Compiler cache и configuration cache не включаются.
При занятом slot сохраняется original attempt; чужая работа не останавливается.
Integration/slow/release/coverage/live/8h выполняются отдельной ручной вехой.

До actual native результата новая source декларация не считается доказательством
ускорения, свежего JAR или прохождения units. Выполненные результаты и оставшиеся
ограничения будут зафиксированы в каноническом плане Base.

## Найденная ошибка подготовки

Первый native image build на commit `a5a14776` отказал до JavaCompile/Unit:
Spring Boot 4.1 определяет `properties.time` как `Property<String>`, тогда как
стабильный clock передавался как `java.time.Instant`. Сохраняется тот же frozen
epoch, но Gradle DSL получает его ISO-8601 строку через `Instant.toString()`.
Исправлена одна и та же причинная граница в CLI, Bot и Backend. CLI проходит
следующую native веху; Bot/Backend этим не объявляются native-qualified.

## Обязательный packaging contract

На `be6792f6` actual CLI graph завершился успешно: 184 units, 0 failures/skips,
JAR task выполнен. При сборе JAR обнаружен отсутствующий `java-artifacts.json`;
общий native report остался failed. Этот partial proof не выдаётся за готовый
artifact или разрешение reuse. Старые логи/JUnit/context/resources сохранены.

Добавлен существующий Base v1 artifact contract с проверенными product module,
filename, Start-Class и notices. Bot output также объявлен точным именем,
cache consent Bot/Backend/Shared прежний. Новый JSON — реальный packaging input,
не обновление SDK/tooling pin. Перед следующей native попыткой typed contract
проверяется отдельно; существующий успешный JAR не был передан на host, поэтому
без нового producer его приёмку подтвердить нельзя. Base переносит этот guard
до image build, чтобы такой поздний отказ не повторялся в следующих версиях.
