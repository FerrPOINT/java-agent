# 06 — Vision & Browser: Re-adding to Scope

## 1. Decision

Vision и browser возвращаются в порт. Оба используют Kimi / Moonshot / OpenAI-compatible endpoints через существующий `ModelClient`, поэтому не требуют отдельных тяжёлых SDK.

## 2. Vision

### 2.1 Функционал

- `vision_analyze` — анализ изображения по URL или локальному пути с пользовательским prompt.
- Поддержка модели `kimi-k2.7-code` (multimodal) или любой OpenAI-compatible vision-модели.
- Изображение кодируется в base64 и передаётся в массиве `content` вместе с текстом.

### 2.2 OpenAI-compatible формат

```json
{
  "model": "kimi-k2.7-code",
  "messages": [
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "What is in this image?"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}
      ]
    }
  ]
}
```

### 2.3 Java-классы

```
hermes-core/src/main/java/com/nous/hermes/core/tools/
├── VisionAnalyzeTool.java
├── VisionImageUrlTool.java
└── support/
    └── ImageEncoder.java
```

### 2.4 Конфигурация

- `hermes.vision.model` — модель по умолчанию (`kimi-k2.7-code`).
- `hermes.vision.max-size` — лимит на размер файла (например, 50 MB).
- `hermes.vision.download-timeout` — timeout для скачивания URL.
- Секреты: `KIMI_API_KEY` / `MOONSHOT_API_KEY` / `OPENAI_API_KEY` через env.

### 2.5 Что нужно от `tools/vision_tools.py`

- `_download_image()` — скачивание с лимитом и timeout.
- base64-кодирование.
- fallback-цепочку провайдеров (Kimi → OpenRouter → Anthropic → custom endpoint).
- redactor для URL с credentials.

## 3. Browser

### 3.1 Функционал

Браузер в Hermes имеет два режима. Для Java-прототипа оставляем **local Chromium через CDP** — он не требует облачных API-ключей и работает на headless-сервере.

- `browser_navigate` — открыть URL.
- `browser_snapshot` — получить accessibility tree / DOM snapshot.
- `browser_click`, `browser_type`, `browser_scroll`, `browser_back`, `browser_press` — действия.
- `browser_console` — читать консоль.
- `browser_vision` — скриншот + анализ через `vision_analyze`.
- `browser_cdp` — низкоуровневый CDP passthrough (ограниченный).

### 3.2 Архитектура

```
┌─────────────────┐
│  BrowserPool    │  ← управляет Chromium процессами
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
┌───────┐ ┌─────────┐
│ CDP   │ │ Page    │
│Client │ │Snapshot │
└───┬───┘ └─────────┘
    │
    ▼
┌─────────────┐
│ Chrome      │
│ DevTools    │
│ Protocol    │
└─────────────┘
```

### 3.3 Java-библиотеки

| Задача | Библиотека |
|--------|------------|
| CDP WebSocket client | `java.net.http.WebSocket` или Tyrus |
| JSON-RPC поверх CDP | ручная реализация (CDP — простой JSON-RPC) |
| Chromium management | `ChromeLauncher` (org.seleniumhq.selenium:chrome-driver) или ручной `ProcessBuilder` |
| Accessibility tree | CDP `Accessibility.getFullAXTree` |
| Screenshot | CDP `Page.captureScreenshot` |
| DOM snapshot | CDP `DOMSnapshot.captureSnapshot` |

### 3.4 Альтернатива: Playwright

Microsoft Playwright for Java тоже вариант, но он тянет ~200 MB нативных бинарей и Node. Для прототипа лучше начать с чистого CDP-клиента, чтобы не раздувать зависимости. Playwright можно добавить как опциональный backend позже.

### 3.5 Java-классы

```
hermes-core/src/main/java/com/nous/hermes/core/tools/browser/
├── BrowserTool.java (annotation alias)
├── BrowserPool.java
├── CdpClient.java
├── ChromiumLauncher.java
├── BrowserSession.java
├── BrowserSnapshot.java
└── tools/
    ├── BrowserNavigateTool.java
    ├── BrowserSnapshotTool.java
    ├── BrowserClickTool.java
    ├── BrowserTypeTool.java
    ├── BrowserScrollTool.java
    ├── BrowserBackTool.java
    ├── BrowserPressTool.java
    ├── BrowserConsoleTool.java
    ├── BrowserGetImagesTool.java
    └── BrowserVisionTool.java
```

### 3.6 Конфигурация

- `hermes.browser.local.executable` — путь к `google-chrome` / `chromium`.
- `hermes.browser.local.headless` — `true` по умолчанию.
- `hermes.browser.local.args` — дополнительные args (`--no-sandbox` и т.д.).
- `hermes.browser.cdp-url` — внешний CDP endpoint, если Chromium запущен вне агента.
- `hermes.browser.timeout` — timeout на операции.

## 4. Image Generation

Генерация изображений (FAL) остаётся **out of scope** — она требует FAL SDK / REST и отдельного биллинга. `image_generate` можно добавить позже как опциональный инструмент через MCP или REST wrapper.

## 5. Обновлённый список инструментов

### Берём

- `read_file`, `write_file`, `patch`, `search_files`
- `terminal`, `process`
- `web_search`, `web_extract`
- `vision_analyze` (Kimi / OpenAI-compatible)
- `browser_navigate`, `browser_snapshot`, `browser_click`, `browser_type`, `browser_scroll`, `browser_back`, `browser_press`, `browser_console`, `browser_get_images`, `browser_vision`
- `skills_list`, `skill_view`, `skill_manage`
- `memory`
- MCP client tools

### Пока не берём

- `image_generate` (FAL)
- `browser_cdp` (расширенный) — можно добавить позже
- voice/TTS
- computer-use / desktop UI

## 6. Влияние на архитектуру

- `ModelClient` должен поддерживать multipart content (text + image_url) для vision.
- `ToolExecutor` может запускать CDP-клиент в виртуальном потоке.
- Нужен `BrowserPool` для управления жизнью Chromium процессов и cleanup.
- `vision_analyze` должен уметь fallback между провайдерами, как auxiliary client в Hermes.

## 7. Безопасность

- Vision: ограничить размер скачиваемых изображений, проверить URL (`website_policy`), не передавать credentials в base64-метаданных.
- Browser: запускать Chromium в sandbox, headless, без `--no-sandbox` если возможно; ограничить navigation по allow-list; redactor для CDP URL.
