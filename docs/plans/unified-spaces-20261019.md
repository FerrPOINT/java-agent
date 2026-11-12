# Единое пространство PDLC: Java Agent

Статус: **согласованная цель**, интеграция в работе; совместимость runtime не подтверждена.
Общий договор — Base ADR 0010 и `docs/contracts/UNIFIED_SPACES_V1.md`.

Java Agent остаётся исполнителем и владельцем своих сессий, сообщений и артефактов.
Каталог Space принадлежит Admin, связи Project/Space — Tracker. Второй каталог,
автоматический all и подстановка первого Project/Space не допускаются.

## Классификация и границы

- Личные сессии используют существующего подтверждённого владельца Java Agent;
  центральный JWT сам по себе не даёт управления глобальными control API.
- Проектное выполнение требует явного ProjectRef и ограниченного машинного
  полномочия от владельца задания. SpaceRef обязателен, если нужна Wiki.
  Текст prompt, путь checkout, email и произвольный session ID не являются proof.
- Fleet-managed процесс и отдельно установленный Java Agent — разные точки
  исполнения. Проверка одного не квалифицирует другой.
- Loopback/private network не заменяют авторизацию. API, SSE/replay, session
  history, вложения, tools/MCP, downloads, CLI и Telegram должны сохранять owner
  scope. Проектные данные дополнительно ограничены общим admission.
- Личные и глобальные ресурсы сохраняют собственные явно описанные права;
  отсутствие ProjectRef не делает ресурс публичным или общеплатформенным.

## Реализация и приёмка

1. Зафиксировать serving inventory и цепь Fleet/Workflow -> execution -> callbacks.
   [Central Auth](../central-auth.md) подтверждает только identity/session contract.
2. Сохранить строгие старые context DTO; добавить отдельный V3 adapter. Проверить
   действительные task/project/space связи и scopes/delegation без выбора default.
3. Перед эффектом и после ожиданий проверять общий исходный deadline <=60s,
   токен и machine grant. Cache/relay/stream reconnect не продлевают его.
4. Согласовать новый start и подтверждённый drain с владельцем lifecycle; timer
   и исчезновение записи не заменяют терминальное подтверждение задания.
5. Проверить owner isolation и проектный допуск через реальные API/stream/files,
   затем включить только в полном совместимом cohort. Старый образ без admission
   не может служить rollback после активации.

Human Central Auth дополнен проверкой свежего human kind/role, без повышения
личного gateway scope. Это не квалифицирует проектное машинное исполнение;
его serving inventory и отдельный grant остаются обязательны. Production rollout
разрешён пользователем 21 октября, но до integrated/dev/rollback приёмки не выполняется.
Hermes и посторонние приложения не затрагиваются. Полный build — на интегрированной вехе, scoped checks по ходу.
