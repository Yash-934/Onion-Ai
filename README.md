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

### 5. API Key Management (Admin Endpoints)
- **`POST /admin/keys`**: Generate new client API keys with optional labels, expiration, and rate limits. Plaintext secret is returned once and stored only as a salted SHA-256 hash.
- **`GET /admin/keys`**: List active/revoked key metadata (ID, label, creation date, rate limits). Secrets are never exposed.
- **`POST /admin/keys/{id}/revoke`**: Immediately revoke an API key.
- **`POST /admin/keys/{id}/rotate`**: Rotate secret and return new single-use plaintext key.

### 6. Health Check
- **`GET /health`** & **`GET /`**
  - Minimal privacy-safe status indicator including model identifier and Tor-only network policy flag.

---

## 🔑 API Key System & Admin vs. Client Separation

The gateway enforces strict separation between **Admin Master Authentication** and **Client API Keys**:

1. **Admin Master Key (`GATEWAY_ADMIN_KEY`)**:
   - Used exclusively for administrative operations (creating, listing, rotating, and revoking client keys).
   - Configured via environment variable or server configuration.
2. **Client API Keys (`sk-priv-...`)**:
   - Distinct, cryptographically random keys (`secrets.token_urlsafe(32)`).
   - Only the SHA-256 digest is stored server-side.
   - Enforce individual rate limits (sliding 60-second window) and expiration dates.
   - Immediate revocation stops downstream agent access without cycling server master credentials.

---

## 🛡️ Privacy Guarantees & Threat Model

### Privacy Guarantees
1. **Zero Data Retention**: No prompts, model outputs, conversation histories, IP addresses, authorization tokens, or cookies are logged to disk, standard out, or remote sinks.
2. **Header Sanitization**: Sensitive headers (`Authorization`, `x-api-key`, `Cookie`) are strictly redacted in any internal logs.
3. **No Third-Party Analytics**: Neither the server nor the Android client includes Firebase Analytics, Sentry, advertising SDKs, or tracking scripts.
4. **Android Keystore AES-GCM**: Local chat history and API configurations on the Android client are encrypted at rest using a hardware-backed master key.
5. **Tor-Only Strict Enforcement**: The Android client strictly rejects non-`.onion` hostnames and communicates exclusively via local Tor SOCKS5 proxy (default `127.0.0.1:9050`). No clear-net fallback is permitted.

### Threat Model & Operational Caveats
* **What Tor Protects**:
  - Hides your device's direct IP address and physical location from the gateway and upstream networks.
  - Hides the gateway's server IP and hosting provider from clients and third parties via Tor v3 Hidden Service encryption.
  - Renders ISP eavesdropping and local network interception impossible.
* **What Tor Does NOT Protect (Realistic Limitations)**:
  - **Malicious Gateway**: If the gateway host is compromised, prompts transmitted to it can be inspected before being forwarded upstream. Always host your own gateway or verify the operator.
  - **Upstream AI Provider**: The configured upstream provider (e.g., Anthropic, OpenAI) still receives the prompt content. Use pseudonymized identities and avoid transmitting PII in agent prompts.
  - **Local Device Compromise**: If an attacker gains root or physical control of the Android device or PocketForge host, memory dumping could reveal decrypted text.
  - **Traffic Timing Analysis**: Sophisticated global adversaries observing both entry guard and destination exit flows can theoretically perform statistical correlation attacks.
* **Performance Realities**:
  - Tor routes traffic through a 3-hop circuit (or 6 hops rendezvous for Hidden Services). Expect higher latency (typically 500ms–2000ms additional initial connection latency) compared to direct clearnet connections.
  - First-token latency in streaming mode will reflect this circuit setup time. Subsequent SSE tokens flow steadily once the circuit is established.

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
GATEWAY_ADMIN_KEY=my-secure-admin-secret
RATE_LIMIT_PER_MINUTE=60
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

Run the comprehensive test suite (31 automated tests covering all protocol translations, API key hashing, rate limiting, agent loops, error handling, and security):

```bash
python3 -m unittest discover -s server/tests -t server -v
```

In-App Diagnostics:
The Android client includes a 6-step interactive diagnostic suite:
1. SOCKS Proxy (127.0.0.1:9050) check
2. Onion reachability
3. Gateway `/health` ping
4. Authentication handshake
5. Model discovery (`/v1/models`)
6. End-to-end AI request (`Ping` → `Pong`)

---

## 📱 Android Client Build

Build the Android debug APK:
```bash
./gradlew assembleDebug
```
The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.
