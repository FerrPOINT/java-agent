# Публикация точного legacy model hint

Восстановить локальное изменение family matcher: отдельный hint для exact
`deepseek-v4.1-flash`, тесты bare/provider/OpenRouter ID и неизвестной версии.
Это локальная таблица подсказок для прежних клиентов, не доказательство
физического лимита, доступа к провайдеру или разрешение увеличить execution budget.
Другие семейства, unknown fallback и настройки исполнения сохраняются.
Проверка: ModelMetadataServiceTest на committed backend wrapper и pinned JDK.
