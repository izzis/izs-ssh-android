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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import id.web.izs.sshclient.BuildConfig
import id.web.izs.sshclient.R

/**
 * About block: app version + a feedback shortcut that opens the mail app
 * addressed to the feedback email (res/values/strings.xml), plus the app
 * license and third-party attributions (the APK strips META-INF license
 * files, so they live here).
 */
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
                "AndroidX Security Crypto. " +
                "Bouncy Castle (MIT-style license): bcprov-jdk18on, bcpkix-jdk18on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
