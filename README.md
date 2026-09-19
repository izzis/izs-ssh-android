# izs SSH — Android SSH client with Tabby Sync parity

Native Android SSH client (`id.web.izs.sshclient`) with
[Tabby terminal](https://github.com/Eugeny/tabby) Config Sync parity
and a Termux-like interactive PTY terminal. Import your
Tabby profiles, unlock the vault once, and ssh from your phone.

Unofficial, independent project — not affiliated with or endorsed by the
Tabby Developers. MIT licensed, see [LICENSE](LICENSE).
Design doc: [ARCHITECTURE.md](ARCHITECTURE.md).
Release flow: [RELEASE.md](RELEASE.md).

> ⚠️ **Upgrading from an older alpha (pre-1.2.1):** the encrypted storage
> backend was replaced, so on-device profiles and settings from previous
> builds are cleared on first boot (one-time reset, fresh installs are
> unaffected). Back up first: Settings → Config file → **Copy** (unlock
> first if fully encrypted; plaintext configs copy directly), then restore
> after updating via Config file → Import → Paste from clipboard.

## Features

- **Tabby Sync import** — host + token setup, download/upload with lossless
  RAW round-trip, foreground auto-sync every 60s (opt-in).
- **Vault** — desktop-interop encryption (PBKDF2 + AES-256-CBC), lazy unlock,
  RAM-only, never written to disk.
- **Profiles** — search, groups, tabbed editor (connection incl. SOCKS
  proxy, ports, ciphers, login scripts), live RAW `config.yaml` viewer.
- **Terminal** — real PTY shell (sshj), xterm subset, alt-buffer, scrollback,
  text selection, host-key trust prompts, password + multi-key auth.
- **Multi-session tabs** — one tab per tap, transport sharing via
  `reuseSession`, desktop `tabsLocation` parity (strip / side drawer).
- **SFTP browser** — download/upload over the session's own transport,
  transfers survive sheet dismiss and tab switches.
- **Port forwarding** — Local/Remote rules open at connect (desktop parity);
  Dynamic (SOCKS) stays desktop-only with a clear message.
- **Appearance** — app theme + palettes, terminal font/cursor, 100+ color
  schemes with per-profile overrides, Follow color scheme (whole app
  theme derived from the active scheme).
- **Updates** — manual update check against GitHub Releases from About
  (sideloaded builds get no store updates; no background polling).
- **Background survival** — foreground service with per-host notification,
  auto-retry once after a kill.

Mobile scope: every YAML value syncs back untouched, but a few desktop
features are desktop-only on a phone (no X server / agent / helper
binaries) — full matrix in [ARCHITECTURE.md](ARCHITECTURE.md) §11.

## Stack

| Layer | Choice |
|---|---|
| Language / build | Kotlin 2.4.20, Gradle 9.7.1, AGP 9.4.1 |
| UI | Jetpack Compose (BOM 2026.08.00) + Material3 + Navigation 2.10.1 |
| Lifecycle | lifecycle-viewmodel(-ktx) 2.11.0 |
| Async / JSON / HTTP | coroutines 1.11.0, serialization-json 1.11.0, OkHttp 5.5.0 |
| YAML | SnakeYAML 2.7 |
| Secure storage | Tink AES256-GCM (tink-android 1.23.0) |
| SSH | sshj 0.40.0 + BouncyCastle (bcprov 1.86 / bcpkix 1.86) |
| SDK | minSdk 26, compileSdk 37, targetSdk 36 |

`http://` sync hosts are allowed only for local targets (loopback/LAN/
link-local, enforced in code with an in-app warning); use `https://`
for anything public, matching Tabby terminal.

## Quick start

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Open the app → profile list (fresh installs start empty).
2. Pick a profile → unlock the vault when asked → a real shell opens.
3. Tap the terminal to raise the keyboard; extra-keys bar covers ESC,
   arrows, TAB, CTRL, ALT.

Zero-warning policy: main + test sourcesets must compile with 0 warnings.
Fix the cause first; `@Suppress` is a last resort and must carry a comment
saying why it is safe and what would remove it.
