# План: ограниченные HTTP healthchecks и reaping

## Причина и изменение

Прежний shell-healthcheck мог оставить HTTP-клиент после внешнего timeout.
В Compose используются прямой CMD, внутренний timeout короче engine timeout
и штатный init. Для body-based проверки бота сохранён status UP и ограничен curl.
GNU wget и Alpine/BusyBox используют разные поддерживаемые наборы аргументов.
API, данные, endpoints, intervals/retries, images и SDK/UI pins не меняются.

## Проверка и поставка

Проверить YAML/Compose, фактический клиент образа, 200/503, body predicate
и зависший ответ без потомков. Source-check и установка — отдельные evidence.
Изменение Compose не требует пересборки неизменившегося приложения, но требует
пересоздания выбранного сервиса для Init/Healthcheck. Сохранить image, mounts,
env и активные задания; БД и другие сервисы не пересоздавать побочно.
Kubernetes native HTTP probes не создают этих exec-процессов; общая приёмка
Docker/Kubernetes остаётся раздельной. Production этой доработкой не меняется.

Порядок отката: восстановить только task-owned Init/Healthcheck поверх текущего
config, проверить изменения после baseline и пересоздать выбранный сервис.
Старый вариант возвращает исходный риск и не является предпочтительным откатом.
