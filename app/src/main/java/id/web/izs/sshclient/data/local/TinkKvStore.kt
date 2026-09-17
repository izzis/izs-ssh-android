package id.web.izs.sshclient.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.crypto.tink.Aead
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Minimal key-value backend contract covering exactly what [ConfigDisk]
 * uses (get/put for String/Boolean/Int/Long/Float, contains, remove).
 * Implemented by [TinkKvStore] (encrypted) and [SharedPrefsBackend]
 * (plain fallback) so the Keystore-outage fallback keeps working with
 * zero call-site branching.
 */
internal interface KvBackend {
    fun getString(key: String, def: String?): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, def: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, def: Int): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, def: Long): Long
    fun putLong(key: String, value: Long)
    fun getFloat(key: String, def: Float): Float
    fun putFloat(key: String, value: Float)
    fun contains(key: String): Boolean
    fun remove(vararg keys: String)
    fun edit(): KvEditor
}

/**
 * Batch writer mirroring the SharedPreferences.Editor calls [ConfigDisk]
 * already makes (put/remove chains + apply), so the backend swap needs
 * zero call-site changes. Tink batches into ONE sealed write per apply.
 */
internal interface KvEditor {
    fun putString(key: String, value: String?): KvEditor
    fun putBoolean(key: String, value: Boolean): KvEditor
    fun putInt(key: String, value: Int): KvEditor
    fun putLong(key: String, value: Long): KvEditor
    fun putFloat(key: String, value: Float): KvEditor
    fun remove(key: String): KvEditor
    fun apply()
}

/** [KvBackend] over a plain [SharedPreferences] (non-secret fallback). */
internal class SharedPrefsBackend(private val prefs: SharedPreferences) : KvBackend {
    override fun getString(key: String, def: String?): String? = prefs.getString(key, def)
    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }
    override fun getBoolean(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
    override fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }
    override fun getLong(key: String, def: Long): Long = prefs.getLong(key, def)
    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }
    override fun getFloat(key: String, def: Float): Float = prefs.getFloat(key, def)
    override fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }
    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun remove(vararg keys: String) {
        prefs.edit().also { e -> keys.forEach { e.remove(it) } }.apply()
    }
    override fun edit(): KvEditor = PrefsEditor(prefs.edit())

    private class PrefsEditor(private val e: SharedPreferences.Editor) : KvEditor {
        override fun putString(key: String, value: String?): KvEditor = apply { e.putString(key, value) }
        override fun putBoolean(key: String, value: Boolean): KvEditor = apply { e.putBoolean(key, value) }
        override fun putInt(key: String, value: Int): KvEditor = apply { e.putInt(key, value) }
        override fun putLong(key: String, value: Long): KvEditor = apply { e.putLong(key, value) }
        override fun putFloat(key: String, value: Float): KvEditor = apply { e.putFloat(key, value) }
        override fun remove(key: String): KvEditor = apply { e.remove(key) }
        override fun apply() {
            e.apply()
        }
    }
}

/**
 * Encrypted [KvBackend]: the whole map is serialized to one JSON blob and
 * sealed with Tink AES256-GCM (Keystore-backed keyset). Replaces
 * EncryptedSharedPreferences (deprecated wholesale in security-crypto
 * 1.1.0 with no drop-in successor) — same Tink engine it wrapped, used
 * directly. Security profile unchanged: AES256-GCM values, key in the
 * Android Keystore, nothing secret in plaintext.
 *
 * Values carry a type tag so getInt/getLong/getFloat round-trip exactly
 * (plain JSON numbers would not). JSON goes through kotlinx.serialization
 * (real JVM implementation — org.json is an Android stub in unit tests).
 * Writes are atomic (tmp + rename); a corrupt blob is quarantined to
 * `<name>.corrupt.<millis>` and the store starts empty instead of
 * crashing. All methods are synchronized — SharedPreferences-parity for
 * the multi-threaded callers.
 *
 * The [Aead] is constructor-injected so JVM unit tests can seal with a
 * generated key (no Keystore/Robolectric); use [create] on device.
 */
internal class TinkKvStore(private val aead: Aead, private val file: File) : KvBackend {
    private var cache: MutableMap<String, String> = load()

    @Synchronized
    override fun getString(key: String, def: String?): String? {
        val env = parseEnvelope(cache[key]) ?: return def
        return if (env.type == TYPE_STRING) env.value.contentOrNull ?: def else def
    }

    @Synchronized
    override fun putString(key: String, value: String?) {
        if (value == null) remove(key) else { cache[key] = envelope(TYPE_STRING, JsonPrimitive(value)); persist() }
    }

    @Synchronized
    override fun getBoolean(key: String, def: Boolean): Boolean {
        val env = parseEnvelope(cache[key]) ?: return def
        return if (env.type == TYPE_BOOLEAN) env.value.booleanOrNull ?: def else def
    }

    @Synchronized
    override fun putBoolean(key: String, value: Boolean) {
        cache[key] = envelope(TYPE_BOOLEAN, JsonPrimitive(value)); persist()
    }

    @Synchronized
    override fun getInt(key: String, def: Int): Int {
        val env = parseEnvelope(cache[key]) ?: return def
        return if (env.type == TYPE_INT) env.value.intOrNull ?: def else def
    }

    @Synchronized
    override fun putInt(key: String, value: Int) {
        cache[key] = envelope(TYPE_INT, JsonPrimitive(value)); persist()
    }

    @Synchronized
    override fun getLong(key: String, def: Long): Long {
        val env = parseEnvelope(cache[key]) ?: return def
        return if (env.type == TYPE_LONG) env.value.longOrNull ?: def else def
    }

    @Synchronized
    override fun putLong(key: String, value: Long) {
        cache[key] = envelope(TYPE_LONG, JsonPrimitive(value)); persist()
    }

    @Synchronized
    override fun getFloat(key: String, def: Float): Float {
        val env = parseEnvelope(cache[key]) ?: return def
        return if (env.type == TYPE_FLOAT) env.value.doubleOrNull?.toFloat() ?: def else def
    }

    @Synchronized
    override fun putFloat(key: String, value: Float) {
        cache[key] = envelope(TYPE_FLOAT, JsonPrimitive(value.toDouble())); persist()
    }

    @Synchronized
    override fun contains(key: String): Boolean = cache.containsKey(key)

    @Synchronized
    override fun remove(vararg keys: String) {
        var changed = false
        keys.forEach { changed = cache.remove(it) != null || changed }
        if (changed) persist()
    }

    override fun edit(): KvEditor = TinkEditor()

    private inner class TinkEditor : KvEditor {
        private val puts = LinkedHashMap<String, String>()
        private val removals = LinkedHashSet<String>()
        override fun putString(key: String, value: String?): KvEditor = apply {
            if (value == null) removals.add(key) else puts[key] = envelope(TYPE_STRING, JsonPrimitive(value))
        }
        override fun putBoolean(key: String, value: Boolean): KvEditor = apply {
            puts[key] = envelope(TYPE_BOOLEAN, JsonPrimitive(value))
        }
        override fun putInt(key: String, value: Int): KvEditor = apply {
            puts[key] = envelope(TYPE_INT, JsonPrimitive(value))
        }
        override fun putLong(key: String, value: Long): KvEditor = apply {
            puts[key] = envelope(TYPE_LONG, JsonPrimitive(value))
        }
        override fun putFloat(key: String, value: Float): KvEditor = apply {
            puts[key] = envelope(TYPE_FLOAT, JsonPrimitive(value.toDouble()))
        }
        override fun remove(key: String): KvEditor = apply { removals.add(key) }
        override fun apply() {
            // The editor itself is single-use by contract (same as Editor);
            // the store call keeps cache+persist atomic.
            this@TinkKvStore.applyBatch(puts, removals)
        }
    }

    @Synchronized
    private fun applyBatch(puts: Map<String, String>, removals: Set<String>) {
        removals.forEach { cache.remove(it) }
        cache.putAll(puts)
        persist()
    }

    private data class Envelope(val type: String, val value: JsonPrimitive)

    private fun envelope(type: String, value: JsonPrimitive): String =
        JsonObject(mapOf(KEY_TYPE to JsonPrimitive(type), KEY_VALUE to value)).toString()

    private fun parseEnvelope(raw: String?): Envelope? = try {
        val obj = (kotlinx.serialization.json.Json.parseToJsonElement(raw ?: return null) as? JsonObject)
            ?: return null
        val type = (obj[KEY_TYPE] as? JsonPrimitive)?.contentOrNull ?: return null
        val value = obj[KEY_VALUE] as? JsonPrimitive ?: return null
        Envelope(type, value)
    } catch (_: Exception) {
        null
    }

    private fun load(): MutableMap<String, String> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val blob = kotlinx.serialization.json.Json.parseToJsonElement(
                String(aead.decrypt(file.readBytes(), ASSOCIATED_DATA), Charsets.UTF_8),
            ).jsonObject
            LinkedHashMap<String, String>().also { m ->
                blob.keys.forEach { k -> m[k] = blob.getValue(k).toString() }
            }
        } catch (_: Exception) {
            // Never crash on a corrupt blob: quarantine for forensics, start empty.
            try {
                file.renameTo(File(file.parent, "${file.name}.corrupt.${System.currentTimeMillis()}"))
            } catch (_: Exception) {
            }
            mutableMapOf()
        }
    }

    private fun persist() {
        val blob = JsonObject(cache.mapValues { (_, v) ->
            kotlinx.serialization.json.Json.parseToJsonElement(v)
        }).toString()
        val bytes = aead.encrypt(blob.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
        file.parentFile?.mkdirs()
        val tmp = File(file.parent, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
        }
    }

    companion object {
        private const val KEY_TYPE = "t"
        private const val KEY_VALUE = "v"
        private const val TYPE_STRING = "s"
        private const val TYPE_BOOLEAN = "b"
        private const val TYPE_INT = "i"
        private const val TYPE_LONG = "l"
        private const val TYPE_FLOAT = "f"
        private const val STORE_FILE = "tabby_store.enc"
        private const val KEYSET_PREFS = "tink_keyset"
        private const val KEYSET_KEY = "tink_keyset_key"
        private const val MASTER_KEY_URI = "android-keystore://izs_tink_master"
        private val ASSOCIATED_DATA = ByteArray(0)

        /** Device backend: Keystore-backed AES256-GCM keyset, file in filesDir. */
        fun create(context: Context): TinkKvStore {
            // Mandatory once per process: registers the AES-GCM key manager
            // + Aead wrapper. security-crypto did this internally; using
            // Tink directly the app owns it (missing = "No key manager
            // found" on every device, keystore-independent).
            AeadConfig.register()
            val appContext = context.applicationContext
            val handle = AndroidKeysetManager.Builder()
                .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                .withSharedPref(appContext, KEYSET_PREFS, KEYSET_KEY)
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
            return TinkKvStore(
                handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java),
                File(appContext.filesDir, STORE_FILE),
            )
        }
    }
}
