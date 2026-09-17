package id.web.izs.sshclient.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * Local storage for v1.
 *
 * - configYaml: raw YAML string identical to the desktop file (outer blob when encrypted).
 * - knownHosts: JSON list (app-owned TOFU keys, kept separate from desktop knownHosts).
 * - sync prefs: host/token/configID/auto/parts + lastRemoteChange.
 *
 * TinkKvStore (Tink AES256-GCM, Keystore-backed keyset) with a plain
 * SharedPreferences fallback when the Keystore is unavailable (old emulators) —
 * the fallback is reported via [isEncryptedStorage] so the UI can inform the
 * user, and writes of secrets (config YAML, sync token/target, known hosts)
 * are refused outright instead of landing in cleartext. The backend is pinned
 * once per process so a transient Keystore glitch can never split-brain
 * reads and writes across the two stores.
 */
class ConfigDisk(context: Context) {
    private val appContext = context.applicationContext

    private val plain: SharedPreferences by lazy {
        appContext.getSharedPreferences("tabby_plain", Context.MODE_PRIVATE)
    }

    var isEncryptedStorage: Boolean = true
        private set

    /**
     * The Tink init failure when the encrypted backend lost, null when it
     * won (or before first access). Surfaced on the boot-failure screen so
     * a dead Keystore is diagnosable instead of a silent fallback + a
     * misleading "refusing unencrypted" loop. Never a secret — keystore
     * exceptions carry only the platform reason.
     */
    var backendError: Exception? = null
        private set

    /**
     * Backend is pinned ONCE per process: either the encrypted store or the
     * plain fallback — never mixed. Per-call try-secure used to split-brain
     * (a transient Keystore glitch wrote to plain, the next read came back
     * from secure looking empty, and a blank config could then overwrite the
     * cloud). First access decides; [isEncryptedStorage] reports which won.
     */
    // Encrypted backend is Tink directly (security-crypto was deprecated
    // wholesale in 1.1.0 with no drop-in replacement; same AES256-GCM
    // engine it wrapped, Keystore-backed keyset, no API change for callers).
    private val backend: KvBackend by lazy {
        // One-time storage reset (alpha, option B): the pre-Tink encrypted
        // file is deleted, never read — backup via Settings > Config file >
        // Copy before updating (see release notes). Exactly one line.
        appContext.deleteSharedPreferences("tabby_secure")
        try {
            TinkKvStore.create(appContext).also { isEncryptedStorage = true }
        } catch (e: Exception) {
            isEncryptedStorage = false
            backendError = e
            android.util.Log.w("ConfigDisk", "encrypted backend unavailable, plain fallback", e)
            SharedPrefsBackend(plain)
        }
    }

    private fun prefs(): KvBackend = backend

    /**
     * Refuses to persist secrets where they would land in cleartext. Reads
     * stay best-effort (so data can still be viewed/exported); only writes
     * throw, loudly instead of silently downgrading to plain.
     */
    private fun requireEncrypted(what: String) {
        prefs() // force backend init so the flag below is real, not the default
        check(isEncryptedStorage) {
            "Refusing to store $what unencrypted (this device cannot encrypt local storage)"
        }
    }

    fun loadYaml(): String? = prefs().getString(KEY_YAML, null)

    fun saveYaml(yaml: String) {
        requireEncrypted("config (holds the sync token, vault blob and possible plaintext secrets)")
        // Desktop saveConfig parity (tabby/app/lib/config.ts): every save
        // also keeps a .backup copy — the safety net for a corrupt main
        // value. Single-slot rolling: only the previous generation is kept
        // (see rotatedBackup); same-content rewrites leave .bak untouched.
        val bak = rotatedBackup(prefs().getString(KEY_YAML, null), yaml)
        prefs().edit().putString(KEY_YAML, yaml).also { e ->
            if (bak != null) e.putString(KEY_YAML_BAK, bak)
        }.apply()
    }

    /**
     * Desktop config.yaml.backup parity: the previous YAML generation, if any.
     * Offered on the boot-failure screen so a corrupt main value is
     * recoverable without wiping.
     */
    fun loadYamlBackup(): String? = prefs().getString(KEY_YAML_BAK, null)

    fun hasYamlBackup(): Boolean = prefs().contains(KEY_YAML_BAK)

    /**
     * Restore the .backup copy over the main value. The backup itself is
     * kept, so restore is repeatable; the next saveYaml rolls normally from
     * the restored content.
     */
    fun restoreYamlBackup(): String {
        requireEncrypted("config backup restore (holds the sync token, vault blob and possible plaintext secrets)")
        val bak = loadYamlBackup() ?: throw IllegalStateException("No config backup available")
        prefs().edit().putString(KEY_YAML, bak).apply()
        return bak
    }

    fun clearYaml() {
        prefs().edit().remove(KEY_YAML).remove(KEY_YAML_BAK)
            .remove(KEY_YAML_PREIMPORT).remove(KEY_PREIMPORT_STAMP).apply()
    }

    /**
     * Pre-overwrite snapshot (mobile-gap fix, desktop has no equivalent —
     * desktop overwrites local on download with no undo): the YAML + stamp
     * from before the last download/import/autosync tick. Single slot,
     * superseded by the next overwrite; restored by abortPendingImport (via
     * SyncRepository) or explicit undo. The session passphrase is never
     * stored here (RAM only, like rememberedPassphrase itself).
     */
    fun savePreImport(yaml: String, lastChange: String) {
        requireEncrypted("pre-import snapshot (holds the sync token, vault blob and possible plaintext secrets)")
        prefs().edit().putString(KEY_YAML_PREIMPORT, yaml)
            .putString(KEY_PREIMPORT_STAMP, lastChange).apply()
    }

    fun loadPreImportYaml(): String? = prefs().getString(KEY_YAML_PREIMPORT, null)

    fun loadPreImportStamp(): String = prefs().getString(KEY_PREIMPORT_STAMP, "") ?: ""

    fun hasPreImport(): Boolean = prefs().contains(KEY_YAML_PREIMPORT)

    fun clearPreImport() {
        prefs().edit().remove(KEY_YAML_PREIMPORT).remove(KEY_PREIMPORT_STAMP).apply()
    }

    fun loadKnownHostsJson(): String? = prefs().getString(KEY_KNOWN_HOSTS, null)

    fun saveKnownHostsJson(json: String) {
        requireEncrypted("known hosts (reveals your infrastructure)")
        prefs().edit().putString(KEY_KNOWN_HOSTS, json).apply()
    }

    // ---- sync behavior prefs (non-secret: auto/parts/stamp; the target
    // itself — host/token/configID — lives only in YAML > configSync) ----

    var auto: Boolean
        get() = prefs().getBoolean(KEY_AUTO, false)
        set(v) = prefs().edit().putBoolean(KEY_AUTO, v).apply()
    var partsHotkeys: Boolean
        get() = prefs().getBoolean(KEY_PART_HOTKEYS, true)
        set(v) = prefs().edit().putBoolean(KEY_PART_HOTKEYS, v).apply()
    var partsAppearance: Boolean
        get() = prefs().getBoolean(KEY_PART_APPEARANCE, true)
        set(v) = prefs().edit().putBoolean(KEY_PART_APPEARANCE, v).apply()
    var partsVault: Boolean
        get() = prefs().getBoolean(KEY_PART_VAULT, true)
        set(v) = prefs().edit().putBoolean(KEY_PART_VAULT, v).apply()
    var lastRemoteChange: String
        get() = prefs().getString(KEY_LAST_CHANGE, "") ?: ""
        set(v) = prefs().edit().putString(KEY_LAST_CHANGE, v).apply()

    /**
     * Alpha cleanup: the sync target used to live in these prefs keys; it now
     * lives only in YAML > configSync. Old installs may still carry the keys
     * — drop them (no data is adopted). No-op when nothing is left, so the
     * per-boot call from loadLocal costs nothing after the first run.
     * (Deliberately NOT gated on the config `version`: that key is
     * desktop-owned parity and must never carry app-local state.)
     */
    fun dropLegacySyncTarget() {
        val p = prefs()
        if (!p.contains(KEY_HOST) && !p.contains(KEY_TOKEN) && !p.contains(KEY_CONFIG_ID)) return
        p.edit().remove(KEY_HOST).remove(KEY_TOKEN).remove(KEY_CONFIG_ID).apply()
    }

    /**
     * Home folder expansion memory. The home list defaults to all-collapsed,
     * so only the EXPANDED group ids are stored. Note: legacy name-based
     * groups get a fresh random id per load (v5 migration parity), so their
     * expansion persists in-session only; id-based groups persist restarts.
     */
    var expandedGroups: Set<String>
        get() = prefs().getString(KEY_EXPANDED_GROUPS, "")
            ?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        set(v) = prefs().edit().putString(KEY_EXPANDED_GROUPS, v.joinToString(",")).apply()

    /**
     * Terminal font size in sp (Termux-like readable default, user-tunable
     * via A-/A+ and Settings > Appearance; persisted like the folder
     * expansion above). Device-only: screens differ, syncing would resize
     * the desktop on every phone pinch.
     */
    var terminalFontSp: Float
        get() = prefs().getFloat(KEY_TERMINAL_FONT_SP, 14f)
        set(v) = prefs().edit().putFloat(KEY_TERMINAL_FONT_SP, v.coerceIn(8f, 24f)).apply()

    /**
     * Scrollback buffer in lines (Settings > Terminal, xterm-like default).
     * 0 disables history. Applied live; lowering trims immediately.
     */
    var terminalScrollback: Int
        get() = prefs().getInt(KEY_TERMINAL_SCROLLBACK, 5000)
        set(v) = prefs().edit().putInt(KEY_TERMINAL_SCROLLBACK, v.coerceIn(0, 100_000)).apply()

    /**
     * Extra-keys bar layout JSON (Settings > Terminal > Extra keys).
     * Null means the factory default. Parsed defensively by
     * [id.web.izs.sshclient.core.term.loadKeyLayout]; corrupt content falls
     * back to the default instead of breaking the terminal.
     */
    var extraKeysJson: String?
        get() = prefs().getString(KEY_EXTRA_KEYS, null)
        set(v) = prefs().edit().putString(KEY_EXTRA_KEYS, v).apply()

    /**
     * Settle delay between macro steps in ms (Settings > Terminal).
     * Multi-step keys send one packet per step with this pause so each
     * part registers in order. 0 sends back-to-back.
     */
    var macroStepDelayMs: Long
        get() = prefs().getLong(
            KEY_MACRO_STEP_DELAY_MS,
            id.web.izs.sshclient.core.term.DEFAULT_MACRO_STEP_DELAY_MS,
        ).coerceIn(0L, id.web.izs.sshclient.core.term.MAX_MACRO_STEP_DELAY_MS)
        set(v) = prefs().edit().putLong(
            KEY_MACRO_STEP_DELAY_MS,
            v.coerceIn(0L, id.web.izs.sshclient.core.term.MAX_MACRO_STEP_DELAY_MS),
        ).apply()

    /**
     * Max concurrent SSH sessions (multi-session cap). Default 5 for typical
     * phones (5 x 5000 history lines is ~10-15MB + 5 sockets); big-RAM phones
     * can raise to [MAX_SESSIONS_HARD_MAX]. Enforced in SshSessionViewModel.
     */
    var maxSessions: Int
        get() = prefs().getInt(KEY_MAX_SESSIONS, 5).coerceIn(1, MAX_SESSIONS_HARD_MAX)
        set(v) = prefs().edit().putInt(KEY_MAX_SESSIONS, v.coerceIn(1, MAX_SESSIONS_HARD_MAX)).apply()

    /**
     * Recent profile ids, most-recent first (desktop `localStorage['recentProfiles']`
     * parity — local data, never synced to YAML). Stored as a JSON array;
     * corrupt content falls back to empty. Pruned against the profile list
     * at display time (deleted profiles drop out).
     */
    var recentProfileIds: List<String>
        get() = try {
            val arr = org.json.JSONArray(prefs().getString(KEY_RECENT_PROFILES, "[]") ?: "[]")
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
        set(v) = prefs().edit().putString(
            KEY_RECENT_PROFILES,
            org.json.JSONArray(v).toString(),
        ).apply()

    /**
     * App-chrome theme (Settings > Appearance). Device-only: the desktop
     * `appearance.*` keys describe a desktop window manager (frame,
     * vibrancy, dock) with no phone equivalent, so this never syncs.
     * "dark" (default) preserves the previous always-dark behavior.
     */
    var appTheme: String
        get() = prefs().getString(KEY_APP_THEME, THEME_DARK)
            ?.takeIf { it == THEME_SYSTEM || it == THEME_LIGHT } ?: THEME_DARK
        set(v) = prefs().edit().putString(
            KEY_APP_THEME,
            when (v) {
                THEME_SYSTEM -> THEME_SYSTEM
                THEME_LIGHT -> THEME_LIGHT
                else -> THEME_DARK
            },
        ).apply()

    /**
     * App-chrome color palette id (Settings > Appearance > App colors).
     * Device-only like [appTheme] (no desktop equivalent); unknown values
     * resolve to Izs, never fatal.
     */
    var appPalette: String
        get() = prefs().getString(KEY_APP_PALETTE, PALETTE_IZS) ?: PALETTE_IZS
        set(v) = prefs().edit().putString(KEY_APP_PALETTE, v).apply()

    /**
     * Match the app appearance to the active color scheme (Settings >
     * Appearance, Tabby desktop "Follow the color scheme" parity).
     * Device-only, never synced. Defaults to false so existing installs
     * see no sudden visual change (opt-in).
     */
    var followColorScheme: Boolean
        get() = prefs().getBoolean(KEY_FOLLOW_SCHEME, false)
        set(v) = prefs().edit().putBoolean(KEY_FOLLOW_SCHEME, v).apply()

    /**
     * Tab-location source priority (Settings > Window). "follow" (default) =
     * the synced `appearance.tabsLocation` YAML wins; "local" = this device's
     * own [localTabLocation] wins and YAML is ignored for display. Local
     * data, never synced to YAML (recentProfiles parity). The local mode is
     * what makes encrypted configs painless: display no longer depends on
     * the YAML value at all.
     */
    var tabSource: String
        get() = prefs().getString(KEY_TAB_SOURCE, "follow")?.takeIf { it == "local" } ?: "follow"
        set(v) = prefs().edit().putString(KEY_TAB_SOURCE, if (v == "local") "local" else "follow").apply()

    /**
     * This device's own tab location (raw YAML value, "" = Off). Only read
     * when [tabSource] is "local". Anything unparseable resolves to OFF.
     */
    var localTabLocation: String
        get() = prefs().getString(KEY_TAB_LOCATION, "") ?: ""
        set(v) = prefs().edit().putString(KEY_TAB_LOCATION, v).apply()

    /**
     * What the terminal "+" (new tab) button opens. Local-only UX pref,
     * never synced (tabSource parity): "list" navigates back to the full
     * profile list (legacy), "sheet" opens a quick-pick bottom sheet over
     * the terminal (search + recent + profiles).
     */
    var newTabMode: String
        get() = prefs().getString(KEY_NEW_TAB_MODE, MODE_NEW_TAB_LIST)
            ?.takeIf { it == MODE_NEW_TAB_SHEET } ?: MODE_NEW_TAB_LIST
        set(v) = prefs().edit().putString(
            KEY_NEW_TAB_MODE,
            if (v == MODE_NEW_TAB_SHEET) MODE_NEW_TAB_SHEET else MODE_NEW_TAB_LIST,
        ).apply()

    /**
     * Color-scheme source priority (Settings > Color scheme, tabSource
     * parity). "synced" (default) = the synced `terminal.colorScheme` YAML
     * wins; "local" = this device's own [localColorSchemeJson] wins and the
     * synced global is ignored for display. Local data, never synced to
     * YAML. Local mode is instant (plain pref write, no vault decrypt) and
     * works fully offline/locked.
     */
    var colorSchemeSource: String
        get() = prefs().getString(KEY_SCHEME_SOURCE, SOURCE_SYNCED)
            ?.takeIf { it == SOURCE_LOCAL } ?: SOURCE_SYNCED
        set(v) = prefs().edit().putString(
            KEY_SCHEME_SOURCE,
            if (v == SOURCE_LOCAL) SOURCE_LOCAL else SOURCE_SYNCED,
        ).apply()

    /**
     * This device's own color scheme (compact JSON, "" = unset = Izs
     * Default). Only read when [colorSchemeSource] is "local".
     */
    var localColorSchemeJson: String
        get() = prefs().getString(KEY_LOCAL_SCHEME, "") ?: ""
        set(v) = prefs().edit().putString(KEY_LOCAL_SCHEME, v).apply()

    /**
     * Hide the terminal header row (Settings > Window). Device-only app
     * chrome (no desktop equivalent — Tabby desktop always shows its
     * titlebar/tab bar): when on, session options move to the ⋮ button on
     * the active tab (or a floating ⋮ when tabs are off). Default false.
     */
    var hideTerminalHeader: Boolean
        get() = prefs().getBoolean(KEY_HIDE_TERMINAL_HEADER, false)
        set(v) = prefs().edit().putBoolean(KEY_HIDE_TERMINAL_HEADER, v).apply()

    /**
     * Floating ⋮ anchor (hidden header + tabs off): false = top-end (the
     * old header ⋮ spot), true = bottom-end above the extra keys. Set by
     * dragging the button; device-only like [hideTerminalHeader].
     */
    var fabAtBottom: Boolean
        get() = prefs().getBoolean(KEY_FAB_AT_BOTTOM, false)
        set(v) = prefs().edit().putBoolean(KEY_FAB_AT_BOTTOM, v).apply()

    /** Floating ⋮ anchor, horizontal half: true = left side. */
    var fabAtLeft: Boolean
        get() = prefs().getBoolean(KEY_FAB_AT_LEFT, false)
        set(v) = prefs().edit().putBoolean(KEY_FAB_AT_LEFT, v).apply()

    /**
     * Keep the CPU awake while sessions are connected (Settings > SSH).
     * Default OFF: a partial wake lock drains the battery, so only users
     * running long jobs (htop, rsync) opt in. Read by SessionService
     * alongside the connected list. Device-only (no desktop equivalent,
     * never synced).
     */
    var keepAwake: Boolean
        get() = prefs().getBoolean(KEY_KEEP_AWAKE, false)
        set(v) = prefs().edit().putBoolean(KEY_KEEP_AWAKE, v).apply()

    /**
     * Battery-optimization exemption was offered once (Settings > Window
     * flow is not involved — the prompt appears on first connect). Either
     * answer (allow / never) sets this; "later" leaves it false so the
     * prompt can reappear next connect. Device-only, never synced.
     */
    var batteryOptAsked: Boolean
        get() = prefs().getBoolean(KEY_BATTERY_OPT_ASKED, false)
        set(v) = prefs().edit().putBoolean(KEY_BATTERY_OPT_ASKED, v).apply()

    /**
     * Notification permission was offered once (first connect, API 33+).
     * Allow/Skip both set this; the system dialog's own "don't ask"
     * handling applies after two denials. Device-only, never synced.
     */
    var notifAsked: Boolean
        get() = prefs().getBoolean(KEY_NOTIF_ASKED, false)
        set(v) = prefs().edit().putBoolean(KEY_NOTIF_ASKED, v).apply()

    companion object {
        const val KEY_YAML = "tabby-config-yaml"
        /** Desktop config.yaml.backup parity: previous YAML generation. */
        const val KEY_YAML_BAK = "tabby-config-yaml.bak"
        /** Pre-overwrite snapshot slot (see savePreImport). */
        const val KEY_YAML_PREIMPORT = "tabby-config-yaml.preimport"
        const val KEY_PREIMPORT_STAMP = "sync.preImportLastRemoteChange"
        const val KEY_KNOWN_HOSTS = "tabby-known-hosts"
        // Pre-YAML-only leftovers, dropped once by dropLegacySyncTarget.
        const val KEY_HOST = "sync.host"
        const val KEY_TOKEN = "sync.token"
        const val KEY_CONFIG_ID = "sync.configID"
        const val KEY_AUTO = "sync.auto"
        const val KEY_PART_HOTKEYS = "sync.parts.hotkeys"
        const val KEY_PART_APPEARANCE = "sync.parts.appearance"
        const val KEY_PART_VAULT = "sync.parts.vault"
        const val KEY_LAST_CHANGE = "sync.lastRemoteChange"
        const val KEY_EXPANDED_GROUPS = "home.expandedGroups"
        const val KEY_TERMINAL_FONT_SP = "terminal.fontSp"
        const val KEY_TERMINAL_SCROLLBACK = "terminal.scrollback"
        const val KEY_EXTRA_KEYS = "terminal.extraKeys"
        const val KEY_MACRO_STEP_DELAY_MS = "terminal.macroStepDelayMs"
        const val KEY_MAX_SESSIONS = "terminal.maxSessions"
        const val KEY_RECENT_PROFILES = "home.recentProfiles"
        const val KEY_TAB_SOURCE = "window.tabSource"
        const val KEY_TAB_LOCATION = "window.tabLocation"
        const val KEY_NEW_TAB_MODE = "window.newTabMode"
        const val KEY_HIDE_TERMINAL_HEADER = "window.hideTerminalHeader"
        const val KEY_FAB_AT_BOTTOM = "window.fabAtBottom"
        const val KEY_FAB_AT_LEFT = "window.fabAtLeft"
        const val KEY_KEEP_AWAKE = "window.keepAwake"
        const val KEY_BATTERY_OPT_ASKED = "window.batteryOptAsked"
        const val KEY_NOTIF_ASKED = "window.notifAsked"
        const val MODE_NEW_TAB_LIST = "list"
        const val MODE_NEW_TAB_SHEET = "sheet"
        const val KEY_SCHEME_SOURCE = "terminal.schemeSource"
        const val KEY_LOCAL_SCHEME = "terminal.localScheme"
        const val SOURCE_SYNCED = "synced"
        const val SOURCE_LOCAL = "local"
        const val KEY_APP_THEME = "appearance.appTheme"
        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val KEY_APP_PALETTE = "appearance.appPalette"
        const val PALETTE_IZS = "izs"
        const val KEY_FOLLOW_SCHEME = "appearance.followColorScheme"
        /** Hard ceiling for [maxSessions]: 10 sockets + histories is the most a phone should hold. */
        const val MAX_SESSIONS_HARD_MAX = 10

        /**
         * Single-slot rolling backup decision (pure, unit-tested): returns
         * the value to store as .bak, or null to leave the existing backup
         * untouched. Only a genuinely older, non-blank generation is
         * preserved — first seed (no current value) and no-op rewrites never
         * clobber a good backup.
         */
        fun rotatedBackup(currentMain: String?, incoming: String): String? =
            if (!currentMain.isNullOrBlank() && currentMain != incoming) currentMain else null
    }
}

/**
 * Desktop `launchProfile` recents parity (profiles.service.ts): dedup, unshift
 * to front, cap at [max]. `max <= 0` clears (desktop "Set to 0 to disable").
 * Pure for unit tests; persistence stays in [ConfigDisk.recentProfileIds].
 */
fun recordRecent(current: List<String>, id: String, max: Int): List<String> {
    if (max <= 0) return emptyList()
    return (listOf(id) + current.filter { it != id }).take(max)
}
