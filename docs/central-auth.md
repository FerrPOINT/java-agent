# Общий вход и владелец сессии Java Agent

При `agent.central-auth.enabled=true` Java принимает пользовательский bearer JWT
собственного Auth. Проверяются ES256, issuer, audience, сроки, UUID subject и
UUID session ID; затем тем же bearer выполняется `/auth/me` для проверки
действующей сессии. Отозванная сессия перестаёт давать доступ без ожидания
истечения JWT.

Настройки задаются для конкретного deployment:

| Property | Назначение |
| --- | --- |
| `agent.central-auth.enabled` | Включение общего входа; по умолчанию выключено |
| `agent.central-auth.issuer` | Публичный issuer собственного Auth |
| `agent.central-auth.jwks-uri` | Доступный Java endpoint собственного Auth `/oidc/jwks` |
| `agent.central-auth.audience` | Ожидаемая audience, для SDLC — `sdlc` |
| `agent.central-auth.session-uri` | Доступный Java endpoint того же Auth `/auth/me` |

Все четыре значения обязательны при включении режима. Для environment variables
используется стандартное Spring-преобразование, например
`AGENT_CENTRAL_AUTH_ENABLED` и `AGENT_CENTRAL_AUTH_JWKS_URI`. Публичный issuer
и внутренний сетевой адрес Auth могут различаться; они должны относиться к одному
стенду. В образе объявляется capability `sdlc.java.central-auth-contract=v2`.

Global API key, per-user legacy key и dev principal не служат fallback в этом
режиме. Пользователь получает свою область сессий и `ROLE_USER`; значение
`userId` в запросе не меняет подтверждённого владельца. Существующая identity
переносится в фоновый stream, а после обработки очищается. Идентификатор
владельца также проверяется при загрузке сессии через общий resolver.

Неверный JWT или неактивная сессия дают HTTP401. Недоступность Auth или
некорректный ответ дают HTTP503. Session readback имеет общий deadline 5 секунд,
предел ответа 64KiB и запрет redirects. Bearer не выводится в диагностике.
Базовые health endpoints доступны без пользовательской сессии.

Standalone-режим при выключенном общем входе сохраняет существующие API keys
и dev behavior. Для него остаются прежние требования к global key в `prod`.
Настройка не создаёт аккаунтов или ролей Auth и не включает исполнение агентов.
Права глобальных control API и machine/project/task authorization проверяются
отдельно; эта интеграция сама по себе не подтверждает изоляцию всего Java API.

При обновлении действующего стенда сохраняются БД, signing keys, issuer и
существующие сессии. Runtime меняется отдельно от публикации исходников.
