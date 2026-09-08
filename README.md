# izs SSH — Android SSH client with Tabby Sync parity

Native Android SSH client (`id.web.izs.sshclient`) with **full Tabby Desktop
Config Sync parity** and a Termux-like interactive PTY terminal. Import your
Tabby profiles, unlock the vault once, and ssh from your phone — black
full-bleed terminal, docked extra-keys bar, sticky CTRL/ALT.

Behavioral parity with [Tabby Desktop](https://github.com/Eugeny/tabby)
(`config.service`, `vault.service`, `configSync.service`); mobile UX inspired
by tabby-android (xterm). See [ARCHITECTURE.md](ARCHITECTURE.md) for the full
technical design.

## Features (usable today)

- **Tabby Sync import**: host + token setup, cloud config list, download /
  upload with lossless RAW round-trip (unknown keys survive untouched).
- **Vault**: PBKDF2-HmacSHA512 + AES-256-CBC interop with desktop vaults;
  lazy unlock (passphrase asked only when a secret is actually needed);
  survives rotation via ViewModel; RAM-only, never written to disk.
- **Profiles**: home list (search, groups, add button, identity-colour
  stripe), tabbed editor — General (connection-mode dropdown, Auto auth,
  new/existing groups, colour picker), Ports, Advanced, Ciphers,
  Login scripts; Colours stays desktop-managed (colour schemes). New
  profiles get desktop-shape ids (`ssh:custom:<slug>:<uuid>`), fields at
  desktop defaults are omitted from YAML, options that do nothing on
  mobile are labeled desktop-only, live RAW `config.yaml` viewer.
- **Terminal**: real PTY shell (sshj), VT100/xterm-subset emulator, colors,
  alt-buffer (vim/htop), **scrollback with drag-to-read + follow-bottom**,
  **text selection (long-press word, drag handles, floating Copy)**,
  window-change on resize, host-key trust prompt (unknown/changed keys,
  desktop `ssh.knownHosts` format, known-first negotiation),
  password + multi-key auth. Connect honors login scripts
  (expect/regex/optional), keepalive interval, and custom ciphers;
  warn-on-close follows Settings > SSH (per-profile override preserved).
- **Settings > Terminal**: font size + scrollback buffer
  (− number + stepper, tap to type, 0 = off, max 100.000), applies live;
  extra-keys layout editor (labels, ordered step macros with per-step
  byte preview, popup menus, presets, clipboard import/export) with a pixel-identical live preview.
- **Termux-like input**: docked extra-keys bar
  (`ESC / - HOME ↑ END PGUP` / `TAB CTRL ALT ← ↓ → PGDN`),
  sticky CTRL/ALT, direct typing with raw keystrokes (Backspace=DEL,
  Enter=CR), command-box mode, adjustable font (8–24sp).
- **Keyboard dock that jumps, not slides**: the layout moves once, discretely,
  on open/close — no tracking animation, no follow-up motion.
- **Crash diagnostics**: debug builds save the last crash trace; the next
  launch offers a Crash Report screen with copy.

## Stack (all stable, none deprecated)

| Layer | Choice |
|---|---|
| Language / build | Kotlin 2.4.20, Gradle 9.7.1, AGP 9.4.0 |
| UI | Jetpack Compose (BOM 2026.08.00) + Material3 + Navigation 2.10.0 |
| Lifecycle | lifecycle-viewmodel(-ktx) 2.11.0 |
| Async / JSON / HTTP | coroutines 1.11.0, serialization-json 1.11.0, OkHttp 5.5.0 |
| YAML | SnakeYAML 2.7 |
| Secure storage | AndroidX Security Crypto 1.1.0 |
| SSH | sshj 0.40.0 + BouncyCastle (bcprov 1.85.2 / bcpkix 1.85). JSch is abandoned and is NOT used |
| Crypto | `javax.crypto` only, pure JVM — unit-testable |
| SDK | minSdk 26, compileSdk 37, targetSdk 36 |

`http://` sync hosts are allowed for self-hosted LAN use (with an in-app
warning); prefer `https://` for anything public, matching Tabby Desktop.

## Quick start

```bash
./gradlew :app:testDebugUnitTest   # 127 unit tests (vault, sync, emulator, profiles, connect opts, host trust, selection, extra keys)
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Open the app → the profile list (fresh installs start empty; Tabby Sync
   lives under Settings, never blocks boot).
2. Pick a profile → unlock the vault when asked → a real shell opens.
3. Tap the terminal to raise the keyboard; use the extra-keys bar for
   ESC/arrows/HOME/END/PGUP/PGDN/TAB/CTRL/ALT.

## Parity guarantees (tested, 127/127 green)

- Decrypt-only-when-needed (listing/upload never decrypt).
- Lossless RAW round-trip (`configSync` stripped/restored, disabled `parts`
  merged from the correct side, opaque unknown keys, profile `color`/`icon`
  preserved).
- Secrets: `ssh:password{user,host,port}`, `ssh:key-passphrase{hash}`,
  `file{id}=base64(PEM)` ↔ `vault://id`; blob-always-container rule.
- Emulator: SGR/wrap/cursor/erase/scroll/margins/alt-buffer + wrap regression.
- Connect opts: login scripts (unconditional/expect/regex/optional/unescape),
  keepalive interval, cipher/kex/mac/hostkey/compression filtered to
  sshj-supported (desktop `supportedAlgorithms`-filter parity),
  `warnOnClose` = profile override ?? global `ssh.warnOnClose`.

## Roadmap (toward full `config.yaml` parity, min. tabby-android level)

- **Multi-session (todo)**: session registry in `AppViewModel` (PTYs survive
  nav + rotation — also fixes rotation-PTY), session picker replacing
  disconnect-on-back, profile-colour strip as tab colour, per-session
  warn-on-close, cap on concurrent sessions. Prerequisite for anything
  multiplexing-shaped.
- **Port forwarding**: open Local/Remote/Dynamic at connect (saved today).
- **Connect**: `connectionMode` (proxyCommand/jumpHost/SOCKS/HTTP) stays
  direct-only (saved for desktop); terminal type + colour schemes stay
  desktop-managed.
- Text selection with start/end drag handles + Copy/Paste bar; search-in-buffer.

## Layout

See [ARCHITECTURE.md](ARCHITECTURE.md) §2 for the per-file module map.
