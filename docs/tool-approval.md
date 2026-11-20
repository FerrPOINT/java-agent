# Подтверждение исполнения инструментов

Единственный владелец policy — `ToolApprovalPolicy` / `DefaultToolApprovalPolicy`.
`TurnExecutor` применяет её к batch в обычном и streaming turn. Policy и
`ApprovalQueue` обязательны; ошибка конфигурации или создания запроса прекращает
исполнение. Отключение approvals выполняется явной настройкой policy, а не
отсутствующей зависимостью. MCP admission проверяется отдельно и не отключается
вместе с подтверждениями пользователя.

Каждый опасный вызов создаёт свой `requestId`. Решение одноразовое: очередь
атомарно потребляет только этот запрос. Замена запроса в той же session
освобождает старое ожидание, но старый ответ, timeout или cleanup не разрешает
новый вызов и не удаляет его запрос. Отсутствующий, просроченный или заменённый
запрос не означает согласие. Исполнение требует явную session и не заимствует
ambient guardrail scope.
Clarify и review HTTP routes также требуют UUID session: невалидный строковый
ключ не пропускает проверку владельца и отклоняется до чтения store.

## REST и команды

- `GET /api/v1/agent/approvals/pending` возвращает доступные caller запросы.
- `POST /api/v1/agent/approvals/decisions` принимает `action` со значением
  `APPROVE` или `DENY`, а также необязательное поле `note`. Для выбора передают
  `requestId` либо `all: true`. Без селектора обрабатывается один доступный
  запрос. Совместное указание ID и `all: true` отклоняется с HTTP 400.
- Сервер проверяет владельца каждой целевой сессии до решения. Неизвестный ID
  возвращает HTTP 404. Запрос, который по-прежнему известен, но уже не допускает
  решение, возвращает HTTP 409. Для `all: true` результат сообщает число
  подтверждённых или отклонённых запросов.
- В CLI и Telegram команды `/approve` и `/deny` принимают `requestId` или `all`.
  CLI `/approvals` показывает ID, Telegram `/approvals` показывает текущий
  режим подтверждений и сообщает ошибку, если backend не вернул настройку.

Прежнее поле `scope`, session ID вместо request ID и обещания `session`/`always`
не поддерживаются. Их allowlist не участвовал в реальном execution policy,
постоянное решение не сохранялось. Два неиспользуемых policy owners удалены.
EA/SC callbacks, их local store и keyboard builder также удалены: в исходниках
нет producer, регистрирующего эти prompts. Реальные Clarify callbacks и
OpenAI Runs request-bound approvals остаются отдельными предметными операциями.
Durable business records и применённые миграции этим изменением не затрагиваются.

Scoped qualification очереди и DTO фиксируется отдельно от полного Gradle/API/
CLI/bot gate; source parsing не заменяет compilation и поведенческую проверку.
