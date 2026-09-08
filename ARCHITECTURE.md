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
      ScreenHeader.kt           Shared sub-screen top bar (back arrow + title)
      ConfigSyncScreen.kt         Connection + cloud configs + up/download (Settings only)
      ProfileListScreen.kt        Home: logo + name header, count, add button, groups + search,
                                  identity-colour stripe per profile row
      ProfileEditScreen.kt        Tabbed editor (General + colour picker / Ports / Advanced /
                                  Ciphers / Colours-scheme-placeholder / Login), desktop-only
                                  options labeled, new profile + new group
      TerminalScreen.kt           PTY session: connect, input, dock, extra keys, box mode,
                                  warn-on-close confirm dialog
      TerminalView.kt             Grid + scrollback Canvas, pinned follow-bottom, measured cells
      TerminalSettingsScreen.kt   Font size + scrollback buffer (applies live)
      ConfigFileScreen.kt         Live RAW YAML view (parity with desktop `_store`)
      VaultUnlockDialog.kt        Passphrase prompt (lazy: only when needed)
      SetVaultPassphraseDialog.kt Set/change vault passphrase
      VaultSettingsScreen.kt      Vault management (set/change/erase, encrypt-config toggle)
      SshSettingsScreen.kt        SSH defaults: host-key verification + warn-on-close
                                  (desktop Settings > SSH parity; plaintext only)
      SettingsScreen.kt           Sidebar mirroring desktop Settings sections
      CrashReportScreen.kt        Shows last crash trace with copy button
      PlaceholderSettingScreen.kt "Scheduled" stubs (colours, proxy connect, etc.)
  core/
    config/
      TabbyModels.kt      Domain models: SshProfile, ProfileGroup, options, SshGlobals
      SshDefaults.kt      Transient defaults applied on the domain view only
      ConfigMigrator.kt   Legacy migrations (name-based groups -> ids, jump hosts)
      RawConfigStore.kt   RAW YAML document ops (update/delete profile, secrets JSON)
      ProfileColor.kt     Identity-color palette + hex normalize/parse (pure JVM)
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
                          multi-key auth, PTY window-change, keepalive, login scripts
      LoginScriptRunner.kt Ordered expect/send automation (desktop LoginScriptProcessor
                          parity, pure JVM)
      SshAlgorithmFactories.kt Profile cipher/kex/mac/hostkey/compression wire names ->
                          sshj factories, unknown skipped (pure JVM + Config build)
    term/
      TerminalEmulator.kt Pure-Kotlin VT100/xterm subset (SGR, cursor, erase,
                          scroll/margins, wrap, alt-buffer, ?25, ?1049) +
                          scrollback history deque capped by maxHistory
      TerminalInput.kt    Pure sticky CTRL/ALT mapping (c & 0x1F, ALT = ESC prefix)
  data/local/
    ConfigDisk.kt         EncryptedSharedPreferences: sync creds, RAW YAML cache,
                          known_hosts (TOFU), terminal prefs (font size)
    CrashLog.kt           Debug-only uncaught-exception recorder -> CrashReportScreen

app/src/test/... (13 files, 92 tests — §8)
```

## 3. Boot & navigation

`MainActivity.setContent` computes the start destination **before** the
NavHost composes (no post-compose navigation): crash report > load failure >
`profiles` — always. Fresh installs seed an empty config; Tabby Sync is
reached from Settings and never gates boot (the `setup`/`sync` start routes
and `SyncSetupScreen` are deleted). Routes: `profiles`, `ssh/{id}`,
`edit/new`, `edit/{id}` (+ settings sections). `AppState` is created once per `AppViewModel`
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
- **Set-vault sweep:** setting a vault passphrase moves inline plaintext
  passwords + PEM keys into the vault once (single save, no duplicates).
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
  -> diff (backspace = DEL, Enter = CR) -> session.send (IO dispatcher);
  Enter/buffer-cap calls resetImeLine() (clear + restartInput, keyboard
  stays open) so predictions start fresh each line — WebView-clear parity.
extra keys: sendSpecial bytes / sticky CTRL+ALT via TerminalInput
resize: measured grid -> settle-debounced (150ms) emulator.resize +
  session window-change (RFC 4254); layout/scroll track live, reflow waits
```

- **Emulator** (`core/term`): pure Kotlin, fully unit-tested. Wrap is
  pending-wrap (consumed exactly once — regression-tested), grid keeps the
  top-left overlap on resize (stepwise shrinks are lossless vs one jump).
- **Render** (`TerminalView`): pure-black full-bleed Canvas. Backgrounds are
  merged per contiguous run (default BG skipped — the backdrop covers it);
  text is **one AnnotatedString layout per row** (~40 layouts/frame instead
  of ~1000 single-cell layouts), and only the **visible window + overscan**
  is drawn at all — the canvas keeps full content size (correct scroll
  extents) but off-screen rows emit zero draw ops, so cost is O(visible)
  even with 10k scrollback lines. Spans merge per style run (80
  single-char spans made paragraph layout pathological at ~15ms/row
  measured; typical rows emit a handful). Scrolling inside the baked
  window is pure GPU translation — a redraw happens only on content
  change (gated by drawTick: history-only windows skip while reading
  during output floods), window exit, or size settle — never per scroll
  pixel or per cursor-follow snap (the old derived window re-laid the
  whole frame on each: the keyboard-toggle freeze). Columns are exactly one measured monospace
  advance wide (`rememberTerminalCell` averages 40 glyphs, so laid-out rows
  land pixel-perfect on the grid). The Canvas lives in `TerminalCanvas`,
  whose params are all Compose-stable (`State` holder + `version` + scalars),
  so size-only recompositions skip the redraw entirely; every emulator
  mutation bumps `version`, so `(ref, version)` fully describes the view.
  Programmatic scrolls never run while a finger is down (`touching` flag —
  `isScrollInProgress` only covers post-slop drags). DEBUG builds log
  `TvPerf` (draw/resize ms) and `TvScroll` (drag/follow/snap) telemetry.
- **Scroll:** `keepCursorVisible` (2-line / 8-column margin) snaps on new
  output and on viewport change. Single snap, no glide, no trailing motion.
- **Scrollback:** full-screen scrolls push the top row into a history deque
  capped by `maxHistory` (Settings > Terminal: stepper + free input,
  default 5000 lines, 0 = off, max 100000; lowering trims immediately). The view renders history + grid as one
  continuous block (history hidden under the alt buffer; pre-resize rows
  padded). A drag ending above the live edge unpins (`pinned=false`); new
  output follows only while pinned, and rows prepended above are
  compensated so the view stays on the same text. Copy includes history.
- **Layout, not overlay:** the terminal grid (`weight=1f`) and the key bars
  are Column siblings that take real layout space (tabby-android `kb-spacer`
  pattern), so the grid can never slide behind the bars — no reserve math.
  6dp side padding keeps edge columns clear of screen protectors.
- **Connect honors the profile:** login scripts (`LoginScriptRunner`:
  unconditional at session-ready, then per-chunk expect/regex/optional
  matching, desktop quirk-for-quirk), keepalive interval as
  `KEEP_ALIVE` (SSH_MSG_IGNORE) heartbeats (`countMax` stored-only, no sshj
  equivalent), custom algorithms via per-connection `DefaultConfig` (desktop
  defaults take the plain `SSHClient()` path — zero behavior change).
  `warnOnClose` = per-profile override ?? global `ssh.warnOnClose`
  (default off); the confirm dialog guards live sessions only.

## 6. Keyboard dock ("lompat", not slide)

The activity window does **not** shrink (`frame=[0,0][1080,2400]` with
`adjustResize` on this stack), so the container reserves space itself:

- `WindowInsets.ime.getBottom()` is the source of truth (0 when the window
  resizes instead — then the dock is a no-op; never double-counted).
- Two height signals, imitating tabby-android's `visualViewport` math
  (`terminal.component.ts:424-443`): the claimed `WindowInsets.ime` plus
  the ACTUALLY-visible rect (`decorView.getWindowVisibleDisplayFrame`,
  15% threshold filters the nav bar). When the visible rect is notably
  smaller than the claim it is trusted with zero shave (the true keys);
  otherwise `ime - imeShavePx` as before.
- Skip redundant sets (`if (t != dockPx)`, like kb-spacer's height check),
  small `delay(10)` in the hot path, and a 500ms re-assert safety net
  (`extraBarInterval` parity). Every apply logs `ImeDock ime= vis=
  useVis= dock=` to logcat for on-device verification.
  History: single `50` (jump) → `0` (tracked live) → `10` → `5` (open fast,
  close looked animated) → direction-aware `5`+`150` → single `10` →
  tabby-android imitation (dual signal + 500ms net).
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
  terminal pure black, themed surfaces, black docked key bars).
- All sub-screens share `ScreenHeader` (back arrow + title); the terminal
  header matches it (themed surface) with a status dot on the name row —
  green = connected, amber = connecting, red = disconnected, tap to
  disconnect — and full-width `user@host:port` below.
- Profile identity color: dot selector beside Name in the General tab
  (presets + Default; custom desktop hex shows as an extra swatch), stripe
  on list rows (absent without a stored color). Terminal scheme stays in
  the Colours tab as a desktop-managed placeholder. Options with no mobile
  effect (forwarding, x11/agent/banner/reuse, non-direct modes) carry a
  desktop-only note in the editor instead of failing silently.
- Extra-keys rows: `ESC / - HOME UP END PGUP` and
  `TAB CTRL ALT LEFT DOWN RIGHT PGDN`; special keys bypass stickies via
  `sendSpecial`. Font size pref `terminal.fontSp` (8–24sp, default 14).
- Terminal header extras: copy (puts `plainText()` on clipboard) +
  box-mode toggle + `⋮` menu (font ±, extra keys on/off).
- `http://` sync hosts allowed for self-hosted LAN (with in-app warning).

## 8. Testing

`./gradlew :app:testDebugUnitTest` — 92 tests, 0 failures (pure JVM, no device):

| File | Covers |
|---|---|
| `DesktopParityTest` | decrypt-when-needed matrix |
| `DesktopVaultInteropTest` | real desktop `vault.json` fixture interop |
| `RawRoundTripTest` | lossless upload/download round-trips |
| `SecretStoreTest` | secret CRUD + `updateProfile`/`deleteProfile` matrix |
| `VaultCryptoTest` | PBKDF2/AES vectors, `BAD_DECRYPT` |
| `VaultManageTest` | set/change/erase passphrase, encrypt-config toggle |
| `VaultStateTest` | pure resolve + `unlockRequired` rules |
| `TerminalEmulatorTest` | VT100 ops + pending-wrap regression + scrollback cap/trim/alt |
| `TerminalInputTest` | sticky CTRL/ALT mapping |
| `ProfileFieldsTest` | profile full-set parse, defaults-omitted write, id shape, inline helpers, color/icon round-trip, global warnOnClose |
| `LoginScriptRunnerTest` | unconditional/expect/regex/optional/break/unescape parity |
| `SshAlgorithmFactoriesTest` | defaults resolve (known skips), order, null-on-defaults, per-category fallback |
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

- **Multi-session (todo — prerequisite for anything multiplexing-shaped):**
  session registry in `AppViewModel` (PTYs survive nav + rotation, which
  also fixes the rotation-PTY item below); session picker replacing
  disconnect-on-back; profile-colour strip as tab colour; per-session
  warn-on-close; cap on concurrent sessions for weak phones.
- **Port forwarding:** open Local/Remote/Dynamic at connect (saved today).
- **jumpHost / proxyCommand / SOCKS-HTTP:** saved to YAML via the
  `connectionMode` dropdown (other-mode fields nulled on save, desktop
  priority), but connect is direct-only — a "scheduled" stub.
- **Text selection with handles:** long-press -> start/end drag handles +
  floating Copy/Paste bar (tabby-android pattern), replacing screen-copy.
- Multi-window / font-choice polish, search-in-buffer.
