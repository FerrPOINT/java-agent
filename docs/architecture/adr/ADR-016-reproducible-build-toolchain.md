# ADR-016: Точный JDK и существующие Gradle wrappers

- Дата: 2026-10-04.
- Статус: предложено; приёмка exact-source local/hosted gates обязательна.
- План: [воспроизводимость сборок](../../plan/2026-10-04-build-reproducibility.md).

## Решение

CI и четыре Dockerfiles используют Temurin 25.0.4.1+1-LTS. Docker закрепляет
manifest digest для JDK и JRE. Bot/CLI больше не выбирают JDK через плавающий
Gradle image: Gradle загружается существующим committed wrapper.
Оба committed wrapper используют Gradle 9.9.1 с проверкой SHA-256. Обновление
охватывает корневые bot/CLI entrypoints и standalone backend: прежние 9.6.1
и 9.8.2 входят в затронутый диапазон
[GHSA-3cfg-jpcr-gfp3](https://github.com/gradle/gradle/security/advisories/GHSA-3cfg-jpcr-gfp3).
Версия JDK и Docker digests сохраняются.

Два рецепта сборки сохраняются: у workspace и standalone backend различаются
имя JAR, manifest, build-info и включение notices. Ранее runtime-зависимости
и BOOT-INF были сверены на прежних wrappers; это историческая проверка, а не
повторная квалификация нового артефакта. Обновление Gradle не объединяет рецепты
и не меняет потребителей поставки. Их объединение требует отдельного решения.

`setup-java@v6` сравнивает Adoptium `version_data.semver`, поэтому используется
`25.0.4+101.0.LTS`; фактический runtime дополнительно проверяется в CI.
Четырёхкомпонентное OpenJDK обозначение не подставляется в SemVer вслепую.
Источник: [Temurin installer v6](https://github.com/actions/setup-java/blob/v6/src/distributions/temurin/installer.ts).

## Последствия

Повторная сборка не меняет JDK из-за сдвига major tags. Плановое обновление
требует изменения версии/digests и полного релевантного прогона.
Это закрепление JDK не является доказательством live LLM/native PM readiness
или итоговой поставки на merged SHA. Схемы БД и продуктовые контракты сохраняются.
