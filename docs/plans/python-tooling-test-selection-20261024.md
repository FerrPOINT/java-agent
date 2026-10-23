# Точный выбор Python helper tests

Реализация утверждённого плана PDLC: известные helper test paths не должны
выбирать application producers через unknown fallback.

1. Проверить исполнение и полный source-input closure каждого модуля.
2. Добавить regression через фактический packaged selector: Fast/Unit/Build,
   неизвестный script, соседний application input и CI module ownership.
3. Добавить exact ownership и отдельные pure Python gates, не меняя существующие
   artifact/native/manual gates и pins. Согласовать CI без двойных unit modules.
4. Выполнить scoped Python checks и публичный root PlanOnly. Production builds,
   ручные integration/slow/release и runtime для этой source-правки не нужны.

Проверка source-сценария не является native build/resource qualification.

## Результат 24 октября

После исправления Windows controller guard source run:26cases, один неверный соседний path в новом routing test.
Остальные23вне routing прошли; selection group6PASS1,530с после исправления на
Java input. Финальный routing использует существующий NoOpModelClient.java и
один actual HEAD read на класс, повторены его3cases. Docs/default checks не
повторялись без изменения их кода/источников. Public root Unit/tooling PlanOnly
exit0. Это union scoped source checks, без нового JVM/Gradle/JAR/resource proof;
application install/export/build0.

Quality/SDK/UI/skills/operator pins и соседние workspace не менялись.
