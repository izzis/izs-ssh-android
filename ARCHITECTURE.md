# ARCHITECTURE — ssh-client-android

Native Android SSH client (`id.web.izs.sshclient`, "izs SSH") with **Tabby
Desktop Config Sync parity** plus a Termux-like interactive PTY terminal.
Single module (`:app`), Kotlin + Jetpack Compose, no WebView.

Upstream references (behavioral parity, not code):
Tabby Desktop `config.service.ts` / `vault.service.ts`
(`tabby-core`), `configSync.service.ts` (`tabby-settings`);
mobile UX reference `tabby-android` (xterm-based).

## 1. Design principles

1. **Desktop parity first.** Vault crypto, secret URIs, sync merge rules and
   lazy-unlock semantics mirror Tabby Desktop exactly (all covered by tests).
2. **Lossless RAW.** The on-disk YAML document is the source of truth.
   The domain model is a transient, defaulted *view*; uploads are rebuilt
   from RAW so unknown/future keys survive round-trips byte-identical.
3. **Secrets never touch disk.** The vault passphrase lives in RAM only
   (`VaultState`, inside `AppState`, inside `AppViewModel`). It is asked
   lazily — only when vault content is actually needed.
4. **Terminal jumps, never slides.** Keyboard open/close moves the layout in
   one discrete jump (like tabby-android's coarse updates). No per-frame
   animation tracking, no follow-up motion.
5. **Cheap frames on weak phones.** The grid redraws only when emulator
   output arrives; keyboard-animation frames skip the Canvas entirely;
   text is laid out once per row, not per cell.

## 2. Module map

```
app/src/main/java/id/web/izs/sshclient/
  MainActivity.kt                 Boot sequence, NavHost, owns AppViewModel
  ui/
    AppViewModel.kt               Rotation-safe holder of AppState (passphrase survives rotate)
    AppState.kt                   Session state: Loaded, unlock(), profile/secret selectors
    Theme.kt                      IzsDarkColors (dark-only Material3 theme)
    screens/
      SyncSetupScreen.kt          Sync host + token setup
      ConfigSyncScreen.kt         Connection + cloud configs + up/download
      ProfileListScreen.kt        Groups + search + profile list, edit entry point
      ProfileEditScreen.kt        Rename/move/edit connection fields, add/remove keys, delete
      TerminalScreen.kt           PTY session: connect, input, dock, extra keys, box mode
      TerminalView.kt             Grid Canvas, cursor-follow scroll, measured cell metrics
      ConfigFileScreen.kt         Live RAW YAML view (parity with desktop `_store`)
      VaultUnlockDialog.kt        Passphrase prompt (lazy: only when needed)
      SetVaultPassphraseDialog.kt Set/change vault passphrase
      VaultSettingsScreen.kt      Vault management (set/change/erase, encrypt-config toggle)
      SshSettingsScreen.kt        SSH defaults (kept separate from desktop format)
      SettingsScreen.kt           Sidebar mirroring desktop Settings sections
      ConfigSyncScreen.kt         (see above)
      CrashReportScreen.kt        Shows last crash trace with copy button
      PlaceholderSettingScreen.kt "Scheduled" stubs (jumpHost/proxy etc.)
  core/
    config/
      TabbyModels.kt      Domain models: SshProfile, ProfileGroup, options
      SshDefaults.kt      Transient defaults applied on the domain view only
      ConfigMigrator.kt   Legacy migrations (name-based groups -> ids, jump hosts)
      RawConfigStore.kt   RAW YAML document ops (update/delete profile, secrets JSON)
    vault/
      VaultCrypto.kt      PBKDF2-HmacSHA512 x100k/salt8 -> AES-256-CBC/iv16 (pure JVM)
      VaultState.kt       Pure resolve/unlock-required logic + secret CRUD ops
      SecretResolver.kt   vault:// URIs -> passwords / key passphrases / PEM files
    sync/
      TabbySyncApi.kt     OkHttp client for the Tabby Sync server endpoints
      SyncRepository.kt   Loaded{domain,secrets,needsPassphrase,store,unlockRequired},
                          decrypt/update/delete with RAW preservation
    ssh/
      SshConnector.kt     sshj sessions, exec + shell channels, TOFU host-key guard,
                          multi-key auth, PTY window-change
    term/
      TerminalEmulator.kt Pure-Kotlin VT100/xterm subset (SGR, cursor, erase,
                          scroll/margins, wrap, alt-buffer, ?25, ?1049)
      TerminalInput.kt    Pure sticky CTRL/ALT mapping (c & 0x1F, ALT = ESC prefix)
  data/local/
    ConfigDisk.kt         EncryptedSharedPreferences: sync creds, RAW YAML cache,
                          known_hosts (TOFU), terminal prefs (font size)
    CrashLog.kt           Debug-only uncaught-exception recorder -> CrashReportScreen

app/src/test/... (10 files, 63 tests — §8)
```

## 3. Boot & navigation

`MainActivity.setContent` computes the start destination **before** the
NavHost composes (no post-compose navigation): crash report > load failure >
`profiles` (vault present or cached YAML) > `sync` (host+token known) >
`setup`. Routes: `setup`, `sync`, `profiles`, `ssh/{id}`, `edit/{id}` (+
settings sections). `AppState` is created once per `AppViewModel`
(`by viewModels()`), so rotation keeps the unlocked vault; the nav stack
itself resets (reconnect is one tap, no password re-asked) and SSH sessions
are screen-scoped (a rotate drops the live PTY by design, v1 scope).

## 4. Config Sync parity (Tabby Desktop)

- **Decrypt only when needed** (`maybeDecryptConfig` parity): boot list,
  upload, and metadata never decrypt. The passphrase is requested for:
  showing a vault password, editing a secret-backed field, first connect
  needing a secret, deleting a shell-encrypted profile.
- **Blob rule (desktop truth):** a stored blob is ALWAYS a container of
  secrets regardless of the profile's `encrypted` flag; the flag only
  describes the file shape (shell-encrypted vs full document + inline blob).
- **Upload strip/restore:** `configSync` is stripped on upload and restored
  from local on download; disabled `parts` (hotkeys/appearance/vault) merge
  from the correct side.
- **Secret URIs:** `ssh:password{user,host,port}` (exact match, then
  host-nulled fallback — never fuzzy), `ssh:key-passphrase{hash}`,
  `file{id}=base64(PEM)` <-> `vault://id`.
- **Host keys:** TOFU store in app prefs, kept separate from the desktop
  `ssh.knownHosts` format so uploads never pollute it. Unknown/changed keys
  prompt (new = trust dialog, changed = danger dialog).

## 5. Terminal pipeline

```
sshj shell PTY (xterm-256color)
  -> output Flow --(Main)--> emulator.feed(chunk) -> version++
  -> TerminalScreen tick -> TerminalCanvas redraw (skipped otherwise)
  -> keepCursorVisible snap (output + viewport effects)

typing: hidden 1px BasicTextField (autoCorrect OFF)
  -> diff (backspace = DEL, Enter = CR) -> session.send (IO dispatcher)
extra keys: sendSpecial bytes / sticky CTRL+ALT via TerminalInput
resize: measured grid -> emulator.resize + session window-change (RFC 4254)
```

- **Emulator** (`core/term`): pure Kotlin, fully unit-tested. Wrap is
  pending-wrap (consumed exactly once — regression-tested), grid keeps the
  top-left overlap on resize (stepwise shrinks are lossless vs one jump).
- **Render** (`TerminalView`): pure-black full-bleed Canvas. Backgrounds are
  merged per contiguous run (default BG skipped — the backdrop covers it);
  text is **one AnnotatedString layout per row** (~40 layouts/frame instead
  of ~1000 single-cell layouts). Columns are exactly one measured monospace
  advance wide (`rememberTerminalCell` averages 40 glyphs, so laid-out rows
  land pixel-perfect on the grid). The Canvas lives in `TerminalCanvas`,
  whose params are all Compose-stable (`State` holder + `version` + scalars),
  so size-only recompositions skip the redraw entirely; every emulator
  mutation bumps `version`, so `(ref, version)` fully describes the view.
- **Scroll:** `keepCursorVisible` (2-line / 8-column margin) snaps on new
  output and on viewport change. Single snap, no glide, no trailing motion.
- **Layout, not overlay:** the terminal grid (`weight=1f`) and the key bars
  are Column siblings that take real layout space (tabby-android `kb-spacer`
  pattern), so the grid can never slide behind the bars — no reserve math.
  6dp side padding keeps edge columns clear of screen protectors.

## 6. Keyboard dock ("lompat", not slide)

The activity window does **not** shrink (`frame=[0,0][1080,2400]` with
`adjustResize` on this stack), so the container reserves space itself:

- `WindowInsets.ime.getBottom()` is the source of truth (0 when the window
  resizes instead — then the dock is a no-op; never double-counted).
- Only the **settled** value is applied (`delay(50)` quiet period): during
  the slide the scope doesn't recompose at all; at settle bar + grid jump
  once. No per-frame tracking, no follow-up motion.
- **Instant open:** the last settled keyboard height is remembered; the bar
  jumps to it on the first open frame, the settle pass only corrects
  mismatches (orientation changes reset the cache).
- **Shave (`imeShavePx = 44.dp`, one constant):** SwiftKey claims ~35dp more
  inset than its visible keys (hidden toolbar slot). The shave docks the bar
  onto the visible keys. Condition: SwiftKey with toolbar OFF. If its toolbar
  is ever shown (or a tighter keyboard like Gboard is used), lower the
  constant — at most that strip of the bar bottom is covered.

## 7. UI conventions

- English-only UI strings/comments; dark-only theme (`IzsDarkColors`,
  terminal pure black, `#1E2A34` header bar, black docked key bars).
- Extra-keys rows: `ESC / - HOME UP END PGUP` and
  `TAB CTRL ALT LEFT DOWN RIGHT PGDN`; special keys bypass stickies via
  `sendSpecial`. Font size pref `terminal.fontSp` (8–24sp, default 14).
- Header is slim: title + copy + box-mode toggle + `⋮` menu (font ±, extra
  keys on/off) + ✕ disconnect. Copy screen puts `plainText()` on clipboard.
- `http://` sync hosts allowed for self-hosted LAN (with in-app warning).

## 8. Testing

`./gradlew :app:testDebugUnitTest` — 63 tests, 0 failures (pure JVM, no device):

| File | Covers |
|---|---|
| `DesktopParityTest` | decrypt-when-needed matrix |
| `DesktopVaultInteropTest` | real desktop `vault.json` fixture interop |
| `RawRoundTripTest` | lossless upload/download round-trips |
| `SecretStoreTest` | secret CRUD + `updateProfile`/`deleteProfile` matrix |
| `VaultCryptoTest` | PBKDF2/AES vectors, `BAD_DECRYPT` |
| `VaultManageTest` | set/change/erase passphrase, encrypt-config toggle |
| `VaultStateTest` | pure resolve + `unlockRequired` rules |
| `TerminalEmulatorTest` | VT100 ops + pending-wrap regression |
| `TerminalInputTest` | sticky CTRL/ALT mapping |
| `SshCryptoProviderTest` | BC provider registration (X25519) |

## 9. Build & diagnostics

- Gradle 9.7.1, AGP 9.4.0, Kotlin 2.4.20, Compose BOM 2026.08.00,
  navigation 2.10.0, OkHttp 5.5.0, SnakeYAML 2.7, security-crypto 1.1.0,
  sshj 0.40.0, BC 1.85.x, lifecycle-viewmodel(-ktx) 2.11.0.
- compileSdk 37, targetSdk 36, minSdk 26. Manifest
  `windowSoftInputMode="adjustResize"`, label `izs SSH`.
- `./gradlew :app:assembleDebug` -> `app-debug.apk`; install with
  `adb install -r`, read logs with `adb logcat`, screenshot with
  `adb shell screencap -p`.
- `CrashLog` (debug builds only) persists the last crash trace; the next
  launch offers the Crash Report screen with copy.

## 10. Roadmap (missing vs Tabby config.yaml / tabby-android)

- **Scrollback + buffer setting:** emulator keeps the live grid only
  (`MAX_HISTORY` const exists but unwired); add scrollback store + visible
  scroll + `tabby-scrollback`-style size setting.
- **Text selection with handles:** long-press -> start/end drag handles +
  floating Copy/Paste bar (tabby-android pattern), replacing screen-copy.
- **Full profile editor parity:** every `config.yaml` key editable and
  honored (advanced SSH opts, keepalive, ciphers, port forwarding,
  proxy/jumpHost, terminal type) — currently connection basics only, so
  parts of an imported YAML are display-only.
- **jumpHost / proxyCommand / SOCKS-HTTP:** currently a "scheduled" stub.
- **Rotation keeping the live PTY** (session is screen-scoped today).
- Multi-window / font-choice polish, search-in-buffer.
