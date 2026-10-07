# Воспроизводимость Java-сборок после dependency queue

## Основание

План унификации требует точного согласования инструментов CI/Docker.
Плавающие `25` и `gradle:jdk25` допускают другой JDK на повторной сборке.
В production bot JAR отсутствуют три файла, названные в его manifest.

## Изменения

- Сохранить проверенные dependency PR как реальные merge parents в PR #79.
- Закрепить Temurin 25.0.4.1+1-LTS во всех четырёх Dockerfiles и CI.
- Docker использует проверенные immutable manifest digests; bot/CLI builder
  использует тот же JDK и существующий wrapper вместо плавающего Gradle image.
- `setup-java@v6` получает точный Adoptium SemVer `25.0.4+101.0.LTS`, а
  отдельный шаг проверяет фактический `java.runtime.version`.
- Скопировать существующие LICENSE/NOTICE/THIRD_PARTY_NOTICES в bot builder.
- Документировать workspace wrapper 9.6.1 и backend wrapper 9.8.0 раздельно.

## Проверки и поставка

Чистый archive, все четыре image builds, exact JDK version, notices в CLI/bot
JAR; полный Java regression/real PostgreSQL/оба configured coverage floors.
Production full/slim: настоящая PostgreSQL, readiness/migrations/API-key
enforcement. Bot image создавать для осмотра без запуска Telegram polling.
Remote CI должен подтвердить точный JDK; неподтверждённая версия не закрыта.
Runtime pins, данные, миграции, auth и native PM admission не изменять.
PR сохраняется Draft до обязательной приёмки. Любая правка сбрасывает счётчик
трёх полных ревью. Откат — предыдущие проверенные commits/images.
