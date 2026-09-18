package id.web.izs.sshclient.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import id.web.izs.sshclient.BuildConfig
import id.web.izs.sshclient.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * About block: app version + a feedback shortcut that opens the mail app
 * addressed to the feedback email (res/values/strings.xml), a manual
 * update check against GitHub Releases (sideloaded builds get no store
 * updates — manual only, no background polling), plus the app
 * license and third-party attributions (the APK strips META-INF license
 * files, so they live here).
 */
private sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck
    data class Current(val latest: String) : UpdateCheck
    data class Available(val latest: String, val url: String) : UpdateCheck
    data class Failed(val reason: String) : UpdateCheck
}

private fun normTag(t: String): String = t.trim().removePrefix("v").removePrefix("V")

private val updateJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** Numeric-aware compare for release tags (`1.10` > `1.9`); fallback lexical. */
private fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.', '-')
    val pb = b.split('.', '-')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrNull(i) ?: "0"
        val y = pb.getOrNull(i) ?: "0"
        val xn = x.toIntOrNull()
        val yn = y.toIntOrNull()
        val c = if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y)
        if (c != 0) return c
    }
    return 0
}
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val feedbackEmail = stringResource(R.string.feedback_email)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("About", onBack)
        Text("izs SSH", style = MaterialTheme.typography.titleMedium)
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Found a bug or have a suggestion? Send feedback. This opens " +
                "your mail app addressed to $feedbackEmail.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_SENDTO, "mailto:$feedbackEmail".toUri()).apply {
                    putExtra(Intent.EXTRA_SUBJECT, "izs SSH feedback")
                }
                context.startActivity(Intent.createChooser(intent, "Send feedback"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Send feedback")
        }
        Text("Updates", style = MaterialTheme.typography.titleMedium)
        val scope = rememberCoroutineScope()
        var updateCheck by remember { mutableStateOf<UpdateCheck>(UpdateCheck.Idle) }
        val updateClient = remember {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }
        fun doCheck() {
            if (updateCheck is UpdateCheck.Checking) return
            updateCheck = UpdateCheck.Checking
            scope.launch {
                updateCheck = try {
                    val req = Request.Builder()
                        .url("https://api.github.com/repos/izzis/izs-ssh-android/releases/latest")
                        .header("Accept", "application/vnd.github+json")
                        .build()
                    val body = withContext(Dispatchers.IO) {
                        updateClient.newCall(req).execute().use { res ->
                            if (!res.isSuccessful) throw IOException("GitHub API returned ${res.code}")
                            res.body.string()
                        }
                    }
                    val obj = updateJson.parseToJsonElement(body).jsonObject
                    val tag = obj["tag_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: throw IOException("no tag in response")
                    val url = obj["html_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: "https://github.com/izzis/izs-ssh-android/releases"
                    if (compareVersions(normTag(tag), normTag(BuildConfig.VERSION_NAME)) > 0) {
                        UpdateCheck.Available(tag, url)
                    } else {
                        UpdateCheck.Current(tag)
                    }
                } catch (e: IOException) {
                    UpdateCheck.Failed(e.message ?: "network error")
                } catch (e: Exception) {
                    UpdateCheck.Failed(e.message ?: "unexpected error")
                }
            }
        }
        when (val st = updateCheck) {
            is UpdateCheck.Idle -> Text(
                "This build was sideloaded, so updates never arrive on " +
                    "their own. Check GitHub Releases for a newer version.",
                style = MaterialTheme.typography.bodyMedium,
            )
            is UpdateCheck.Checking -> Text(
                "Checking…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is UpdateCheck.Current -> Text(
                "You're up to date (${st.latest}).",
                style = MaterialTheme.typography.bodyMedium,
            )
            is UpdateCheck.Available -> Text(
                "Version ${st.latest} is available " +
                    "(you have ${BuildConfig.VERSION_NAME}).",
                style = MaterialTheme.typography.bodyMedium,
            )
            is UpdateCheck.Failed -> Text(
                "Couldn't check for updates (${st.reason}). Try again later.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val available = updateCheck as? UpdateCheck.Available
        if (available != null) {
            Button(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, available.url.toUri()))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Download update")
            }
            TextButton(onClick = { doCheck() }) {
                Text("Check again")
            }
        } else {
            Button(
                onClick = { doCheck() },
                enabled = updateCheck !is UpdateCheck.Checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (updateCheck is UpdateCheck.Checking) "Checking…" else "Check for updates")
            }
        }
        Text("Source code", style = MaterialTheme.typography.titleMedium)
        Text(
            "izs SSH is open source — you can read the code on GitHub.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        "https://github.com/izzis/izs-ssh-android".toUri(),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("github.com/izzis/izs-ssh-android")
        }
        Text("License", style = MaterialTheme.typography.titleMedium)
        Text(
            "izs SSH is licensed under the MIT License, Copyright (c) 2026 izs. " +
                "See LICENSE in the source repository for the full text.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Unofficial Tabby-compatible client. It reads the config, vault, and sync format " +
                "used by Tabby terminal. No Tabby code is used. Not affiliated with or endorsed " +
                "by the Tabby Developers. " +
                "Tabby is MIT licensed, Copyright (c) 2017 Tabby Developers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Open-source libraries", style = MaterialTheme.typography.titleMedium)
        Text(
            "Apache License 2.0: sshj, OkHttp, SnakeYAML, Jetpack Compose / " +
                "AndroidX, kotlinx.coroutines, kotlinx.serialization-json, SLF4J, " +
                "Tink Android. " +
                "Bouncy Castle (MIT-style license): bcprov-jdk18on, bcpkix-jdk18on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
