# Open TODO — manual audit (2026-08-23, 0.1.34) + e2e audit (0.1.33)

## FIXED in 0.1.34 (was below): checkpoint snapshots captured the world
~~checkpoints «9999 files / 128 MB каждый»~~ — ИСПРАВЛЕНО: gitignore-aware
walk с pruning (Hermes DEFAULT_EXCLUDES + правила .gitignore от корня
обхода, вложенные git-репы больше не резолвятся как корень). 6085 файлов
112 МБ → 1340 файлов 8.4 МБ; распухшая таблица checkpoint_files почищена
1531 МБ → 6.2 МБ. /sessions и /resume показывали пустой список (identity
drift default vs user-1) — единая константа AgentProperties.DEFAULT_USER_ID.
/config и /doctor падали (Spring 7 не материализует абстрактный JsonNode
через конвертер) — читаем String и парсим.

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


## New from manual pass (2026-08-23, 0.1.34)

### 5. /editor без TTY запускает vim и сыплёт мусором

В piped/non-interactive режиме `/editor true` запускает $EDITOR (vim),
который без терминала печатает «Vim: Error reading input, exiting...»
в поток вывода. Hermes детектит isatty и отказывает с внятным сообщением.
**Решение:** проверка `System.console() != null` перед запуском внешнего
редактора; без TTY — сообщение «/editor requires an interactive terminal».

### 6. CLI /checkpoint и /checkpoints не показывают описание restore-семантики

`/checkpoints` выводит список, `/checkpoint` создаёт, но в CLI нет
`/restore <id>` (в Hermes-CLI есть). Restore доступен только через REST.
**Решение:** добавить /restore в CLI (endpoint уже есть).

### 7. /usage показывает только счётчики сессии, не стоимость

Hermes /usage показывает токены + стоимость по моделям. Java /usage —
только messageCount/tokenEstimate текущей сессии. Данные есть в
/agent/credits (агрегат) и /agent/insights (byModel).
**Решение:** расширить /usage выводом credits+insights текущей сессии.

### 8. Restart-семантика /restart отличается от Hermes

Java `/restart` очищает все сообщения юзера (деструктивно, без
подтверждения). Hermes /restart перезапускает agent-процесс, историю
сохраняет. Уточнить желаемую семантику у владельца; сейчас лучше не
вызывать в проде с важными сессиями.
