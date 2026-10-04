# Безопасные ошибки MVC и SSE — SDLC2

Подтверждённые BF-002/BF-004: неверные UUID/числовые параметры попадали в generic
500 с внутренней диагностикой; возврат SseEmitter из exception advice не
поддерживался фактическим Spring MVC resolver.

Исправление сохраняет error DTO и проверку владельца. Ошибки binding возвращают
400/bad_request с безопасным сообщением; неожиданные ошибки — 500/internal без
исходного текста исключения. Детали остаются в серверном журнале.

Если Accept явно предпочитает text/event-stream JSON, обработчики binding,
AgentException и generic exception возвращают конечный синхронный error frame
через ResponseEntity<String>. JSON сериализуется без pretty print, с экранированием
переносов и кавычек. Wildcard/JSON preference сохраняет JSON. Ошибка не превращается
в успешный HTTP 200 и не оставляет незавершённый поток.

Приёмка 04.10.2026: реальные MockMVC binding/content negotiation, 7231 backend
тест (0 failures, 0 errors, 1 skip), bootJar, documentation ratchet. Теги live,
slow и e2e не входили в этот unit gate. После Build/Apply SDLC2 восемь неверных
UUID/limit/offset запросов вернули 400 в JSON/SSE; чтение владельцем — 200,
чужим пользователем — 403, анонимным/invalid token — 401.

Полные receipts и границы проверки: workspace
`.local/bugfix-round2-20261004/REPORT.md`. Вызовы платных провайдеров не выполнялись.

Перед публикацией PR проверена отдельная ветка от актуальной main: 7273 backend
теста под обычным Linux-пользователем, 0 failures/errors, 1 skip; bootJar и
documentation ratchet прошли. Публичный PR содержит только обработчик ошибок,
связанные регрессии и этот план.

## Ревью согласованного кандидата

PR79 включает исходную голову81 и сохраняет проверенные dependency/JDK/notices
исправления. R35: wildcard quality не должен переопределять явный JSON range.
Настоящий MockMVC воспроизводил два ответа JSON вместо выбранного SSE, включая
`application/json;q=0`. Выбор сначала учитывает specificity, затем quality;
`*/*` сохраняет JSON по умолчанию, text wildcard поддерживает SSE, запрет SSE
не обходится wildcard. Новый helper использует существующий Spring MediaType.

Объединённый candidate прошёл 7283 backend tests
(1 прежний skip), shared tests,23 настоящих PostgreSQL
регрессии без пропусков, bootJar и оба configured coverage floors. JDK точно
25.0.4.1+1 LTS. Два production image прошли 30 actual readiness/API-key/
malformed JSON/SSE/Accept checks. Направленные R35 tests прошли после двух
исходных отказов. HTTP transport в live gate не подменялся; provider inference
и live Telegram не запускались. Первый QA runner без git и ошибочный Gradle
filter сохранены как ошибки проверки; успешный повтор использовал тот же код.
Hosted CI итогового commit и поставка на merged SHA проверяются отдельно.
Новые миграции/зависимости/env не добавляются; полная задача и три чистых ревью
остаются отдельной приёмкой.
