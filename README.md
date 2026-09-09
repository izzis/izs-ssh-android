# izs SSH — Android SSH client with Tabby Sync parity

Native Android SSH client (`id.web.izs.sshclient`) with **full Tabby Desktop
Config Sync parity** and a Termux-like interactive PTY terminal. Import your
Tabby profiles, unlock the vault once, and ssh from your phone — black
full-bleed terminal, docked extra-keys bar, sticky CTRL/ALT.

Behavioral parity with [Tabby Desktop](https://github.com/Eugeny/tabby)
(`config.service`, `vault.service`, `configSync.service`); mobile-first
terminal UX (xterm-style). See [ARCHITECTURE.md](ARCHITECTURE.md) for the full
technical design.

## Features (usable today)

- **Tabby Sync import**: host + token setup, cloud config list, download /
  upload with lossless RAW round-trip (unknown keys survive untouched).
- **Vault**: PBKDF2-HmacSHA512 + AES-256-CBC interop with desktop vaults;
  lazy unlock (passphrase asked only when a secret is actually needed);
  survives rotation via ViewModel; RAM-only, never written to disk.
- **Profiles**: home list (search, groups, add button, identity-colour
  stripe), tabbed editor — General (connection-mode dropdown, Auto auth,
  new/existing groups, colour picker), Ports, Advanced, Ciphers, Colours
  (terminal color scheme override: Use-global + scheme search), Login
  scripts; New profiles get desktop-shape ids (`ssh:custom:<slug>:<uuid>`), fields at
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
- **Multi-session tabs**: every profile tap opens a new tab (desktop
  parity); `reuseSession` shares one TCP transport per tab group. Tab chrome
  follows the desktop `appearance.tabsLocation` key — `top`/`bottom` strip
  (status dot + profile name + primary activity underline + ×),
  `left`/`right` side drawer (hamburger replaces Back, edge-fling opens it,
  pinned Profile-list + Settings footer), absent key = no tabs (profile-list UX). `+`
  opens a new connection (list or quick-pick sheet, Settings > Window),
  × honors `warnOnClose`, Back always goes home, navigation stays shallow
  (no stack growth).
- **Settings > Window**: tab-location source priority — *Synced config*
  (the desktop YAML value wins; on encrypted configs Android ignores it but
  still writes it for desktop) or *This device only* (a local pref, never
  synced — desktop-top/phone-bottom splits without touching sync parts);
  plus *New tab (+) opens* — profile list or quick-pick bottom sheet
  (search + recent + grouped profiles, half by default, draggable to full),
  device-only, never synced.
- **Termux-like input**: docked extra-keys bar
  (`ESC / - HOME ↑ END PGUP` / `TAB CTRL ALT ← ↓ → PGDN`),
  sticky CTRL/ALT, direct typing with raw keystrokes (Backspace=DEL,
  Enter=CR), command-box mode, adjustable font (8–24sp).
- **Recent profiles**: home quick-connect section (desktop `recentProfiles`
  parity, per-row History icon like the desktop selector) sized by the
  desktop `terminal.showRecentProfiles` key (0 = off); collapsible header
  (outside the card) with Clear. Active-sessions card above it has Close all.
- **Keyboard dock that jumps, not slides**: the layout moves once, discretely,
  on open/close — no tracking animation, no follow-up motion.
- **Settings > Color scheme**: Current header + Edit/Delete, full `ls`
  preview per row, Custom badges, Save = global + customs upsert by name;
  22-dot editor (FG/BG/CU/CA/SB/SF + ANSI labels, long-press tooltips,
  4×5 family grid + 9-step + hex picker); synced YAML + per-profile
  overrides + this-device-only instant mode. Terminal content only.
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
./gradlew :app:testDebugUnitTest   # 189 unit tests (vault, sync, emulator, profiles, connect opts, host trust, selection, extra keys, sessions, tabs, recents, color schemes)
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Zero-warning policy: every build (main + test sourcesets) must finish with
0 warnings. Fix the code first (migrate off deprecated APIs, restructure
the nullable logic) — `@Suppress` is the LAST resort, never a shortcut to
silence the compiler: each one must carry a comment stating WHY it is safe
and WHAT would remove it. See [ARCHITECTURE.md](ARCHITECTURE.md) §9.

1. Open the app → the profile list (fresh installs start empty; Tabby Sync
   lives under Settings, never blocks boot).
2. Pick a profile → unlock the vault when asked → a real shell opens.
3. Tap the terminal to raise the keyboard; use the extra-keys bar for
   ESC/arrows/HOME/END/PGUP/PGDN/TAB/CTRL/ALT.

## Parity guarantees (tested, 189/189 green)

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

## Roadmap (toward full `config.yaml` parity)

- **Multi-session (done, incl. tab chrome)**: session registry in `SshSessionViewModel`
  (PTYs survive nav + rotation — also fixes rotation-PTY), new tab per tap
  with `reuseSession` transport sharing (desktop multiplex parity),
   Active-sessions list on home replacing disconnect-on-back (Back keeps the
   session alive), per-session warn-on-close, cap on concurrent sessions
   (default 5, max 8). Tab strip (top/bottom) + side drawer (left/right) +
   background-output activity underline (primary) driven by the desktop `appearance.tabsLocation`
   key; shell presence is an observable `hasShell` flow (branching composition
   on the plain `shell` field renders stale nulls — green dot + dead
   Disconnected on a live session). Reorder/rename/pin stay v2.
- **Tab UX polish (done)**: home is one shared scroll (Active always
  expanded with Close all, Recent collapsible with per-row History icons +
  Clear, sticky search); quick-pick bottom sheet for `+` (Settings > Window >
  New tab, device-only) with recent + grouped profiles, half by default and
  draggable to full; primary activity underline on tabs; drawer footer with
  Profile list + Settings; drawer edge-fling with a 32dp system-Back reserve;
  shallow sheet navigation + global session-limit dialog.
- **Appearance (planned)**: Settings > Appearance today is a placeholder —
  target is desktop `appearance.*` parity where it makes sense on mobile
  (app theme selection incl. follow-system, display density/spaciness),
  desktop-only keys (vibrancy, CSS, frame) stay desktop-managed.
- **Color scheme (done)**: Settings > Color scheme mirrors desktop
  (Current header + Edit/Delete, full `ls` preview per row, Custom badges,
  Save = global + customs upsert by name, 4×5 family grid + 9-step + hex
  picker).
  89 built-ins (Izs Default + Tabby Default + 87 curated community picks,
  readability-gated). Global `terminal.colorScheme` + `terminal.customColorSchemes`
  + per-profile `terminalColorScheme` in synced YAML, desktop object shape
  (sibling keys like `lightColorScheme` never touched); absent global = Izs
  Default (zero visual change). Source toggle (tabSource parity): synced YAML
  vs this-device-only instant pref (per-profile overrides apply in both).
  Applies live incl. old cells (remap), terminal content only;
  `selectionForeground`/`cursorAccent` stored but unused (inverse-video
  cursor); `lightColorScheme`/`colorSchemeMode` ignored (dark-only app).
  Profile identity colors already work (list stripe, sheet dot, editor picker).
- **Port forwarding**: open Local/Remote/Dynamic at connect (saved today).
- **Connect**: `connectionMode` (proxyCommand/jumpHost/SOCKS/HTTP) stays
  direct-only (saved for desktop); terminal type stays desktop-managed.
- Text selection with start/end drag handles + Copy/Paste bar (done — see
  Features above); search-in-buffer.

## Layout

See [ARCHITECTURE.md](ARCHITECTURE.md) §2 for the per-file module map.
