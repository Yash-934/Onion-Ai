# Privacy model

The Android client is designed to avoid telemetry and accounts.

## Client guarantees implemented

- Only `INTERNET` permission is declared.
- API configuration and chat history are encrypted at rest with an Android Keystore AES-GCM key.
- Network requests require a `.onion` hostname and are sent through a local SOCKS proxy.
- There is no clearnet fallback.
- No analytics or advertising SDK is included.
- TTS is performed by Android's local TTS interface.

## Important limitation

Tor transport is only as private as the Tor runtime and device. This APK does not claim to make the device anonymous or compromise-proof. A future standalone build should bundle an audited Tor/Arti implementation and verify that every network path remains inside the Tor transport.

## Server requirements

The gateway must not log request bodies, IP addresses, authorization headers, prompts, responses, or persistent identifiers. Infrastructure, reverse proxies, OS logs, crash reporting, and upstream model services must be configured consistently with that policy.
