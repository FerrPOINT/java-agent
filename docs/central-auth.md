# Общий вход и владелец сессии Java Agent

При `agent.central-auth.enabled=true` Java принимает пользовательский bearer JWT
или PAT собственного Auth. Для JWT проверяются ES256, issuer, audience, сроки,
UUID subject и
UUID session ID; затем тем же bearer выполняется `/auth/me` для проверки
действующей сессии, текущей роли и допуска. PAT проверяется существующим endpoint
Auth `/auth/tokens/introspect?include_lifetime=true`; разрешение приложения и
точный `java-agent:read` либо `java-agent:write` scope проверяются совместно. Отозванная сессия перестаёт давать доступ без
ожидания
истечения JWT.

Настройки задаются для конкретного deployment:

| Property | Назначение |
| --- | --- |
| `agent.central-auth.enabled` | Включение общего входа; по умолчанию выключено |
| `agent.central-auth.issuer` | Публичный issuer собственного Auth |
| `agent.central-auth.jwks-uri` | Доступный Java endpoint собственного Auth `/oidc/jwks` |
| `agent.central-auth.audience` | Ожидаемая audience, для SDLC — `sdlc` |
| `agent.central-auth.session-uri` | Доступный Java endpoint того же Auth `/auth/me` |
| `agent.central-auth.personal-token-uri` | Фиксированный endpoint того же Auth `/auth/tokens/introspect`, обязателен для PAT |
| `agent.central-auth.application-access-required` | Требовать enforced proof; по умолчаниюfalse для совместимого rollout |

Все четыре значения обязательны при включении режима. Для environment variables
используется стандартное Spring-преобразование, например
`AGENT_CENTRAL_AUTH_ENABLED` и `AGENT_CENTRAL_AUTH_JWKS_URI`. Публичный issuer
и внутренний сетевой адрес Auth могут различаться; они должны относиться к одному
стенду. В образе объявляется capability `sdlc.java.central-auth-contract=v2`.

Global API key, per-user legacy key и dev principal не служат fallback в этом
режиме. Пользователь получает свою область сессий и текущую роль `user` либо `admin`
из единого ответа Auth. JWT role hint и прежняя локальная роль не дают полномочий.
Допуск к `java-agent` не даёт административных прав. Даже человеческий admin
не может передать другого автора через `userId`; технический standalone gateway
сохраняет свой отдельный контракт. В фоновый stream и поток инструмента
передаётся тот же proof с первоначальным сроком, после обработки контекст очищается. Идентификатор
владельца также проверяется при загрузке сессии через общий resolver.

Неверный JWT или неактивная сессия дают HTTP401. Недоступность Auth или
некорректный ответ дают HTTP503. Session readback имеет общий deadline 5 секунд,
предел ответа 64KiB и запрет redirects. Отсутствие приложения или PAT scope даёт
HTTP403 без нового SSO-цикла; malformed proof и недоступный обязательный
PAT endpoint дают503. Исходный срок proof, JWT/PAT и monotonic window не
продлеваются. SSE callbacks закрывают поток по истечении; новая итерация и
инструмент проверяют текущий срок до запуска. Bearer не выводится в диагностике.
Базовые health endpoints доступны без пользовательской сессии.

Standalone-режим при выключенном общем входе сохраняет существующие API keys
и dev behavior. Для него остаются прежние требования к global key в `prod`.
Настройка не создаёт аккаунтов или ролей Auth и не включает исполнение агентов.
Права глобальных control API и machine/project/task authorization проверяются
отдельно; эта интеграция сама по себе не подтверждает изоляцию всего Java API.

При обновлении действующего стенда сохраняются БД, signing keys, issuer и
существующие сессии. Runtime меняется отдельно от публикации исходников.

## SDK и незавершённая поставка

Канон JVM SDK — Base `base-modules/java/auth-core`, точная ревизия в
`.base-revision`. Встроенный `com/pdlc/auth/HumanApplicationAccess.java` выпускается
каноническим `scripts/export_java_access.py`; `--check` обнаруживает drift.
Новый runtime, Gradle-проект или локальная копия политики не создаются.
Каталог Auth должен содержать стабильный ключ `java-agent`; это отдельная
операторская конфигурация, не автоматическая выдача всем пользователям.

Это feature-исходники. Request/stage guards не доказывают всю матрицу API,
DB commit fencing, повторную owner-проверку длительного задания, межпродуктовую
цепочку или независимый допуск к пространствам. Контроль общих tools, UI,
миграция, полный совместимый cohort, Docker/Kubernetes кандидата и dev/IAB
приёмка остаются обязательными до активации. Историческая capability общего
входа v2 сама по себе не подтверждает готовность новой политики.
