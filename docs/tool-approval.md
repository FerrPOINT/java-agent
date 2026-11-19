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

Текущий REST contract:

- `GET /api/v1/agent/approvals/pending` возвращает доступные caller запросы.
- `POST /api/v1/agent/approvals/{requestId}/approve` и `/deny` разрешают один
  конкретный запрос с проверкой владельца его session. Неизвестный ID — HTTP404.
  Решение задаётся операцией маршрута; прежний query `decision` отклоняется.
  Заменённое или уже противоположное решение — HTTP409, без пустого успеха.
- `POST /api/v1/agent/approve` принимает `requestId` либо `all=true`.
  Пустой selector выбирает один текущий запрос; каждый элемент `all` всё равно
  получает только одноразовое решение. `all` вместе с `requestId` отклоняется.
- CLI `/approvals` показывает ID; `/approve-tool <requestId>` и
  `/deny-tool <requestId>` используют именно его. Bot `/approve` и `/approve all`
  подтверждают текущие запросы, `/deny` отклоняет их.

Прежнее поле `scope`, session ID вместо request ID и обещания `session`/`always`
не поддерживаются. Их allowlist не участвовал в реальном execution policy,
постоянное решение не сохранялось. Два неиспользуемых policy owners удалены.
EA/SC callbacks, их local store и keyboard builder также удалены: в исходниках
нет producer, регистрирующего эти prompts. Реальные Clarify callbacks и
OpenAI Runs request-bound approvals остаются отдельными предметными операциями.
Durable business records и применённые миграции этим изменением не затрагиваются.

Scoped qualification очереди и DTO фиксируется отдельно от полного Gradle/API/
CLI/bot gate; source parsing не заменяет compilation и поведенческую проверку.
