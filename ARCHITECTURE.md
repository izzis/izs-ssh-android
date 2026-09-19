# ARCHITECTURE — ssh-client-android

Native Android SSH client (`id.web.izs.sshclient`, "izs SSH") with **Tabby
terminal ([Eugeny/tabby](https://github.com/Eugeny/tabby)) Config Sync parity** plus a Termux-like interactive PTY terminal.
Single module (`:app`), Kotlin + Jetpack Compose, no WebView.

Upstream references (behavioral parity, not code):
Tabby terminal `config.service.ts` / `vault.service.ts`
(`tabby-core`), `configSync.service.ts` (`tabby-settings`).

## 1. Design principles

1. **Tabby parity, YAML-first.** Behavior (vault crypto, secret URIs, sync
   merge rules, lazy-unlock semantics) mirrors Tabby terminal exactly (all
   covered by tests). The on-disk YAML document is the source of truth:
   the domain model is a transient, defaulted *view*; uploads are rebuilt
   from RAW so unknown/future keys survive round-trips byte-identical.
2. **Secrets never touch disk.** The vault passphrase lives in RAM only
   (`VaultState`, inside `AppState`, inside `AppViewModel`). It is asked
   lazily — only when vault content is actually needed.

## 2. Module map

```
app/src/main/java/id/web/izs/sshclient/
  MainActivity.kt                 Boot sequence, NavHost, owns AppViewModel;
                                  shallow openProfile (sheet picks land without
                                  stacking), global session-limit dialog;
                                  installs the SessionService mirror
                                  (serviceSync/lostListener) + singleTop
                                  Disconnect-all target + AppForeground pump
  core/session/
    SessionService.kt             Foreground-service anchor (specialUse): exact
                                  "N sessions" notification (expandable
                                  per-host lines, Disconnect-all action),
                                   opt-in wake lock; pure text builders at
                                   file level for JVM tests
    BatteryOpt.kt               Battery-optimization exemption helpers
                                (background survival)
  ui/
    AppForeground.kt              Process foreground counter (no extra deps)
    AppViewModel.kt               Rotation-safe holder of AppState (passphrase survives rotate)
    SshSessionViewModel.kt        Multi-session registry: PTYs survive rotate+nav, reuseSession parity, cap 5/10,
                                    selected tab + hasActivity flag + observable hasShell (never branch UI on the plain shell field);
                                    per-session SftpTransferManager hosts (transfers survive back/dismiss/rotate,
                                    die only on close(); Back never reaches close());
                                    background mirror (serviceSync/lostListener, single auto-retry per transport death)
    AppState.kt                   Session state: Loaded, unlock(), profile/secret selectors
    Theme.kt                      AppPalettes (Grape/Ocean/Forest/Sunset dark+light;
                                  shared Izs surfaces) + resolveAppPalette;
                                  Follow color scheme (`schemeToAppColorScheme`:
                                  full M3 theme from the active non-profile
                                  scheme, dark/light auto from luminance;
                                  opt-in, device-only)
    screens/
       ScreenHeader.kt           Shared sub-screen top bar (back arrow + title + trailing busy spinner for YAML writes); header is sticky outside the scrolling column so the spinner does not jitter content
       AboutScreen.kt              Version + email feedback + source-code link +
                                   manual GitHub Releases update check (manual
                                   only, no background polling) + MIT license +
                                   third-party attributions
       ConfigSyncScreen.kt         Connection + cloud configs + up/download (Settings only, sticky header with busy spinner + centered busy dialog for list/transfer ops)
       ProfileListScreen.kt        Home: single LazyColumn (header + Active + Recent +
                                   sticky search + profiles share one scroll, so long
                                   Active/Recent never squeezes the profile viewport);
                                   Active always expanded with Close all, Recent
                                    collapsible with per-row History icons + Clear,
                                    identity-colour stripe per profile row,
                                    folder pencil (rename + reparent + delete group,
                                    members ungrouped, children to top level),
                                    profile ⋮ menu (Duplicate via editor
                                    copy-mode, Hide/Show via synced
                                    profileBlacklist + collapsed Hidden
                                    section, Delete with confirm; 40dp action
                                    buttons, 4dp row end-padding),
                                    exit-with-confirm top-bar button
        NewTabSheet.kt              Quick-pick bottom sheet (search + recent + grouped
                                    profiles, desktop-selector parity; custom
                                    sheet: header-only drag to half/full/hide,
                                    list owns all scrolls; blacklisted
                                    profiles filtered out, search included)
       ProfileEditScreen.kt        Tabbed editor (General + colour picker (FlowRow swatch
                                    grid — fixed chunked rows clipped the rightmost
                                    swatch into an oval on narrow phones) / Ports /
                                    Advanced / Ciphers / Colours (terminal-scheme
                                    override: Use-global + scheme search) / Login),
                                     desktop-only options labeled, new profile + new group; sticky header with busy spinner.
                                     `copy:<id>` duplicates one (editor
                                     pre-filled from the source, Save creates,
                                     Back cancels, Delete hidden)
       TerminalScreen.kt           PTY session: connect, input, dock, extra keys, box mode,
                                    warn-on-close + host-key trust dialogs, ⋮ menu SFTP entry,
                                    slim background-transfer indicator row (tap reopens the sheet),
                                    auth-failover `Password for user@host` dialog (remember checkbox,
                                    unlock-routed deferred vault save),
                                    hide-terminal-header mode (options to active-tab ⋮;
                                    floating ⋮ when tabs are off — draggable to any
                                     corner, anchor persisted in ConfigDisk);
                                     per-profile session chrome via nested
                                     ProfileChrome (top bar, tab strips/menus,
                                     SFTP, extra keys, command box — lists stay
                                     on the shared scheme); Copy stays silent
                                     (no banner)
       SftpSheet.kt                SFTP browser + transfers as a bottom sheet over the terminal
                                    (half by position via SheetState initial Partial, list
                                    fills sheet height so loads never balloon it, draggable
                                    to full): SAF Save-as/Choose-file, DISPLAY_NAME lookup,
                                    same-name Overwrite/Keep-both/Cancel dialog (dir frozen
                                    at pick time), per-item Cancel, Clear finished.
                                    UI-only: dismiss/back touches no transfer.
       TerminalView.kt             Grid + scrollback Canvas, pinned follow-bottom, measured cells;
                                   hold/triple-tap selection with back-gesture
                                   guards + dismissing Copy/Paste pill
       TerminalSettingsScreen.kt   Scrollback + macro delay + sessions (font size moved to Appearance) + recent profiles (synced YAML) + Clipboard 4 toggles (bracketed/warn/replace/trim, synced YAML); sticky header with busy spinner
       AppearanceSettingsScreen.kt App theme (device-only) + terminal font/cursor (YAML) + font size + live preview
                                   + Follow-color-scheme toggle (disables
                                   theme/palette while on); sticky header with busy spinner
       ColorSchemeSettingsScreen.kt Global/local scheme source + editor entry (sticky header with busy spinner);
                                   commits bump `AppState.schemeVersion` for a
                                   live re-theme
       ColorSchemeEditorScreen.kt  22-slot editor (sticky header with busy spinner)
       WindowSettingsScreen.kt     appearance.tabsLocation: Follow-synced vs This-device-only source priority + Off/Top/Bottom/Left/Right;
                                    New-tab mode (profile list vs quick-pick sheet, device-only pref);
                                    Hide-terminal-header toggle (device-only pref, whole-row tap)
       SessionTabs.kt (components/) Tab strip (top/bottom, VM-hoisted scroll, slim 32dp buttons,
                                    tight ⋮/× cluster, desktop `.colorbar` profile-colour bar
                                    sealed inside the rounded tab box; strip scrolls
                                    horizontally = unbounded width, so items bind
                                    `IntrinsicSize.Max` or fillMaxWidth underlines
                                    collapse to 0) + side drawer frame (left/right, no RTL mirror) +
                                    single status dot + primary activity underline (cleared on select) + pinned Profile-list/Settings footer
                                    (drawer ⋮ menu hides its own Settings/Profile-list copies)
       ConfigFileScreen.kt         Live RAW YAML view (parity with desktop `_store`, sticky header with busy spinner)
       VaultUnlockDialog.kt        Passphrase prompt (lazy: only when needed)
       SetVaultPassphraseDialog.kt Set/change vault passphrase
       VaultSettingsScreen.kt      Vault management (set/change/erase, encrypt-config toggle, sticky header with busy spinner)
       SshSettingsScreen.kt        SSH defaults: host-key verification + warn-on-close
                                   + background keep-awake toggle (device-only)
                                   (desktop Settings > SSH parity; live-save, vault-aware; sticky header with busy spinner)
       SettingsScreen.kt           Sidebar mirroring desktop Settings sections
       CrashReportScreen.kt        Shows last crash trace with copy button
       ColorSchemeComponents.kt    Shared scheme picker list + sample-line
                                   preview (used by Colours tab + global screen)
       ExtraKeysBar.kt             Docked extra-keys bar (shared by terminal +
                                   layout editor preview)
       KeyboardLayoutScreen.kt     Extra-keys layout editor (WYSIWYG preview +
                                   row/key editing, device-local)
       SshInputPipe.kt             Hidden-field IME input pipe (Termux
                                   onCreateInputConnection port)
  core/
    config/
      TabbyModels.kt      Domain models: SshProfile, ProfileGroup, options, SshGlobals
      TabLocation.kt      appearance.tabsLocation mapping (OFF/TOP/BOTTOM/LEFT/RIGHT) + FOLLOW_YAML/LOCAL source priority (pure)
      SshDefaults.kt      Transient defaults applied on the domain view only
      ConfigMigrator.kt   Legacy migrations (name-based groups -> ids, jump hosts)
      RawConfigStore.kt   RAW YAML document ops (update/delete profile, secrets JSON,
                          terminal.showRecentProfiles + appearance.tabsLocation + 4 clipboard keys (bracketed/warn/replace/trim,
                          delete-on-default + empty-map prune) read/write — desktop-owned keys, never invented)
      ProfileColor.kt     Identity-color palette + hex normalize/parse (pure JVM)
      ColorScheme.kt      TerminalColorScheme shape (theme.ts parity):
                          parse/normalize/readability gates, resolution order
                          (profile > device-local | synced-global > Izs),
                          upsert/delete customs, YAML compat (pure JVM)
      TerminalAppearance.kt terminal.font/cursor/cursorBlink keys (pure JVM)
    vault/
      VaultCrypto.kt      PBKDF2-HmacSHA512 x100k/salt8 -> AES-256-CBC/iv16 (pure JVM)
      VaultState.kt       Pure resolve/unlock-required logic + secret CRUD ops
      SecretResolver.kt   vault:// URIs -> passwords / key passphrases / PEM files
    sync/
      TabbySyncApi.kt     OkHttp client for the Tabby Sync server endpoints
      SyncRepository.kt   Loaded{domain,secrets,needsPassphrase,store,unlockRequired},
                          decrypt/update/delete with RAW preservation,
                          savePassword/deletePassword (prompt-password remember /
                          total-failure forget; vault secret or no-vault literal)
    ssh/
      SshConnector.kt     sshj sessions, exec + shell channels, desktop-format host-key
                           trust prompt, multi-key auth, PTY window-change, keepalive,
                           login scripts, typed SshAuthFailed + friendly reason
                           (never raw "Exhausted…") driving the password prompt
      SftpTransfer.kt     SFTP list/download/upload on an authenticated client: fresh
                           channel per transfer, 64 KB chunks + progress, cancel =
                           close-channel abort (surfaces CancellationException) +
                           partial-delete, source never touched; other failures ->
                           IllegalStateException
      SftpTransferManager.kt Session-scoped transfer host: one Job per transfer on the
                           owner's scope, StateFlow rows (RUNNING/DONE/FAILED/
                           CANCELLED + progress), per-item cancel(), cancelAll()
                           (wired to session close only), takeDownloadFile(id)
                           (once-only Save-as handshake, survives sheet reopen),
                           clearFinished() (drops rows + deletes untaken files)
      HostKeyTrust.kt     Trust decisions on desktop ssh.knownHosts (sha256 wire digest,
                          exact host/port/type match, known-first negotiation order,
                          legacy prefs self-healing upgrade — pure JVM)
      LoginScriptRunner.kt Ordered expect/send automation (desktop LoginScriptProcessor
                          parity, pure JVM)
      SshAlgorithmFactories.kt Profile cipher/kex/mac/hostkey/compression wire names ->
                           sshj factories, unknown skipped (pure JVM + Config build)
      PortForwarding.kt   Local + Remote forwarding at connect (addPortForward
                           parity); Dynamic stays desktop-only (pure JVM)
      SocksProxy.kt       Outbound SOCKS proxy for the transport (newSocksProxy
                           parity, default 1080; pure JVM)
    term/
      TerminalEmulator.kt Pure-Kotlin VT100/xterm subset (SGR, cursor, erase,
                          scroll/margins, wrap, alt-buffer, ?25, ?1049) +
                          scrollback history deque capped by maxHistory
      TerminalInput.kt    Pure sticky CTRL/ALT mapping (c & 0x1F, ALT = ESC prefix)
      ExtraKeyboard.kt    Editable on-screen extra-keys bar (dock rows below
                           the grid); device-local JSON, strict import (pure JVM)
      MonoFontCheck.kt    Proportional-system-monospace detection -> bundled
                           fallback (pure JVM)
      PipeInput.kt        Event-driven IME pipe commit mapping (pure JVM)
  data/local/
    TinkKvStore.kt        Encrypted KV backend (Tink AES256-GCM, Keystore-backed
                           keyset; batched single sealed write, corrupt-blob
                           quarantine) + unencrypted fallback
    ConfigDisk.kt         TinkKvStore (Tink AES256-GCM whole-blob, backend pinned once per
                           process; secret writes refused when encryption is
                           unavailable): sync behavior prefs (auto/parts/stamp),
                           RAW YAML cache, known_hosts (TOFU), terminal prefs
                           (font size),
                           Android-only home.recentProfiles + window.tabSource/tabLocation/window.newTabMode/window.hideTerminalHeader/window.fabAtBottom/window.fabAtLeft (never synced to YAML).
                           The sync target (host/token/configID) lives ONLY in
                           YAML > configSync (single source, RAM-mirrored after
                            load — never a prefs duplicate).
                            Replaces EncryptedSharedPreferences (security-crypto
                            deprecated wholesale, no drop-in successor) with the
                            same Tink engine used directly; pre-Tink store file
                            deleted on first boot (one-time alpha reset).
    CrashLog.kt           Debug-only uncaught-exception recorder -> CrashReportScreen

app/src/test/... (45 files, 362 tests — §8)
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

## 4. Config Sync parity (Tabby terminal)

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
- **Post-download re-encrypt (desktop `writeConfigDataFromSync` parity):**
  a fresh encrypted shell is saved verbatim, then re-encrypted once with a
  fresh salt/iv after the passphrase prompt — immediately when already
  unlocked (RAM passphrase reused, no extra prompt), otherwise on the first
  unlock. `keySalt` therefore rotates exactly like desktop; plaintext docs
  are never rewritten, and upload stays verbatim (no encrypt).
- **Failed-import restore:** before every download/import overwrite, the
  previous YAML + sync target (YAML `configSync` host/token/configID + prefs
  `lastRemoteChange`) + session passphrase are snapshotted in RAM
  + session passphrase are snapshotted in RAM (`preImportBackup*`,
  `pendingEncryptedRewrite` in `SyncRepository`, surfaced as
  `Loaded.pendingRewrite`). Cancelling/deleting before the first unlock
  (`abortPendingImport`) restores all three — the old config comes back
  already unlocked. Ordinary boot unlocks keep desktop "Erase config"
  semantics instead (erase → `refresh()` → seeded empty, so the UI never
  strands on a stale locked view).
- **Foreground auto-sync (desktop `autoSync` parity), both directions:**
  `AutoSyncTicker` in `MainActivity` polls cloud metadata every 60s while
  the app is open. Guards run cheap-first (background/busy/`sync.auto`-off/
  locked/stamp-equal all skip before any network); default OFF. Each tick
  acts on two dirtiness bits (`decideAutoSync`): server-only movement
  downloads (toast + refresh, never a modal — RAM sessions keep running
  untouched), local-only movement (local YAML hash vs `lastSyncedHash`
  baseline, stamped after every completed up/download/import) uploads
  silently, and both-sides movement sets `syncConflict` and pauses instead
  of overwriting either side. A conflict toasts once ("open Settings >
  Sync"), dots the Settings row, and shows a resolve card atop the sync
  screen (Upload local / Download server, reusing the manual confirm
  dialogs); any successful up/download clears it. Undo/restore deliberately
  do NOT re-baseline, so reverting to an older local copy against a newer
  server surfaces as a conflict rather than silently re-downloading.
- **Secret-field keyboards:** every passphrase/password field uses
  `KeyboardType.Password` (no predictions/autocomplete), not just visual
  masking — secrets never leak into the keyboard dictionary.
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

typing: event-driven pipe (SshInputPipe + PipeInput): the field owns a
  scratch BaseInputConnection that is ALWAYS empty — commits send the fresh
  text straight to the shell and clear, deletes send NxDEL, Done sends CR.
  No mid-line buffer is ever modeled, so recall/prediction bugs are
  structurally impossible (the old recall model + its tests are deleted).
  Soft backspace arrives as key events via sendKeyEvent with the full Termux
  KeyHandler map (DEL/FWD-DEL/ENTER/arrows/ESC/printable/CTRL); hardware keys
  are fielded in onKeyEvent the same way. Extra keys bypass the pipe
  (sendKeySteps -> staged raw chunks); special sequences bypass + consume
  stickies via sendSpecial.
extra keys: user-editable layout (ExtraKeyboard model: ordered send steps
  with preset/text kinds, modifiers, escape codec, legacy-send migration,
  normalize+fallback) rendered by one shared ExtraKeysBar composable in the
  terminal and the editor preview; multi-step macros send staged with a
  settle delay; device-local JSON, never synced
- **Hold-to-repeat extra keys + half width:** single preset
  navigation/editing keys (`REPETITIVE_PRESETS`) auto-repeat while held
  (80ms tick); the rule is structural so user-added keys qualify alike,
  while macros/text/sticky/menu never repeat. Tap-or-hold shares one funnel
  call (a quick tap sends once, a hold ticks with no double-send) and repeat
  ticks skip focus-grab. `WIDTH_HALF` keys weigh 0.5 with a 4-char label
  budget, picked from the width editor.
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
   `isScrollInProgress` only covers post-slop drags). No logcat telemetry:
   the `TvPerf`/`TvScroll`/`ImeDock` debug logs were removed outright
   (re-add a line when debugging, then delete it).
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
  matching, desktop quirk-for-quirk), keepalive interval + countMax as
  `KEEP_ALIVE` (SSH_MSG_IGNORE heartbeats; the provider is fixed on the
  `Config` before `SSHClient()` is built — sshj freezes it into the
  connection at construction), custom algorithms via per-connection `DefaultConfig`.
  `warnOnClose` = per-profile override ?? global `ssh.warnOnClose`
  (default off); the confirm dialog guards live sessions only.

## 6. Keyboard dock ("lompat", not slide)

The activity window does **not** shrink on edge-to-edge devices
(`adjustResize` no longer resizes the window), so the outer Column pads
itself by `dockPx` — a whole-column dock, so the extra-keys bar (a layout
sibling below the grid) rides up with the grid instead of sliding under
the IME:

- Two height signals, imitating the `visualViewport` approach: the claimed
  `WindowInsets.ime` plus the ACTUALLY-visible rect
  (`decorView.getWindowVisibleDisplayFrame`, 15% threshold filters the nav
  bar). When the visible rect is notably smaller than the claim (SwiftKey
  over-claims when its window is only as tall as the keys) it is trusted
  with zero shave; otherwise the IME claim is used. No fixed shave: one
  constant can never fit both an over-claiming and an accurate keyboard.
- Minus the nav bar: MainActivity's Scaffold already pads content by
  safeDrawing (nav included), so a full-IME dock double-counts it and
  leaves a black gap between the extra keys and the keyboard. Target =
  (vis-or-ime) − navigationBars, floored at 0.
- Skip redundant sets (`if (t != dockPx)`), small `delay(10)` in the hot
  path (no fit-spam: refit hits the network via window-change), and a
  500ms re-assert safety net. No logcat telemetry (removed outright).

## 7. UI conventions

- English-only UI strings/comments; app theme (System/Dark/Light) +
  palettes + Follow color scheme (`Theme.schemeToAppColorScheme`: the full
  M3 theme derives from the active non-profile scheme, dark/light
  automatic from luminance; session chrome follows the profile scheme via
  a nested `ProfileChrome`, lists follow the shared scheme).
- All sub-screens share `ScreenHeader` (back arrow + title + trailing 20dp busy spinner when a YAML write is in flight — encrypted vault re-encrypt can take seconds on large configs; the header is sticky outside the scrolling column so the spinner does not jitter content); the terminal
  green = connected, amber = connecting, red = disconnected. The dot always
  asks before disconnecting (it is a 32dp invisible tap target — an instant
  silent kill reads exactly like a dropped session); the power button honors
  `warnOnClose` like desktop — and full-width `user@host:port` below.
  Settings > Window can hide the header (device-only): its options move to
  the ⋮ on the active tab, or a floating ⋮ when tabs are off (draggable to
   any corner, anchor in ConfigDisk).
- Settings toggle parity: desktop `toggle` (`terminalSettingsTab.pug`, `sshSettingsTab.pug`, `sshProfileSettings.pug` Advanced, `configSyncSettingsTab.pug`) → `Switch` pill on the right (`Row(fillMaxWidth.clickable){ Text(weight1f.padding(end=12.dp)) + Switch }`); desktop `checkbox` (ciphers, remember password) → `Checkbox`.
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
  (14 presets + Default + stored-custom-hex extra swatch, wrapping FlowRow
  grid; custom desktop hex shows as an extra swatch), stripe
  on list rows plus a desktop-`.colorbar` bar sealed inside the tab box
  (absent without a stored color, live-resolved like the tab title so
  late-set colors still show). Terminal scheme override
  lives in the Colours tab (Use-global + scheme search) and Settings >
  Color scheme. Options with no mobile
   effect (forwarding, x11/agent, non-direct modes) carry a
   desktop-only note in the editor instead of failing silently.
- Extra-keys rows: `ESC / - HOME UP END PGUP` and
  `TAB CTRL ALT LEFT DOWN RIGHT PGDN`; special keys bypass stickies via
  `sendSpecial`. Font size pref `terminal.fontSp` (8–24sp, default 14,
  Settings > Appearance, device-only).
- Terminal header extras: copy (puts `plainText()` on clipboard) +
  box-mode toggle + `⋮` menu (Disconnect, SFTP, Clear terminal
  [`xterm.clear` parity: grid + scrollback emptied, session alive],
  font ±, extra keys on/off, Settings, Profile list — one item list, five
  anchors).
- Connecting row: live stage text from the connector (crypto / connect /
  password / key i-of-n / shell / login scripts) instead of a spinner, with
  Cancel at the far right aborting the in-flight job (quiet, never a failure).
- Text selection: born ONLY from a committed hold (word) or a triple-tap
  (line) — never from plain drags, and never from a stolen system
  gesture. Two guards: holds born in the back-gesture edge strip (24dp)
  cannot summon, and a system-consumed stream (ACTION_CANCEL claiming a
  lingering gesture) ends as "gone" — never a tap, never a release — so a
  back swipe leaves no phantom selection. Two-stage hold: 300ms ticks
  haptically (release = commit word, move = scroll); ~600ms commits and
  extends the nearest endpoint until release. Summoning is vetoed once
  scrolled content moves (24dp drift backstop for clamped edges).
  Endpoints also move via immediate handle drags (a press on a handle
  locks scroll at down, so no second hold is needed; dragging into the
  edge zone auto-scrolls). Scroll stays on even while selecting (it locks
  only for an armed endpoint drag); output-follow freezes; tap clears.
  Copy/Paste float above the selection in an opaque pill (below when no
  room); both dismiss back to typing (Copy stays silent — no banner).
  Copy is always plaintext (grid chars, no ANSI/HTML — `copyAsHTML` is not
  synced). Paste goes through the desktop funnel (`paste()`: newline fold,
  replace-newlines, single-trailing strip, multiline warn dialog outside
  the alt screen, bracketed `ESC[200~…ESC[201~` wrap when the shell enabled
  `?2004`) and refocuses the keyboard. Keyboard-driven paste (IME commit
  with line breaks/ESC) is routed to the same funnel; plain typing commits
  stay direct so auto-spaces survive. Absolute rows are scroll-stable so
  handles track the text (a history shrink, resize, font change, or
  alt-buffer switch drops the selection).
- **Clipboard parity (desktop Settings > Terminal > Clipboard):** 4 synced
  keys under `terminal.*`, absent = default, delete-on-default (ConfigProxy
  parity). `copyOnSelect`/`copyAsHTML` are intentionally NOT synced.
  | Key | Default |
  |---|---|
  | `bracketedPaste` | `true` |
  | `warnOnMultilinePaste` | `true` |
  | `replaceNewlinesWithSpacesOnPaste` | `false` |
  | `trimWhitespaceOnPaste` | `true` |
  Shell side: `TerminalEmulator` tracks `?2004` (`supportsBracketedPaste`,
  cleared by `resetTerminalModes()` on every fresh shell + full `reset()`),
  `isAlternateScreenActive()` gates the warn dialog. Server-emitted standout
  (e.g. a shell highlighting the bracketed-pasted region) renders faithfully
  like desktop xterm — a white block over pasted text is shell bytes, not an
  app selection bug (app selection is the purple overlay, cleared by tap).
  Alt-screen tracking covers `?1049` only (pre-existing gap: `?1047`-only apps
  are rare and undetected; `?1048` needs no handling — cursor save/restore
  only, never switches buffers).
- **Sync target (YAML-only):** host/token/configID live ONLY in YAML >
  `configSync` (outer shell, readable while locked — desktop parity) and are
  RAM-mirrored after load; no prefs duplicate exists. "Test and save" writes
  the YAML section; the ticker reads it back from disk each poll.
- **Cleartext policy:** `https://` always; `http://` only for local targets
  (loopback, RFC 1918, link-local, .local-style, single-label LAN) enforced
  in code (`RawConfigStore.isSyncHostAllowed`, checked by `TabbySyncApi` on
  every request — network-security-config cannot express CIDR ranges).
- **Compose staleness lesson (phantom-Disconnected):** never branch UI on the
  plain `shell` field — the branch group can keep evaluating a stale null
  forever (green dot + Disconnected + dead Reconnect on a live session;
  logging masks it by reshuffling recomposition timing). Shell presence is
  the observable `hasShell` flow, updated at every assignment site;
  composition branches on the flow, event handlers read the field fresh.

## 8. Testing

`./gradlew :app:testDebugUnitTest` — 362 tests, 0 failures (pure JVM, no device):

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
| `PipeInputTest` | event-driven pipe commit mapping (LF→CR submit flag) |
| `MonoFontCheckTest` | proportional-system-monospace detection → bundled fallback |
| `ProfileFieldsTest` | profile full-set parse, defaults-omitted write, id shape, inline helpers, color/icon round-trip, global warnOnClose, behaviorOnSessionEnd parse + auto-omit |
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
| `ConfigImportTest` | raw YAML file/clipboard import validation (strict rejects) |
| `SftpTransferTest` | download/upload/listDir against MINA SFTP, chunked 5 MB SHA-256, deterministic cancel |
| `SftpTransferManagerTest` | DONE/FAILED/CANCELLED rows, per-item + cancelAll abort, once-only Save-as take, clearFinished cleanup |
| `SshAuthTest` | typed SshAuthFailed + friendly reason (MINA rejects-all server), no-credentials case |
| `SessionKeepAliveTest` | label fallback, auto-retry gate, mirror exactness, notification text builders |
| `SyncHostPolicyTest` | cleartext matrix: https always, public http refused, LAN/loopback/link-local allowed |
| `SyncTargetTest` | sync-target write-back on present/missing/stale sections + dump/load round-trip |
| `AutoSyncDecisionTest` | tick direction matrix (clean/download/upload/conflict) + content-hash stability |
| `PortForwardingTest` | forward validation + Local/Remote traffic proofs vs MINA, bind-conflict abort |
| `SocksProxyTest` | SOCKS defaults/validation + live handshake-through-proxy vs fake SOCKS5 |
| `AuthSelectionTest` | auth selection honored (stage proofs) + typed failover bypass vs MINA |
| `ClipboardParityTest` | 4 clipboard keys (defaults/delete-on-default/prune) + `?2004`/`?1049` tracking + paste funnel (fold/replace/strip/trim) |
| `BannerTextTest` | auth-banner fold (`\n`→`\r\n`) + blank-only collapse + skipBanner first-service-line |
| `ConfigBackupTest` | single-slot YAML backup generations + RAM→disk restore fallback |
| `KeepaliveTest` | custom keepalive values reach the transport + defaults match desktop |
| `TinkKvStoreTest` | typed KV round-trip + single-write batching + corrupt-blob quarantine + cross-instance persist |
| `UsernamePromptTest` | blank-user prompt: trim/empty/cancel-to-error-card/retry gating |
| `GroupEditTest` | group rename (trim/unknown-key keep/no-op) + delete (ungroup members, lift children) + move (reparent/top-level/cycle-guard) |
| `ProfileBlacklistTest` | blacklist hide/show round-trip (idempotent, unknown ids kept) + dump-reload stability |
| `SessionEndBehaviorTest` | destroy matrix: close always, auto only on Ctrl+D / submitted `exit`, keep/reconnect never |
| `TerminalEmulatorTest` | (+ clear) grid + scrollback emptied, cursor home, version bump |

## 9. Background survival (SessionService)

sshj runs as threads inside our own process — no forked children, so
Android 12's phantom-process killer does not apply. The remaining enemy
is plain background-process death (Doze, App Standby, OEM task killers),
countered in four layers:

1. **Foreground-service anchor** (`core/session/SessionService`,
   `specialUse` + subtype property for API 34+/targetSdk 36; sideloaded so
   no Play review). The ViewModel keeps owning every socket/shell — the
   service only mirrors the connected list handed to it via `serviceSync`
   (installed by MainActivity): non-empty = foreground with an exact
   notification, empty = stand down. Count, oldest-first `user@host` lines
   (expanded InboxStyle, capped 5 + remainder), and the Disconnect-all action
   (routes to MainActivity singleTop, which closes every session) are all
   derived per transition, so the notification cannot drift. `START_NOT_STICKY`:
   process death clears sessions anyway, nothing to resume.
2. **Opt-in wake lock** (Settings > SSH, default OFF): partial wake lock
   held only while sessions are connected; device-only pref
   `window.keepAwake`.
3. **Notification permission + battery-opt prompt**: once each, on first
   connect (notification first). POST_NOTIFICATIONS (API 33+) is required
   for the FGS notice to be manageable from settings and for the lost
   notice to show; Allow/Skip persist `window.notifAsked`. The battery
   prompt follows with honest scope (helps Doze, not OEM killers);
   Allow/Never persist `window.batteryOptAsked`, Later re-arms.
4. **Graceful death**: `onTransportDeath` funnels every dead shell through
   `onSessionShellEnded`, gated by the profile's `behaviorOnSessionEnd`
   (desktop `base/connectableTerminalTab` + `sshTab` parity, default `auto`,
   synced to desktop verbatim, Advanced tab > Session section):
   `close` and explicit-`auto` destroy the tab (registry drop + `tabDestroy`
   event navigates its screen back); `reconnect` redials at once (consuming
   the auto-retry one-shot so it can't double-fire); `keep` and
   non-explicit `auto` keep today's failed card + Retry and add the desktop
   "Press any key to reconnect" service line (first keypress reconnects;
   input stays alive while the offer stands — tap-to-focus, extra keys,
   box mode and paste all route through the `reconnectOffer` intercept, so
   the promise is reachable even with the shell dead). Explicit exit = Ctrl+D tail or a
   submitted `exit` (`recentInputs`, desktop-capped last 32 chars).
   Explicit `keep` never self-heals (desktop-exact); `auto` keeps the
   single pre-existing auto-retry (`everConnected` + `!autoRetried` + no
   pending UI, 2s settle, aborts if the user acted) using the last connect
   environment so backgrounded tabs redial too. A backgrounded death also
   posts a tap-to-open "session lost" notice when notifications are allowed.

## 10. Build & diagnostics

- Gradle 9.7.1, AGP 9.4.0, Kotlin 2.4.20, Compose BOM 2026.08.00,
  navigation 2.10.0, OkHttp 5.5.0, SnakeYAML 2.7, tink-android 1.23.0,
  sshj 0.40.0, BC 1.85.x, lifecycle-viewmodel(-ktx) 2.11.0.
- compileSdk 37, targetSdk 36, minSdk 26. Manifest
  `windowSoftInputMode="adjustResize"`, label `izs SSH`.
- `./gradlew :app:assembleDebug` -> `app-debug.apk`; install with
  `adb install -r`, read logs with `adb logcat`, screenshot with
  `adb shell screencap -p`.
- **Zero-warning policy (enforced):** `allWarningsAsErrors = true` —
  `assembleDebug` + `testDebugUnitTest` fail on any warning. Fix the cause
  first (AutoMirrored icons, `PrimaryScrollableTabRow`, `menuAnchor(type)`,
  `LocalClipboard`, lifecycle-compose owner, `autoCorrectEnabled`,
  smart-cast simplifications, `ServiceCompat.startForeground`).
  Dynamic YAML/JSON maps go through `asStringMap`/`asMutableStringMap`
  (`is Map<*, *>` is fully checkable — no cast, no warning, no `@Suppress`).
  `@Suppress` is the LAST resort — never slap it on just to silence the
  compiler. Each suppression must carry a comment stating WHY it is safe and
  WHAT would remove it. Allowed today: none — the ledger is zero.
  Verify with a clean `--rerun-tasks` build: incremental builds do not
  re-emit warnings for unchanged files.
- `CrashLog` (debug builds only) persists the last crash trace; the next
  launch offers the Crash Report screen with copy.

## 11. Capability scope (vs Tabby)

Live feature-by-feature tracking (tiers, effort map, done log) lives outside
this file: <https://ssh.izs.web.id/roadmap> (source:
<https://raw.githubusercontent.com/izzis/izs-assets/main/tabby-parity-roadmap.md>).
What stays here is the on-device capability contract — what connects,
what round-trips untouched, and what never will.
- **Port forwarding:** Local + Remote rules open at connect (desktop
  `addPortForward` parity — Local bind failure aborts the connect, Remote
  rejection warns in-terminal and continues); Dynamic (SOCKS) stays
  desktop-only with a clear error. Forward rules join the transport key so
  different rules never silently share one transport.
- **jumpHost / proxyCommand / HTTP proxy:** saved to YAML via the
  `connectionMode` dropdown (other-mode fields nulled on save, desktop
  priority), but connect shows a not-supported message. SOCKS proxy connects
  on-device (desktop `newSocksProxy` parity, default port 1080). The dropdown
  disables switching INTO still-unsupported modes from the phone ("…
  (desktop only)"); a synced non-direct value stays visible/selected so it
  round-trips untouched.

### Mobile scope: YAML features vs this device

Guarantee first: every YAML value round-trips untouched (unknown keys ride
the raw map) — scope differences below are connect-time only, never silent
stripping. Editor marks non-working options "(desktop only)" instead of
hiding them, so synced values stay manageable from the phone.

Connects on-device: password, publicKey, Auto, username prompt when blank
(desktop `Username for host` parity; typed name is session-local), SOCKS
proxy (default 1080), Local/Remote port forwarding, keepalive interval +
countMax watchdog, auth-banner service line unless skipped, readyTimeout, reuseSession, custom algorithms, login
scripts, per-profile warnOnClose.
`keyboardInteractive` narrows to password + the failover prompt (no KI
transport); typed failover passwords bypass the `auth` selection.

Not yet (implementable, no platform blocker): keyboard-interactive
transport + challenge UI, HTTP CONNECT proxy, jump-host chains
(`connectVia` exists in sshj), Dynamic (device-side SOCKS listener),
`telnet` profile type (plain TCP + the existing emulator).

Desktop-only (no mobile counterpart): `x11` (no X server), `agentForward`
and `auth: agent` (no ssh-agent), `proxyCommand` (no helper binaries like
`ssh -W` on stock Android), hotkeys/shortcuts (no physical keyboard),
`options.input` nuances (input is the native IME pipe here — stored,
applied per-platform).
