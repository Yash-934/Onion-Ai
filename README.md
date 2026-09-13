# Private AI Gateway & Tor Client

A production-ready, privacy-first AI gateway and Android client designed for secure, Tor-routed AI operations and seamless integration with **PocketForge** and **Claude Code** agents.

---

## 🏗️ Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                       Clients Layer                         │
│                                                             │
│   ┌──────────────────────────┐   ┌───────────────────────┐  │
│   │ PocketForge (Claude Code)│   │  Android Tor Client   │  │
│   │  - Sandboxed Agent       │   │  - Keystore AES-GCM   │  │
│   │  - Tool Execution Local  │   │  - SOCKS5 Strict Mode │  │
│   └────────────┬─────────────┘   └───────────┬───────────┘  │
└────────────────┼─────────────────────────────┼──────────────┘
                 │ Anthropic /v1/messages      │ .onion SOCKS5
                 ▼                             ▼
┌─────────────────────────────────────────────────────────────┐
│              Private Onion AI Gateway (FastAPI)             │
│                                                             │
│  - Zero Logging: No prompts, responses, or keys persisted   │
│  - Protocol Adapters: Anthropic ⇄ OpenAI ⇄ Internal Normal  │
│  - SSE Streaming Translator: Real-time event synthesis      │
│  - Agent Tool Relay: Safe translation of tools & results    │
│  - Host Isolation: Bound to 127.0.0.1 (behind Tor v3 HS)   │
└──────────────────────────────┬──────────────────────────────┘
                               │ Private Upstream Credentials
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                 Upstream Model Provider                     │
│      (Anthropic Claude, OpenAI, or Self-Hosted LLM)         │
└─────────────────────────────────────────────────────────────┘
```

> **Standalone Server**: The gateway runs as a standalone server-side API. The Android client does **not** need to be running for PocketForge or Claude Code to use the gateway.

---

## 🚀 PocketForge & Claude Code Integration

PocketForge uses Claude Code as an autonomous agent connecting to this gateway through the standard **Anthropic Messages API** protocol (`/v1/messages`).

### PocketForge Custom API Configuration

In PocketForge's API Settings:

| Setting | Value |
| :--- | :--- |
| **Provider** | `Anthropic-compatible` / `Custom API` |
| **Base URL** | `http://YOUR-ONION-ADDRESS.onion` (or `https://...`) |
| **API Key** | `YOUR_GATEWAY_API_KEY` (configured in `.env`) |
| **Model** | `claude-3-7-sonnet-20250219` (or your configured `CHAT_MODEL`) |

> **Note on Tool Safety**: The gateway **does not** execute tools on the server. When Claude Code outputs a `tool_use` content block, the gateway relays it to PocketForge. PocketForge executes the tool within its local sandbox/policy layer and sends back `tool_result` blocks in the next turn.

---

## 📡 API Endpoints

### 1. Anthropic-Compatible Messages API
- **`POST /v1/messages`**
  - **Headers**: `x-api-key: <gateway-key>` or `Authorization: Bearer <gateway-key>`
  - **Body fields**: `model`, `max_tokens`, `messages`, `system`, `temperature`, `top_p`, `top_k`, `stop_sequences`, `stream`, `tools`, `tool_choice`, `metadata`
  - **Content blocks supported**: `text`, `image` (base64 data), `tool_use`, `tool_result`
  - **Streaming**: Strict Anthropic SSE event stream (`message_start` → `content_block_start` → `content_block_delta` → `content_block_stop` → `message_delta` → `message_stop`).

### 2. Model Discovery
- **`GET /v1/models`** & **`GET /models`**
  - Returns list of available models configured on the gateway (`CHAT_MODEL`, `CODE_MODEL`, `IMAGE_MODEL`).

### 3. OpenAI-Compatible Chat Completions
- **`POST /v1/chat/completions`** & **`POST /chat/completions`**
  - Supports streaming and non-streaming requests with tool calls.

### 4. Image Generation
- **`POST /v1/images/generations`** & **`POST /images/generations`**
  - Dispatches image prompts to the upstream image model.

### 5. Health Check
- **`GET /health`** & **`GET /`**
  - Minimal privacy-safe status indicator.

---

## 🛡️ Privacy & Zero-Logging Guarantees

1. **Zero Data Retention**: No prompts, model outputs, conversation histories, IP addresses, authorization tokens, or cookies are logged to disk, standard out, or remote sinks.
2. **Header Sanitization**: Sensitive headers (`Authorization`, `x-api-key`, `Cookie`) are strictly redacted in any internal logs.
3. **No Third-Party Analytics**: Neither the server nor the Android client includes Firebase Analytics, Sentry, advertising SDKs, or tracking scripts.
4. **Android Keystore AES-GCM**: Local chat history and API configurations on the Android client are encrypted at rest using a hardware-backed master key.
5. **Tor-Only Strict Enforcement**: The Android client strictly rejects non-`.onion` hostnames and communicates exclusively via local Tor SOCKS5 proxy (default `127.0.0.1:9050`). No clear-net fallback is permitted.

---

## ⚙️ Server Deployment Guide

### Prerequisites
- Python 3.10+
- Tor daemon installed (e.g. `sudo apt install tor`)

### 1. Configure Environment
Create `.env` in the server root:
```bash
cp .env.example .env
```

Configure your parameters:
```env
GATEWAY_API_KEY=my-secure-gateway-secret
UPSTREAM_PROTOCOL=openai
UPSTREAM_BASE_URL=https://api.openai.com/v1
UPSTREAM_API_KEY=sk-...
CHAT_MODEL=claude-3-7-sonnet-20250219
CODE_MODEL=claude-3-7-sonnet-20250219
IMAGE_MODEL=dall-e-3
```

### 2. Run Gateway
```bash
cd server
pip install -r requirements.txt
uvicorn main:app --host 127.0.0.1 --port 8443
```

### 3. Configure Tor Onion Service
Edit `/etc/tor/torrc`:
```text
HiddenServiceDir /var/lib/tor/private-ai/
HiddenServicePort 80 127.0.0.1:8443
HiddenServicePort 443 127.0.0.1:8443
HiddenServiceVersion 3
```

Restart Tor and read your `.onion` address:
```bash
sudo systemctl restart tor
sudo cat /var/lib/tor/private-ai/hostname
```

---

## 🧪 Testing & Verification

Run the comprehensive test suite (21 automated tests covering all protocol translations, agent loops, error handling, and security):

```bash
python3 -m unittest discover -s server/tests -t server -v
```

---

## 📱 Android Client Build

Build the Android debug APK:
```bash
./gradlew assembleDebug
```
The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.
