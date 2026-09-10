package id.web.izs.sshclient.core.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization exemption helpers (background survival).
 *
 * The direct ask ([Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS])
 * is refused by some OEMs / Android flavors (no handler, or a
 * SecurityException): swallowing that leaves a dead button, so [open]
 * walks a fallback chain — direct ask, then the system exemption list,
 * then our app-details page — and reports whether anything opened. The
 * TerminalScreen dialog and the SSH-settings status row both funnel here.
 */
fun isBatteryExempt(context: Context): Boolean = try {
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true
} catch (_: Exception) {
    false
}

fun openBatterySettings(context: Context): Boolean {
    val pkg = "package:${context.packageName}"
    val intents = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse(pkg)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse(pkg)),
    )
    for (intent in intents) {
        try {
            // Safe from both Activity and app contexts (settings opens
            // on top either way); required when this runs off-Activity.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        } catch (_: Exception) {
        }
    }
    return false
}
