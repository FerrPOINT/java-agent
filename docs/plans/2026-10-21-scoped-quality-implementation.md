# Java3: source partition и штатные scoped entrypoints

Дата: 2026-10-21. Статус: source implementation; native/hosted qualification OPEN.
Основание: [prerequisites](2026-10-21-scoped-quality-prerequisites.md).
Операционные команды и manual gates — [TESTING](../TESTING.md).

## Реализовано

- Перенесены существующие Gradle partition/topology/membership и validator из
  reviewed reference3c17f478; module dependencies и pinned wrappers сохранены.
- Reviewed inventory покрывает852Java/Groovy test/support files:583unit,
  183integration,68slow,9live и9support. Это source counts, не native test results.
  Root timings не переносятся на peer.
- Browser fake WebSocket выделен в shared fixture; Gateway test использует
  событие вместо sleep. Production Gateway body совпадает с reference.
  Старый polling lifecycle сохранён отдельно от прямой проверки counter rule.
  TokenLog с real HTTP client остаётся integration, а не fake unit.
- Testcontainers dependencies принадлежат manual compilation. Остальные
  application dependencies и processors пока сохраняются. Backend forks1 и
  forkEvery750 прежние; heap ceilings не уменьшаются без измерения.
- Unit/manual compilation и resource outputs независимы; validator проверяет
  запрещённые зависимости в обе стороны и требует shared fixture.
- Один Make dispatch на совместимый wrapper; stable candidate epoch/buildInfo
  и notices сохраняются. Clients profile не конфигурирует backend. Raw Docker
  builders получают partition inputs, выполняют units и ограничивают workers2;
  runtime stages не меняются, `.local` не попадает в context.
- Descriptor объявляет backend/bot/CLI JAR producers. CI переводит канонический
  Base plan в typed task arrays и объединяет совместимые tasks, не вводя второго
  selector/executor/cache framework. Изменение Shared test не выбирает приложения.
- Full coverage/manual CLI/Shared inventory и baseline floors сохраняются.
  Найденный test-only CLI release gap закрыт явными integration/slow tasks;
  verifier выполняет blocking coverage ratchet. Backend JAR передаётся с SHA256.

## Граница внедрения

Standalone selector — exact81467ded через Base packager без optional helpers.
Root quality остаётся a36cd64f. Native artifact cacheable=false; автоматический
configuration-cache default и compiler-cache opt-in peer не квалифицированы.
Источник current wrapper/JDK и dependency closure учитывается существующим
gradle-context helper; он возвращает reuse_qualified=false.

Сейчас проверяются source/selection, pure Python contracts, Bash syntax и YAML
objects. Actual Gradle/JVM/JAR, Docker/hosted/resource consumption и live не
доказываются этими checks. На интегрированной вехе нужны actual source-set API
fixture, pinned native unit/JAR/classpath и invalidation checks. Общая цель Base
и исходные manual release/live/soak gates остаются открытыми.
