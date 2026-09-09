package id.web.izs.sshclient.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Local storage for v1.
 *
 * - configYaml: raw YAML string identical to the desktop file (outer blob when encrypted).
 * - knownHosts: JSON list (app-owned TOFU keys, kept separate from desktop knownHosts).
 * - sync prefs: host/token/configID/auto/parts + lastRemoteChange.
 *
 * EncryptedSharedPreferences (stable 1.1.0, Tink/AES256-GCM) with a plain
 * SharedPreferences fallback when the Keystore is unavailable (old emulators) —
 * the fallback is reported via [isEncryptedStorage] so the UI can inform the user.
 */
class ConfigDisk(context: Context) {
    private val appContext = context.applicationContext

    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "tabby_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val plain: SharedPreferences by lazy {
        appContext.getSharedPreferences("tabby_plain", Context.MODE_PRIVATE)
    }

    var isEncryptedStorage: Boolean = true
        private set

    private fun prefs(): SharedPreferences = try {
        secure.also { isEncryptedStorage = true }
    } catch (e: Exception) {
        isEncryptedStorage = false
        plain
    }

    fun loadYaml(): String? = prefs().getString(KEY_YAML, null)

    fun saveYaml(yaml: String) {
        prefs().edit().putString(KEY_YAML, yaml).apply()
    }

    fun clearYaml() {
        prefs().edit().remove(KEY_YAML).apply()
    }

    fun loadKnownHostsJson(): String? = prefs().getString(KEY_KNOWN_HOSTS, null)

    fun saveKnownHostsJson(json: String) {
        prefs().edit().putString(KEY_KNOWN_HOSTS, json).apply()
    }

    // ---- sync prefs (token aside, non-sensitive; encrypted too when secure storage is active) ----

    var host: String?
        get() = prefs().getString(KEY_HOST, null)
        set(v) = prefs().edit().putString(KEY_HOST, v).apply()
    var token: String?
        get() = prefs().getString(KEY_TOKEN, null)
        set(v) = prefs().edit().putString(KEY_TOKEN, v).apply()
    var configId: Long
        get() = prefs().getLong(KEY_CONFIG_ID, -1L)
        set(v) = prefs().edit().putLong(KEY_CONFIG_ID, v).apply()
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
     * via A-/A+; persisted like the folder expansion above).
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

    companion object {
        const val KEY_YAML = "tabby-config-yaml"
        const val KEY_KNOWN_HOSTS = "tabby-known-hosts"
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
        const val MODE_NEW_TAB_LIST = "list"
        const val MODE_NEW_TAB_SHEET = "sheet"
        /** Hard ceiling for [maxSessions]: 8 sockets + histories is the most a phone should hold. */
        const val MAX_SESSIONS_HARD_MAX = 8
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
