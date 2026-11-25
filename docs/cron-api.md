# Cron API

Единственный CRUD contract — `/api/v1/agent/cron`, владелец HTTP —
`CronJobController`, владелец изменений и валидации задания — `CronJobService`.
CLI и Telegram bot используют этот prefix. `/api/jobs`, `/api/cron/jobs` и их
`/p/{profile}` варианты удалены вместе с compatibility controllers.

| Операция | Метод и путь |
| --- | --- |
| Создать | `POST /api/v1/agent/cron` |
| Список | `GET /api/v1/agent/cron?profile=team&includeDisabled=true` |
| Карточка | `GET /api/v1/agent/cron/{uuid}` |
| Изменить | `PUT /api/v1/agent/cron/{uuid}` |
| Удалить | `DELETE /api/v1/agent/cron/{uuid}` |
| Пауза | `POST /api/v1/agent/cron/{uuid}/pause` |
| Возобновление | `POST /api/v1/agent/cron/{uuid}/resume` |
| Запуск | `POST /api/v1/agent/cron/{uuid}/run` |
| История исполнения | `GET /api/v1/agent/cron/{uuid}/executions` |

Идентификатор — полный UUID. Профиль передаётся полем `profile` при создании
и query-параметром списка; отдельного профильного CRUD нет. При отсутствии
профиля создание использует `default`, список — все видимые пользователю
профили. Автор берётся из проверенного `UserContext`, а не из JSON. Профиль
не расширяет доступ к чужим заданиям; изменение и attached session проверяет
существующая сервисная граница владельца.

Сервис получает владельца явно: REST/blueprints передают ID проверенного
пользователя, tool — владельца текущей сессии. Query scope администратора
не используется как owner при создании. `null` означает глобальное задание
и разрешён только административному или внутреннему вызову; существующие
глобальные задания сохраняют эту семантику.

Создание требует `name` и `schedule`. Payload содержит prompt, script или
skills; `noAgent=true` требует script. Остальные поля: `deliverTo`, `skills`,
`contextFrom`, `enabledToolsets`, `workdir`, `repeatCount`, `script`, `noAgent`,
`modelProvider`, `modelName`, `baseUrl`, `monitor`, `continuityEnabled`,
`attachedSessionId`. `monitor` — одно поле источника наблюдения, без aliases
`monitor_script`/`monitor_url`. Повтор без положительного `repeatCount`
не ограничен количеством запусков; это текущая семантика сервиса.

PUT сохраняет обязательные `name`/`schedule`; остальные nullable поля оставляют
значение без изменения. Пустые script/monitor очищают настройку по правилам
сервиса. `attachToSession=true` требует `attachedSessionId`, `false` снимает
привязку. Профиль задания через PUT не меняется. Неизвестные поля, включая
прежние snake_case aliases и попытку передать `userId`, отклоняются.

Blueprints, suggestions и heartbeat — отдельные операции того же текущего
контроллера, а не другие версии CRUD. Существующие Cron rows, UUID, owner,
профиль, конфигурация и история исполнения не преобразуются при удалении
прежних HTTP adapters. Браузерный dashboard в этом checkout отсутствует;
совместимость внешнего клиента со снятыми routes не эмулируется.
