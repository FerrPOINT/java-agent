# Контекст сборки backend Docker-образа

При обновлении workspace штатный Build остановился на конфигурации Gradle:
`backend/build.gradle` читает `../docs/standards/coverage-baseline.json`, но
Dockerfile переносил только backend и shared. Даже `bootJar -x test` читает
настройки coverage при конфигурации проекта и завершался до компиляции.

Добавить в builder настоящий versioned файл по ожидаемому пути `/docs/standards/`.
Сохранить текущее расположение Gradle-проекта, pinned JDK/wrapper, настройки
coverage, исходники приложения и runtime stage.

Проверки: повторить штатный Docker Build на чистом committed кандидате;
проверить новый образ на сохранённой копии PostgreSQL с миграцией V72,
сверкой прежних записей и повторным запуском. Публикация и установка выполняются
после этих проверок; runtime и данные не входят в исходный patch.
