# Open TODO — found during e2e feature audit (2026-08-23, 0.1.33)

Реальные находки живого аудита, не починенные сразу (нужен дизайн-решение
или подтверждение скоупа). Каждая — с воспроизведением.

## 1. GET /agent/background/{id} не существует — результат background-задачи недоступен

`POST /agent/background` возвращает id **новой сессии** (строкой). Но
эндпоинта «получить статус/результат background-задачи по id» нет:
`GET /agent/background/{id}` → 404 NoResourceFoundException (и это
единственное место, где GlobalExceptionHandler логирует ERROR-стектрейс
на легитимный запрос).

Hermes-паритет: `run_in_background` в Hermes — это job-модель со
статусами (PENDING/RUNNING/DONE) и получением результата. В java — просто
«создали сессию и забыли»: вызвавший не может узнать, чем закончилась
задача, кроме как прочитав историю сессии напрямую.

**Решить:** либо добавить `GET /agent/background/{id}` → {status, result},
либо переименовать ответ в session_id и честно задокументировать. Не
чинилось вслепую: непонятно, хочет ли пользователь job-модель.

## 2. reasoning-effort уровни CLI ≠ backend

CLI (`CliState.REASONING_LEVELS`): none, minimal, low, medium, high, xhigh
Backend (новая валидация): none, minimal, low, medium, high, xhigh, **max, ultra**

Hermes поддерживает max и ultra. CLI-хелпер /reasoning их не показывает и
(надо проверить) может не пропустить. Синхронизировать наборы: вынести в
одно место (общий класс или properties).

## 3. Health-endpoint тяжёлый (до 30+ секунд)

`/actuator/health` включает browser/chromium-проверки — при старте chromium
health отвечает >10s, e2e-раннеры падают по таймауту (пришлось поднимать до
60s). Вынести тяжёлые компоненты из liveness/readiness или сделать их
caching.

## 4. Heartbeat delivery poller — нет метрик/логов доставки

HeartbeatDeliveryPoller шлёт в Telegram, но при ошибке только nack+retry
(макс 5). Нет journalctl-события «heartbeat доставлен/потерян» — при
разборе «почему юзер не получил heartbeat» придётся гадать. Добавить
INFO-лог доставки + счётчик в /actuator/metrics.
