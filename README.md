# Private AI — App 1

A privacy-first Android AI client designed for a separate server-side API gateway.

## What is included

- Android client (Kotlin, no account, no analytics SDKs)
- Strict `.onion` endpoint validation
- SOCKS/Tor-only network path (127.0.0.1:9050 by default)
- No clearnet fallback
- Encrypted local configuration/history using Android Keystore AES-GCM
- Streaming OpenAI-compatible `/chat/completions`
- `/models` discovery
- `/images/generations` client/server contract
- On-device Android TTS
- Separate FastAPI gateway skeleton
- PocketForge-compatible OpenAI Chat provider contract

## PocketForge compatibility

PocketForge's OpenAI Chat provider expects:

- `GET {baseUrl}/models`
- `POST {baseUrl}/chat/completions`
- Bearer authentication
- OpenAI-style SSE streaming

Set PocketForge's Custom/OpenAI-compatible provider base URL to the gateway onion base URL.

Example:

`https://YOUR-SERVICE-ADDRESS.onion`

Do not append `/v1` unless your gateway is intentionally deployed with that prefix.

## Tor note

This first release uses a strict SOCKS interface rather than bundling a Tor daemon inside the APK. The app refuses non-`.onion` hosts and never falls back to normal networking.

For a fully standalone APK, embed and lifecycle-manage an audited Tor/Arti runtime in a later release. Do not weaken the `.onion` check to make ordinary HTTPS work.

## Server gateway

```bash
cd server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn main:app --host 127.0.0.1 --port 8443
```

Put TLS/Tor in front of it and keep the API process bound to localhost.

### Privacy requirements

- No request/response logging.
- No analytics.
- No cookies or persistent user identifiers.
- Do not store prompts, responses, IP addresses, or Authorization headers.
- Use aggregate rate limiting only if necessary.
- Never put upstream provider credentials in the APK.
- Use a dedicated gateway key for PocketForge; rotate it if exposed.

## Build

Open the directory in Android Studio and run:

`./gradlew assembleDebug`

The repository intentionally does not contain a release signing key. For a public GitHub release, sign the final APK with your own key/CI secret.

## Current scope

This is the initial implementation foundation. Before calling it a production security release, independently audit the embedded Tor choice, Android transport behavior, server reverse proxy, TLS, rate limiting, and all dependency versions.
