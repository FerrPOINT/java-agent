# 06 — Vision & Browser: In Scope

Target stack: Java 25 LTS + Spring Boot 4.1.0 + Gradle 9.6.1 (Groovy DSL) + Groovy 5.0.7 + PostgreSQL 16 + OpenAI-compatible LLM endpoint.

## 1. Decision

Vision and browser are in scope for the Java port. Both use the same `ModelClient` with OpenAI-compatible endpoints, so they do not require separate heavy SDKs.

## 2. Vision

### 2.1 Functionality

- `vision_analyze` — analyze an image from URL or local path with a user prompt.
- Default model: OpenAI-compatible vision model configured by `agent.vision.model-name`; local Ollama is the dev default.
- Image is base64-encoded and passed in the `content` array alongside text.

### 2.2 OpenAI-compatible format

```json
{
  "model": "qwen2.5-vl:7b",
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
agent-core/src/main/java/com/azhukov/agent/core/tools/
├── VisionAnalyzeTool.java
├── VisionImageUrlTool.java
└── support/
    └── ImageEncoder.java
```

### 2.4 Configuration

- `agent.vision.model-name` — default model (Ollama vision model).
- `agent.vision.max-download-bytes` — file size limit (default 50 MB).
- `agent.vision.download-timeout-seconds` — download timeout.
- Secrets via env: `AGENT_VISION_API_KEY`, or reuse the main model provider.

### 2.5 What to port from `tools/vision_tools.py`

- Image download with size limit and timeout.
- Base64 encoding.
- Provider fallback chain: Ollama → OpenAI-compatible endpoint → custom endpoint.
- URL credential redaction.

## 3. Browser

### 3.1 Functionality

Browser in agent has multiple modes. For the Java prototype we keep **local Chromium via CDP** — no cloud API keys and it runs on headless servers.

- `browser_navigate` — open URL.
- `browser_snapshot` — accessibility tree / DOM snapshot.
- `browser_click`, `browser_type`, `browser_scroll`, `browser_back`, `browser_press` — actions.
- `browser_console` — read console.
- `browser_vision` — screenshot + analysis via `vision_analyze`.
- `browser_cdp` — low-level CDP passthrough (restricted).

### 3.2 Architecture

```
┌─────────────────┐
│  BrowserPool    │  ← manages Chromium processes
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

### 3.3 Java libraries

| Task | Library |
|------|---------|
| CDP WebSocket client | `java.net.http.WebSocket` |
| JSON-RPC over CDP | manual (CDP is simple JSON-RPC) |
| Chromium management | `ProcessBuilder` with `google-chrome --remote-debugging-port=9222` or Selenium chrome-driver helper |
| Accessibility tree | CDP `Accessibility.getFullAXTree` |
| Screenshot | CDP `Page.captureScreenshot` |
| DOM snapshot | CDP `DOMSnapshot.captureSnapshot` |

### 3.4 Playwright / agent-browser (out of scope)

The upstream Python agent uses `agent-browser` (Node wrapper) or Playwright. These are **not** in the Java prototype scope because:
- Playwright Java adds ~200 MB of native binaries.
- The goal is a lightweight local Chromium CDP client.

Playwright may be added as an optional backend after core CDP is stable.

### 3.5 Java classes

```
agent-core/src/main/java/com/azhukov/agent/core/tools/browser/
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

### 3.6 Configuration

- `agent.browser.cdp-url` — external CDP endpoint if Chromium is managed outside the agent.
- `agent.browser.default-timeout-ms` — operation timeout.

## 4. Image Generation

Image generation (FAL) stays **out of scope** — it needs FAL SDK / REST and separate billing. `image_generate` can be added later as an optional tool via MCP or a REST wrapper.

## 5. Updated Tool List

### In scope

- `read_file`, `write_file`, `patch`, `search_files`
- `terminal`, `process`
- `web_search`, `web_extract`
- `vision_analyze` (Ollama / OpenAI-compatible)
- `browser_navigate`, `browser_snapshot`, `browser_click`, `browser_type`, `browser_scroll`, `browser_back`, `browser_press`, `browser_console`, `browser_get_images`, `browser_vision`
- `skills_list`, `skill_view`, `skill_manage`
- `memory`
- MCP client tools

### Not in scope

- `image_generate` (FAL)
- `browser_cdp` extended — can be added later
- voice / TTS
- computer-use / desktop UI

## 6. Architecture Impact

- `ModelClient` must support multipart content (text + image_url) for vision.
- `ToolExecutor` can run the CDP client in a virtual thread.
- Need `BrowserPool` to manage Chromium process lifecycle and cleanup.
- `vision_analyze` must support provider fallback like agent' auxiliary client.

## 7. Security

- Vision: limit downloaded image size, validate URL via `website_policy`, do not pass credentials in base64 metadata.
- Browser: run Chromium in sandbox, headless, avoid `--no-sandbox` unless required; restrict navigation via allow-list; redact CDP URL credentials.
