# Безопасные ошибки MVC и SSE — SDLC2

Подтверждённые BF-002/BF-004: неверные UUID/числовые параметры попадали в generic
500 с внутренней диагностикой; возврат SseEmitter из exception advice не
поддерживался фактическим Spring MVC resolver.

Исправление сохраняет error DTO и проверку владельца. Ошибки binding
пользовательского ввода возвращают 400/bad_request с безопасным сообщением;
внутренние ошибки конфигурации и неожиданные ошибки — 500/internal без
исходного текста исключения. Детали остаются в серверном журнале.

Если Accept явно предпочитает text/event-stream JSON, обработчики binding,
AgentException и generic exception возвращают конечный синхронный error frame
через ResponseEntity<String>. JSON сериализуется без pretty print, с экранированием
переносов и кавычек. Wildcard/JSON preference сохраняет JSON. Ошибка не превращается
в успешный HTTP 200 и не оставляет незавершённый поток.

Повторные поля Accept эквивалентны одному объединённому списку. Для каждого
формата качество определяется наиболее специфичным media range: точный тип,
затем type/*, затем */*. Поэтому wildcard с высоким q не отменяет низкий q
или явный запрет q=0 точного типа. Равные предпочтения и wildcard без явного
text/event-stream сохраняют JSON-политику. Native Boot MVC регрессии проверяют
выбор формата и статус для domain, internal и binding ошибок.

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

После ревью внутренние ошибки binding (нет конвертера, переменная отсутствует в
шаблоне маршрута) сохраняют 500/internal в JSON и SSE. Если значение передано,
но конвертер вернул null, сохраняется штатная пользовательская ошибка 400.
Добавлены шесть проверок через настоящие MVC argument resolvers: четыре
регрессии внутренних ошибок и два случая missing-after-conversion.
