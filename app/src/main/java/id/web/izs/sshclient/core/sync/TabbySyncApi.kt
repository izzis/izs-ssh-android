package id.web.izs.sshclient.core.sync

import id.web.izs.sshclient.core.config.RemoteConfigMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Parity with tabby-settings/src/services/configSync.service.ts:161-198.
 *
 * ONE deliberate difference: the desktop rejects http:// (throws unless https).
 * Here http:// is ALLOWED for self-hosted/LAN use per user request,
 * with a warning in the UI (a MITM'd YAML payload can RCE via command/env).
 *
 * Body compatibility: official flat {name} / {content, last_used_with_version} first,
 * {data:{...}} fallback for older server variants. Empty (204) responses are null-safe.
 */
class TabbySyncApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun getUser(host: String, token: String): String {
        val res = get(host, token, "/api/1/user")
        return res
    }

    fun getConfigs(host: String, token: String): List<RemoteConfigMeta> {
        val body = get(host, token, "/api/1/configs")
        if (body.isBlank()) return emptyList()
        val el = json.parseToJsonElement(body)
        val arr = if (el is kotlinx.serialization.json.JsonArray) el else return emptyList()
        return arr.mapNotNull { e ->
            try {
                val o = e.jsonObject
                RemoteConfigMeta(
                    id = o["id"]!!.jsonPrimitive.longOrNull ?: o["id"]!!.jsonPrimitive.intOrNull!!.toLong(),
                    name = o["name"]?.jsonPrimitive?.content ?: "",
                    content = o["content"]?.jsonPrimitive?.content ?: "",
                    lastUsedWithVersion = o["last_used_with_version"]?.jsonPrimitive?.content,
                    createdAt = o["created_at"]?.jsonPrimitive?.content ?: "",
                    modifiedAt = o["modified_at"]?.jsonPrimitive?.content ?: "",
                )
            } catch (_: Exception) { null }
        }
    }

    fun getConfig(host: String, token: String, id: Long): RemoteConfigMeta {
        val body = get(host, token, "/api/1/configs/$id")
        require(body.isNotBlank()) { "Empty response from sync server" }
        val o = json.parseToJsonElement(body).jsonObject
        return RemoteConfigMeta(
            id = o["id"]!!.jsonPrimitive.longOrNull ?: o["id"]!!.jsonPrimitive.intOrNull!!.toLong(),
            name = o["name"]?.jsonPrimitive?.content ?: "",
            content = o["content"]?.jsonPrimitive?.content ?: "",
            lastUsedWithVersion = o["last_used_with_version"]?.jsonPrimitive?.content,
            createdAt = o["created_at"]?.jsonPrimitive?.content ?: "",
            modifiedAt = o["modified_at"]?.jsonPrimitive?.content ?: "",
        )
    }

    /** Try the official body first, then the {data:{...}} fallback. Returns the created meta. */
    fun createConfig(host: String, token: String, name: String): RemoteConfigMeta {
        val bodies = listOf(
            """{"name":${quote(name)}}""",
            """{"data":{"name":${quote(name)}}}""",
        )
        var lastErr = ""
        for (b in bodies) {
            val (code, text) = post(host, token, "/api/1/configs", b)
            if (code in 200..299) {
                val created = tryParseMeta(text, name) ?: fallbackFindByName(host, token, name)
                if (created != null) return created
                return RemoteConfigMeta(id = -1, name = name)
            }
            lastErr = "$code $text"
        }
        throw IllegalStateException("Create failed $lastErr")
    }

    /** PATCH content; flat first, then {data:{...}}. */
    fun updateConfig(host: String, token: String, id: Long, content: String, appVersion: String) {
        val bodies = listOf(
            """{"content":${quote(content)},"last_used_with_version":${quote(appVersion)}}""",
            """{"data":{"content":${quote(content)},"last_used_with_version":${quote(appVersion)}}}""",
        )
        var lastErr = ""
        for (b in bodies) {
            val (code, text) = patch(host, token, "/api/1/configs/$id", b)
            if (code in 200..299) return
            lastErr = "$code $text"
        }
        throw IllegalStateException("Upload failed $lastErr")
    }

    fun deleteConfig(host: String, token: String, id: Long) {
        val req = Request.Builder()
            .url("$host/api/1/configs/$id")
            .delete()
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Delete failed ${r.code} ${r.message}")
        }
    }

    // ---- low level ----

    private fun get(host: String, token: String, path: String): String {
        val req = Request.Builder()
            .url("$host$path")
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IllegalStateException("$path failed: ${r.code} $text")
            return text
        }
    }

    private fun post(host: String, token: String, path: String, body: String): Pair<Int, String> {
        val req = Request.Builder()
            .url("$host$path")
            .post(body.toRequestBody(JSON))
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(req).execute().use { r ->
            return r.code to (r.body?.string() ?: "")
        }
    }

    private fun patch(host: String, token: String, path: String, body: String): Pair<Int, String> {
        val req = Request.Builder()
            .url("$host$path")
            .patch(body.toRequestBody(JSON))
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(req).execute().use { r ->
            return r.code to (r.body?.string() ?: "")
        }
    }

    private fun tryParseMeta(text: String, fallbackName: String): RemoteConfigMeta? {
        if (text.isBlank()) return null
        return try {
            val o = json.parseToJsonElement(text).jsonObject
            val id = o["id"]?.jsonPrimitive?.longOrNull
                ?: o["id"]?.jsonPrimitive?.intOrNull?.toLong() ?: return null
            RemoteConfigMeta(
                id = id,
                name = o["name"]?.jsonPrimitive?.content ?: fallbackName,
                content = o["content"]?.jsonPrimitive?.content ?: "",
                modifiedAt = o["modified_at"]?.jsonPrimitive?.content ?: "",
                createdAt = o["created_at"]?.jsonPrimitive?.content ?: "",
            )
        } catch (_: Exception) { null }
    }

    private fun fallbackFindByName(host: String, token: String, name: String): RemoteConfigMeta? {
        return try {
            getConfigs(host, token).find { it.name == name }
        } catch (_: Exception) { null }
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }
}
