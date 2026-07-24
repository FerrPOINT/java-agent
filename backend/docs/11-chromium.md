# Chromium auto-install

Приложение может самостоятельно скачивать и запускать Chromium для инструментов браузера. Это позволяет работать с `browser_*` инструментами без ручной установки браузера.

## Включение

```bash
java -jar build/libs/*.jar
```

В dev-профиле авто-старт включён по умолчанию. В остальных профилях переопределите:

```bash
AGENT_CHROMIUM_AUTO_START=true \
AGENT_CHROMIUM_AUTO_INSTALL=true \
AGENT_CHROMIUM_REVISION=1667635 \
java -jar build/libs/*.jar
```

## Настройки

| Переменная окружения | `application.yml` | Значение по умолчанию | Описание |
|---|---|---|---|
| `AGENT_CHROMIUM_AUTO_START` | `agent.chromium.auto-start` | `false` | Автоматически запускать Chromium |
| `AGENT_CHROMIUM_AUTO_INSTALL` | `agent.chromium.auto-install` | `true` | Скачивать Chromium, если не найден |
| `AGENT_CHROMIUM_REVISION` | `agent.chromium.revision` | `1667635` | Конкретная revision Chromium |
| `AGENT_CHROMIUM_DOWNLOAD_URL` | `agent.chromium.download-url` | `https://storage.googleapis.com/chromium-browser-snapshots` | CDN со snapshots |
| `AGENT_CHROMIUM_EXECUTABLE_PATH` | `agent.chromium.executable-path` | — | Явный путь к исполняемому файлу |
| `AGENT_CHROMIUM_HEADLESS` | `agent.chromium.headless` | `true` | Headless режим |
| `AGENT_CHROMIUM_LAUNCH_TIMEOUT_SECONDS` | `agent.chromium.launch-timeout-seconds` | `120` | Таймаут ожидания CDP |
| `AGENT_CHROMIUM_USER_DATA_DIR` | `agent.chromium.user-data-dir` | временная директория | Профиль пользователя |
| `AGENT_CHROMIUM_EXTRA_ARGS` | `agent.chromium.extra-args` | — | Дополнительные аргументы Chrome |

## Порядок поиска исполняемого файла

1. Явный путь из `agent.chromium.executable-path` (если задан и файл исполняемый).
2. Скачанный Chromium в `~/.azhukov-agent/chromium/<platform>/<revision>/<archive>/chrome`.
3. Системный Chrome/Chromium (`google-chrome`, `chromium`, `chromium-browser`).
4. Если ничего не найдено и `auto-install=true`, скачиваем snapshot.

## Директория установки

```text
~/.azhukov-agent/chromium/linux_x64/1667635/
├── chrome-linux/
│   ├── chrome
│   └── ...
├── chrome-linux.zip
└── linux_x64/.revision
```

## Аргументы запуска

Chromium запускается с `--remote-debugging-port=9222` и минимальным набором sandbox-аргументов для headless-окружений:

- `--headless=new`
- `--no-sandbox`
- `--disable-setuid-sandbox`
- `--disable-dev-shm-usage`
- `--disable-gpu`
- `--disable-extensions`
- `--disable-background-networking`
- `--disable-sync`
- `--no-first-run`

## Health indicator

Actuator health проверяет доступность CDP endpoint. При авто-старте Chromium индикатор `chromium` входит в группу readiness.

## Проверка

```bash
curl http://localhost:8090/actuator/health
```

Статус `UP` означает, что Chromium запущен и CDP доступен.

## Тестирование авто-установки

Live-тест требует переменной:

```bash
cd backend
RUN_LIVE_CHROMIUM_TEST=true ./gradlew test \
  --tests 'com.azhukov.agent.tools.browser.ChromiumAutoStartLiveTest'
```

## Замечания

- Используются официальные open-source snapshots Chromium, а не Google Chrome.
- Первый запуск скачивает ~240 MB и может занять 1–3 минуты.
- При graceful shutdown Chromium процесс завершается вместе с JVM.
