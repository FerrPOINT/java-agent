# ADR-016: Точный JDK и существующие Gradle wrappers

- Дата: 2026-10-04.
- Статус: предложено; приёмка exact-source local/hosted gates обязательна.
- План: [воспроизводимость сборок](../../plan/2026-10-04-build-reproducibility.md).

## Решение

CI и четыре Dockerfiles используют Temurin 25.0.4.1+1-LTS. Docker закрепляет
manifest digest для JDK и JRE. Bot/CLI больше не выбирают JDK через плавающий
Gradle image: Gradle загружается существующим committed wrapper.
Backend standalone wrapper 9.8.0 и workspace wrapper 9.6.1 проверяются отдельно;
они не объявляются одним инструментом сборки без соответствующих прогонов.

`setup-java@v6` сравнивает Adoptium `version_data.semver`, поэтому используется
`25.0.4+101.0.LTS`; фактический runtime дополнительно проверяется в CI.
Четырёхкомпонентное OpenJDK обозначение не подставляется в SemVer вслепую.
Источник: [Temurin installer v6](https://github.com/actions/setup-java/blob/v6/src/distributions/temurin/installer.ts).

## Последствия

Повторная сборка не меняет JDK из-за сдвига major tags. Плановое обновление
требует изменения версии/digests и полного релевантного прогона.
Это закрепление JDK не является доказательством live LLM/native PM readiness
или итоговой поставки на merged SHA. Схемы БД и продуктовые контракты сохраняются.
