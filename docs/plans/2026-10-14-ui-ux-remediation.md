# Доработки UI/UX 2026-10-14

UX-13. /help показывает короткий старт, /help all — полный registry с сохранёнными aliases; неизвестные аргументы объясняют варианты. Бот проверяется через BotMessageProcessor и тестовый TelegramClient без внешней отправки.

Общие контракты и тексты: [стандарт Base](https://github.com/FerrPOINT/services-base/blob/6adaf0961c96d613746c60a4b37f6104bfb35917/docs/platform/UI_UX_STANDARD.md).

Изменённые CLI/registry/handler тесты и путь отправки в тестовый transport проходят. Linux CLI check проходил. Полный Gradle gate не зелёный: Linux-прогон имел DNS/асинхронные падения неизменённого backend/bot, native Windows — платформенные пути/shell и отдельный slow test. Причинная проверка не заменяет этот gate.

Зависимость: [Base PR #206](https://github.com/FerrPOINT/services-base/pull/206). Кандидат поставляется draft: полная межпродуктовая IAB-приёмка, SSO и 30 направлений переключения ещё не подтверждены. GitHub CI учитывается отдельно; отсутствие запуска из-за billing не объявляется PASS.

Работа и native QA выполняются в PDLC2 без новых Docker-групп. Merge и установка исключены; установка — отдельная задача.
