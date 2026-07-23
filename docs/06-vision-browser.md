# 06 — Vision & Browser

Current stack: Java 25 LTS + Spring Boot 4.1.0 + Gradle 9.6.1 (Groovy DSL) + Groovy 5.0.7 + PostgreSQL 16 + OpenAI-compatible LLM endpoint.

## 1. Decision

Vision and browser are in scope. Both use the same `ModelClient` with OpenAI-compatible endpoints, so no separate heavy SDKs are required.

## 2. Vision

### 2.1 Functionality

- `vision_analyze` — analyze an image from URL or local path with a user prompt.
- Default model: configured by `agent.vision.model-name`; dev default falls back to the main model.
- Image is base64-encoded and passed in the `content` array alongside text.

### 2.2 OpenAI-compatible format

```json
{
  "model": "kimi-k2.6",
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

### 2.3 Java classes

```
backend/src/main/java/com/azhukov/agent/tools/
└── vision/
    └── VisionAnalyzeTool.java
```

### 2.4 Configuration

- `agent.vision.model-name` — vision model name.
- `agent.vision.timeout-seconds` — request timeout (default 600).
- `agent.vision.use-auxiliary-first` — try auxiliary model before main model.
- Secrets via env: `AGENT_VISION_API_KEY`, or reuse the main model provider.

## 3. Browser

### 3.1 Functionality

Browser uses **local Chromium via CDP** — no cloud API keys and it runs on headless servers.

- `browser_navigate` — open URL.
- `browser_snapshot` — accessibility tree / DOM snapshot.
- `browser_click`, `browser_type`, `browser_scroll`, `browser_back`, `browser_press` — actions.
- `browser_console` — read console.
- `browser_vision` — screenshot + analysis via `vision_analyze`.

### 3.2 Architecture

```
┌─────────────────┐
│  BrowserService │  ← manages tabs, high-level operations
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
┌───────┐ ┌─────────┐
│ Cdp   │ │ Page    │
│Client │ │Snapshot │
└───┬───┘ └─────────┘
    │
    ▼
┌─────────────┐
│ Chromium    │
│ DevTools    │
│ Protocol    │
└─────────────┘
```

### 3.3 Java libraries

| Task | Library |
|------|---------|
| CDP WebSocket client | `java.net.http.WebSocket` |
| JSON-RPC over CDP | manual (simple JSON-RPC) |
| Chromium management | external `google-chrome --remote-debugging-port=9222` |
| Accessibility tree | CDP `Accessibility.getFullAXTree` |
| Screenshot | CDP `Page.captureScreenshot` |
| DOM snapshot | CDP `DOMSnapshot.captureSnapshot` |

### 3.4 Playwright / agent-browser (out of scope)

The upstream Python agent uses Playwright / `agent-browser`. These are **not** in the Java prototype scope because Playwright Java adds ~200 MB of native binaries. CDP-first approach keeps the footprint small.

### 3.5 Java classes

```
backend/src/main/java/com/azhukov/agent/tools/browser/
├── BrowserService.java
├── CdpClient.java
├── BrowserNavigateTool.java
├── BrowserSnapshotTool.java
├── BrowserClickTool.java
├── BrowserTypeTool.java
├── BrowserScrollTool.java
├── BrowserBackTool.java
├── BrowserPressTool.java
├── BrowserConsoleTool.java
└── BrowserVisionTool.java
```

### 3.6 Configuration

- `agent.browser.cdp-url` — WebSocket URL of Chrome DevTools.
- `agent.browser.default-timeout-ms` — default CDP operation timeout (default 120000).
- `agent.browser.page-load-timeout-ms` — page load timeout (default 120000).
- `agent.browser.max-tabs` — max concurrent tabs.
- `agent.browser.headless` — launch headless (if launcher implemented).
- `agent.browser.executable-path` — path to Chrome binary.

## 4. Integration

Browser tools and `vision_analyze` share `ModelClient`. Screenshot is sent as base64 image to the configured vision model. Auxiliary-first vision is controlled by `agent.vision.use-auxiliary-first`.
