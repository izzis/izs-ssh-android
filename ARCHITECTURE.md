# ARCHITECTURE — ssh-client-android

Native Android SSH client (`id.web.izs.sshclient`, "izs SSH") with **Tabby
Desktop Config Sync parity** plus a Termux-like interactive PTY terminal.
Single module (`:app`), Kotlin + Jetpack Compose, no WebView.

Upstream references (behavioral parity, not code):
Tabby Desktop `config.service.ts` / `vault.service.ts`
(`tabby-core`), `configSync.service.ts` (`tabby-settings`).

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
   one discrete jump. No per-frame
   animation tracking, no follow-up motion.
5. **Cheap frames on weak phones.** The grid redraws only when emulator
   output arrives; keyboard-animation frames skip the Canvas entirely;
   text is laid out once per row, not per cell.

## 2. Module map

```
app/src/main/java/id/web/izs/sshclient/
  MainActivity.kt                 Boot sequence, NavHost, owns AppViewModel;
                                  shallow openProfile (sheet picks land without
                                  stacking), global session-limit dialog
  ui/
    AppViewModel.kt               Rotation-safe holder of AppState (passphrase survives rotate)
    SshSessionViewModel.kt        Multi-session registry: PTYs survive rotate+nav, reuseSession parity, cap 5/8,
                                  selected tab + hasActivity flag + observable hasShell (never branch UI on the plain shell field)
    AppState.kt                   Session state: Loaded, unlock(), profile/secret selectors
    Theme.kt                      AppPalettes (Izs/Ocean/Forest/Sunset/Grape dark+light;
                                  shared Izs surfaces) + resolveAppPalette; terminal
                                  stage + key bar excluded by design)
    screens/
      ScreenHeader.kt           Shared sub-screen top bar (back arrow + title)
      ConfigSyncScreen.kt         Connection + cloud configs + up/download (Settings only)
      ProfileListScreen.kt        Home: single LazyColumn (header + Active + Recent +
                                  sticky search + profiles share one scroll, so long
                                  Active/Recent never squeezes the profile viewport);
                                  Active always expanded with Close all, Recent
                                  collapsible with per-row History icons + Clear,
                                  identity-colour stripe per profile row,
                                  exit-with-confirm top-bar button
      NewTabSheet.kt              Quick-pick bottom sheet (search + recent + grouped
                                  profiles, desktop-selector parity; half by
                                  default, draggable to full)
      ProfileEditScreen.kt        Tabbed editor (General + colour picker / Ports / Advanced /
                                   Ciphers / Colours (terminal-scheme override: Use-global +
                                   scheme search) / Login), desktop-only
                                   options labeled, new profile + new group
      TerminalScreen.kt           PTY session: connect, input, dock, extra keys, box mode,
                                  warn-on-close + host-key trust dialogs
      TerminalView.kt             Grid + scrollback Canvas, pinned follow-bottom, measured cells
      TerminalSettingsScreen.kt   Scrollback + macro delay + sessions (font size moved to Appearance)
      AppearanceSettingsScreen.kt App theme (device-only) + terminal font/cursor (YAML) + font size + live preview
      WindowSettingsScreen.kt     appearance.tabsLocation: Follow-synced vs This-device-only source priority + Off/Top/Bottom/Left/Right;
                                  New-tab mode (profile list vs quick-pick sheet, device-only pref)
      SessionTabs.kt (components/) Tab strip (top/bottom, VM-hoisted scroll) + side drawer frame (left/right, no RTL mirror) +
                                  single status dot + primary activity underline (cleared on select) + pinned Profile-list/Settings footer
      ConfigFileScreen.kt         Live RAW YAML view (parity with desktop `_store`)
      VaultUnlockDialog.kt        Passphrase prompt (lazy: only when needed)
      SetVaultPassphraseDialog.kt Set/change vault passphrase
      VaultSettingsScreen.kt      Vault management (set/change/erase, encrypt-config toggle)
      SshSettingsScreen.kt        SSH defaults: host-key verification + warn-on-close
                                  (desktop Settings > SSH parity; live-save, plaintext only)
      SettingsScreen.kt           Sidebar mirroring desktop Settings sections
      CrashReportScreen.kt        Shows last crash trace with copy button
  core/
    config/
      TabbyModels.kt      Domain models: SshProfile, ProfileGroup, options, SshGlobals
      TabLocation.kt      appearance.tabsLocation mapping (OFF/TOP/BOTTOM/LEFT/RIGHT) + FOLLOW_YAML/LOCAL source priority (pure)
      SshDefaults.kt      Transient defaults applied on the domain view only
      ConfigMigrator.kt   Legacy migrations (name-based groups -> ids, jump hosts)
      RawConfigStore.kt   RAW YAML document ops (update/delete profile, secrets JSON,
                          terminal.showRecentProfiles + appearance.tabsLocation read/write — desktop-owned keys, never invented)
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
      SshConnector.kt     sshj sessions, exec + shell channels, desktop-format host-key
                          trust prompt, multi-key auth, PTY window-change, keepalive,
                          login scripts
      HostKeyTrust.kt     Trust decisions on desktop ssh.knownHosts (sha256 wire digest,
                          exact host/port/type match, known-first negotiation order,
                          legacy prefs self-healing upgrade — pure JVM)
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
                          known_hosts (TOFU), terminal prefs (font size),
                          Android-only home.recentProfiles + window.tabSource/tabLocation/window.newTabMode (never synced to YAML).
                          security-crypto 1.1.0 deprecated the API wholesale
                          (suppressed; revisit on a DataStore+Tink migration)
    CrashLog.kt           Debug-only uncaught-exception recorder -> CrashReportScreen

app/src/test/... (24 files, 205 tests — §8)
```

## 3. Boot & navigation

`MainActivity.setContent` computes the start destination **before** the
NavHost composes (no post-compose navigation): crash report > load failure >
`profiles` — always. Fresh installs seed an empty config; Tabby Sync is
reached from Settings and never gates boot (the `setup`/`sync` start routes
and `SyncSetupScreen` are deleted). Routes: `profiles`, `ssh/{id}`,
`edit/new`, `edit/{id}` (+ settings sections). `AppState` is created once per `AppViewModel`
(`by viewModels()`), so rotation keeps the unlocked vault; the nav stack
itself resets (no password re-asked). SSH sessions live in
`SshSessionViewModel` (also `by viewModels()`, keyed by session UUID), so a
rotate or a trip back to the list never drops the live PTY — Back goes home
(popBackStack-first, synchronous) and only the explicit disconnect control
closes a session (per-session `warnOnClose` dialog; the header status dot
always confirms). Session hops use shallow navigate (`launchSingleTop` +
`popUpTo("profiles")`) so the stack never grows `ssh/A → ssh/B → ssh/C`.

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
- **Host keys (single source: `ssh.knownHosts` in YAML):** unknown/changed
  keys NEVER auto-trust — the connect pauses with a desktop-parity dialog
  (MITM warning + previous fingerprint on mismatch; Accept and remember /
  just this once / Disconnect). Remember writes the desktop-format entry
  locally (uploaded later via normal sync); `verifyHostKeys=false` trusts
  silently. Accept-and-remember connects FIRST on session-only trust and
  persists in the background — no Disconnected flash while an encrypted
  store rewrites (the persisted entry only matters for future sessions).
  Negotiation is known-first, desktop order on defaults
  (ecdsa before ed25519 — sshj's own default would pick otherwise, and the
  verifier list alone can't reorder: sshj's `Proposal` only uses it as a
  membership filter, so the per-connection config carries the order).
  Legacy prefs-era trust self-heals into YAML entries on match.

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
extra keys: user-editable layout (ExtraKeyboard model: ordered send steps
  with preset/text kinds, modifiers, escape codec, legacy-send migration,
  normalize+fallback) rendered by one shared ExtraKeysBar composable in the
  terminal and the editor preview; multi-step macros send staged with a
  settle delay; device-local JSON, never synced
resize: measured grid -> settle-debounced (150ms) emulator.resize +
  session window-change (RFC 4254); layout/scroll track live, reflow waits
```

- **Emulator** (`core/term`): pure Kotlin, fully unit-tested. Wrap is
  pending-wrap (consumed exactly once — regression-tested), grid keeps the
  top-left overlap on resize (stepwise shrinks are lossless vs one jump).
- **Render** (`TerminalView`): scheme-bg full-bleed Canvas. Backgrounds are
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
  are Column siblings that take real layout space, so the grid can never slide behind the bars — no reserve math.
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
- Two height signals, imitating the `visualViewport` approach: the claimed `WindowInsets.ime` plus
  the ACTUALLY-visible rect (`decorView.getWindowVisibleDisplayFrame`,
  15% threshold filters the nav bar). When the visible rect is notably
  smaller than the claim it is trusted with zero shave (the true keys);
  otherwise `ime - imeShavePx` as before.
- Skip redundant sets (`if (t != dockPx)`), small `delay(10)` in the hot path, and a 500ms re-assert safety net. Every apply logs `ImeDock ime= vis=
  useVis= dock=` to logcat for on-device verification.
  History: single `50` (jump) → `0` (tracked live) → `10` → `5` (open fast,
  close looked animated) → direction-aware `5`+`150` → single `10` →
  dual-signal tracking + 500ms net.
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
  terminal follows the active scheme background, themed surfaces, black docked key bars).
- All sub-screens share `ScreenHeader` (back arrow + title); the terminal
  header matches it (themed surface) with a status dot on the name row —
  green = connected, amber = connecting, red = disconnected. The dot always
  asks before disconnecting (it is a 32dp invisible tap target — an instant
  silent kill reads exactly like a dropped session); the power button honors
  `warnOnClose` like desktop — and full-width `user@host:port` below.
- Home Recent section (desktop `recentProfiles` parity, per-row History icons
  like the desktop selector, default card colour): header lives outside the
  card (title + Clear + collapse), collapsible, sized by
  `terminal.showRecentProfiles` (0 = off, hidden while searching); Active
  card above it is always expanded with Close all.
  Settings > Window edits the
  desktop `appearance.tabsLocation` (Off removes the key; encrypted configs
  stay writable but Android ignores the value) or picks This-device-only
  (local pref, YAML ignored for display — the painless encrypted path);
  New-tab mode (list vs sheet) is a second device-only pref.
- Profile identity color: dot selector beside Name in the General tab
  (presets + Default; custom desktop hex shows as an extra swatch), stripe
  on list rows (absent without a stored color). Terminal scheme override
  lives in the Colours tab (Use-global + scheme search) and Settings >
  Color scheme. Options with no mobile
  effect (forwarding, x11/agent/banner/reuse, non-direct modes) carry a
  desktop-only note in the editor instead of failing silently.
- Extra-keys rows: `ESC / - HOME UP END PGUP` and
  `TAB CTRL ALT LEFT DOWN RIGHT PGDN`; special keys bypass stickies via
  `sendSpecial`. Font size pref `terminal.fontSp` (8–24sp, default 14,
  Settings > Appearance, device-only).
- Terminal header extras: copy (puts `plainText()` on clipboard) +
  box-mode toggle + `⋮` menu (font ±, extra keys on/off).
- Connecting row: live stage text from the connector (crypto / connect /
  password / key i-of-n / shell / login scripts) instead of a spinner, with
  Cancel at the far right aborting the in-flight job (quiet, never a failure).
- Text selection: born ONLY from a committed hold (word) or a triple-tap
  (line). Two-stage hold: 300ms ticks haptically (release = commit word,
  move = scroll); ~600ms commits and extends the nearest endpoint until
  release. Summoning is vetoed once scrolled content moves (24dp drift
  backstop for clamped edges). Endpoints also move via immediate handle
  drags (press-on-handle locks scroll at down); edge-zone auto-scroll. Endpoints move via immediate handle drags (a press on a
  handle locks scroll at down, so no second hold is needed; dragging into
  the edge zone auto-scrolls).
  Scroll stays on even while selecting (it locks only for an armed endpoint
  drag); tap clears. Copy/Paste float above the selection in an opaque
  pill (below when no room; Paste sends the clipboard to the session,
  mirrors it into the input buffer so backspace keeps working, and refocuses
  the keyboard).
  While selecting, user scrolling is off, output-follow freezes, tap clears;
  absolute rows are scroll-stable so handles track the text (a history
  shrink, resize, font change, or alt-buffer switch drops the selection).
- `http://` sync hosts allowed for self-hosted LAN (with in-app warning).

## 8. Testing

`./gradlew :app:testDebugUnitTest` — 205 tests, 0 failures (pure JVM, no device):

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
| `TextSelectionTest` | range extraction, order/clamp, scroll-stability, word expansion |
| `TerminalInputTest` | sticky CTRL/ALT mapping |
| `ProfileFieldsTest` | profile full-set parse, defaults-omitted write, id shape, inline helpers, color/icon round-trip, global warnOnClose |
| `HostKeyTrustTest` | ssh-keygen digest vector, exact match/mismatch/port identity, legacy upgrade, negotiation order, knownHosts upsert |
| `LoginScriptRunnerTest` | unconditional/expect/regex/optional/break/unescape parity |
| `SshAlgorithmFactoriesTest` | defaults resolve (known skips), order, null-on-defaults, per-category fallback |
| `SshCryptoProviderTest` | BC provider registration (X25519) |
| `ExtraKeyboardTest` | layout normalize/clamp, escape codec, save-load, corrupt fallback, strict import |
| `SshSessionRegistryTest` | new tab per tap, cap + slot reclaim, multiplexer key format |
| `SshMultiplexTest` | shared-close releases pool ref without channel-close (MINA) |
| `TabLocationTest` | tabsLocation resolver + FOLLOW_YAML/LOCAL priority + encrypted-OFF rules |
| `SessionActivityTest` | background-output activity flag, select clears, close clears selection |
| `RecentProfilesTest` | recordRecent dedup/cap/disable parity |
| `ColorSchemeTest` | scheme parse/normalize/round-trip, readability gates, resolution order, YAML compat, emulator palette + remap, upsert/delete, JSON, shades |
| `TerminalAppearanceTest` | font/cursor parse + fallback, YAML set/remove round-trip |
| `AppPaletteTest` | palette resolve fallback, dark/light distinctness |

## 9. Build & diagnostics

- Gradle 9.7.1, AGP 9.4.0, Kotlin 2.4.20, Compose BOM 2026.08.00,
  navigation 2.10.0, OkHttp 5.5.0, SnakeYAML 2.7, security-crypto 1.1.0,
  sshj 0.40.0, BC 1.85.x, lifecycle-viewmodel(-ktx) 2.11.0.
- compileSdk 37, targetSdk 36, minSdk 26. Manifest
  `windowSoftInputMode="adjustResize"`, label `izs SSH`.
- `./gradlew :app:assembleDebug` -> `app-debug.apk`; install with
  `adb install -r`, read logs with `adb logcat`, screenshot with
  `adb shell screencap -p`.
- **Zero-warning policy:** `assembleDebug` + `compileDebugUnitTestKotlin`
  must emit 0 `w:` lines. Fix the code first (AutoMirrored icons,
  `PrimaryScrollableTabRow`, `menuAnchor(type)`, `LocalClipboard`,
  lifecycle-compose owner, `autoCorrectEnabled`, smart-cast simplifications).
  `@Suppress` is the LAST resort — never slap it on just to silence the
  compiler. Each suppression must carry a comment stating WHY it is safe and
  WHAT would remove it. Allowed today, and only these two: dynamic
  YAML/JSON `Map<String, Any?>` casts (keys are strings by construction —
  a per-entry re-check would only add copies, gone if the RAW layer ever
  gets typed models) and whole-library deprecations with no drop-in
  (security-crypto 1.1.0, revisit on DataStore+Tink). Verify with a clean
  `--rerun-tasks` build: incremental builds do not re-emit warnings for
  unchanged files.
- `CrashLog` (debug builds only) persists the last crash trace; the next
  launch offers the Crash Report screen with copy.

## 10. Roadmap (missing vs Tabby config.yaml)

- **Multi-session (done):** session registry in `SshSessionViewModel`
  (PTYs survive nav + rotation, which fixed the rotation-PTY drop); every
  profile tap opens a new tab while `reuseSession=true` (default) shares one
  TCP transport per `host:port:user:proxy…` key (desktop multiplexer parity —
  extra tabs skip re-auth, one reader pump per channel, refcounted teardown);
  closing a tab on a shared transport releases the pool ref WITHOUT sending
  channel-close (desktop `shell.ts` destroy parity — some servers kill the
  whole connection on channel close; the abandoned remote shell lingers until
  the last tab's disconnect, exactly like desktop);
  the home list shows an Active-sessions section (green/amber/red dot)
  replacing disconnect-on-back; per-session warn-on-close; cap on concurrent
   sessions (default 5, hard max 8, tunable in Settings > Terminal, now
   scrollable). Reader-pump death marks tabs failed (red + Retry): single
   `exit` fails only its tab, a dead transport fails all riders.
- **Tab chrome (done):** strip for `top`/`bottom` (status dot + profile
  name + primary activity underline + × + `+`; scroll hoisted to the VM because every tab
  is its own destination), custom side drawer for `left`/`right` (M3 drawer
  is start-side only — whole-screen RTL mirroring is rejected; hamburger
  replaces Back, edge-fling (32dp system-Back reserve) opens it, pinned
  Profile-list/Settings footer, scrim/Back closes), Back always goes
home via synchronous popBackStack. Socket teardown runs off-Main (a stalled
VPN must never freeze the terminal mid-tap).
- **Tab UX polish (done):** home shares one LazyColumn (Active always
  expanded with Close all in the header row, Recent collapsible with
  per-row History icons + Clear, sticky search); `NewTabSheet.kt` quick-pick
  (search + recent + group sections, desktop-selector parity; half by
  default via partial anchor, draggable to full); `window.newTabMode`
  device-only pref + Settings > Window radio; primary activity underline
  (tertiary reads red on this theme); drawer footer Profile list + Settings;
  drawer open fling reserves only the 32dp system-Back edge; sheet picks reuse
  shallow `openProfile`; session-limit dialog moved global (visible from the
  sheet, not just home).
- **Appearance (done):** `AppearanceSettingsScreen` — app theme
  (System/Dark/Light, device-only `ConfigDisk appearance.appTheme`) +
  app color palettes (`ui/Theme.kt AppPalettes`: Izs/Ocean/Forest/Sunset/
  Grape, device-only `appearance.appPalette`, terminal untouched),
  terminal font (system monospace or bundled Source Code Pro,
  `terminal.font` YAML), font size (device-only, moved from Terminal),
  cursor style + blink (`terminal.cursor`/`cursorBlink` YAML, live on
  open sessions via a blink-gated cursor overlay), live preview (font +
  size + cursor in the active scheme colors, same resolution as
  TerminalScreen). No scheme set anywhere +
  light app theme = light Izs terminal default (explicit schemes always
  win). Desktop-only keys (vibrancy,
  custom CSS, window frame) stay desktop-managed, RAW-lossless.
- **Color scheme (done):** `core/config/ColorScheme.kt` (desktop
  `theme.ts` shape; parse/normalize/toRawMap/readability gates/contrast;
  JSON ser via kotlinx.serialization for the device pref; `SchemeSource` +
  `resolveActiveScheme` (profile > device-local | synced-global > Izs);
  `upsertCustom`/`deleteCustomByName` saveScheme parity; pure JVM) +
  `assets/color_schemes.json` (102 built-ins, curated from 191 XResources;
  light schemes gated on black/bright-black contrast + background visibility).
  Global `terminal.colorScheme` + `terminal.customColorSchemes` +
  per-profile `terminalColorScheme` (null = follow global) in synced YAML
  via `RawConfigStore` readers/writers + `updateProfileMap` (null removes
  the key); sibling keys (`lightColorScheme`, unknown) never touched
  (desktop-compat test). `SyncRepository.updateTerminalSection` (plaintext
  outer edit; encrypted shells rewrite the blob, direct Loaded without
  re-decrypt); `AppState.adopt` skips the redundant refresh cycle.
  `TerminalEmulator` palette is per-session instance state + `remapCells`
  (whole screen follows a switch; 256/truecolor untouched). `drawTerminal`
  backdrop + missing-cell fallbacks use the palette; TerminalScreen owns the
  single apply path (`LaunchedEffect`, scheme-change only) + stage bg.
  UI mirrors desktop: Current header + Edit/Delete, search + full-preview
  rows with Custom badges (customs first), editor (22 dots with desktop
  FG/BG/CU/CA/SB/SF + ANSI labels, long-press tooltips, 4×5 family grid +
  9-step + hex picker, live preview, warnings never block), profile Colours
  tab (Use-global + search). Source toggle (tabSource parity, ConfigDisk
  `terminal.schemeSource`/`localScheme`): device picks apply instantly
  (plain pref); device edits apply instantly and upsert the shared pool
  (vault-aware). Terminal-content only;
  `selectionForeground`/`cursorAccent` stored-but-unused;
  `lightColorScheme`/`colorSchemeMode` ignored (dark-only app).
- **Compose staleness lesson (phantom-Disconnected):** never branch UI on the
  plain `shell` field — the branch group can keep evaluating a stale null
  forever (green dot + Disconnected + dead Reconnect on a live session;
  logging masks it by reshuffling recomposition timing). Shell presence is
  the observable `hasShell` flow, updated at every assignment site;
  composition branches on the flow, event handlers read the field fresh.
- **Port forwarding:** open Local/Remote/Dynamic at connect (saved today).
- **jumpHost / proxyCommand / SOCKS-HTTP:** saved to YAML via the
  `connectionMode` dropdown (other-mode fields nulled on save, desktop
  priority), but connect is direct-only — a "scheduled" stub.
- Multi-window / font-choice polish, search-in-buffer.
