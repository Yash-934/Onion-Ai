# Privacy & Security Model

This project enforces strict privacy guarantees across both the server gateway and Android client.

---

## 🔒 Gateway Privacy Guarantees

1. **Zero Data Retention**:
   - The gateway does **not** persist or log request payloads, prompt texts, model responses, system prompts, or tool execution arguments.
   - Logs are restricted to sanitized operational telemetry (e.g. HTTP method, sanitized path, status code, response time).
   
2. **Credential Redaction**:
   - Client authorization headers (`Authorization: Bearer ...`, `x-api-key`, and cookies) are stripped and redacted in memory before any log output.
   - Upstream API keys are held only in runtime memory and injected directly into upstream requests.

3. **Loopback Isolation**:
   - The gateway server binds exclusively to loopback interfaces (`127.0.0.1:8443`), making it accessible from the internet solely through an authenticated Tor v3 Onion Service.

4. **Agent Sandbox Neutrality**:
   - The gateway is a pass-through protocol translator. It **never** runs arbitrary code or executes tool calls on behalf of the agent. All tool execution is delegated to the client runtime (e.g. PocketForge).

---

## 🛡️ Android Client Guarantees

1. **Hardware-Backed Encryption**:
   - Local chat history, onion URLs, and API tokens are encrypted at rest using AES-GCM with keys secured by the **Android Keystore System**.

2. **Tor-Only Strict Transport**:
   - All network traffic is routed via a local Tor SOCKS proxy (default `127.0.0.1:9050`).
   - The client verifies that destination hostnames terminate with `.onion`. Any clearnet or IP destinations are immediately rejected.

3. **Zero Third-Party Telemetry**:
   - No crash reporters, advertising networks, analytics trackers, or third-party SDKs are embedded in the app.

4. **Local Text-to-Speech**:
   - Voice synthesis runs exclusively on-device through the system's local TextToSpeech engine.
