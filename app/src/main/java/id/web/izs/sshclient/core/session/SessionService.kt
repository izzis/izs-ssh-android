package id.web.izs.sshclient.core.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import id.web.izs.sshclient.MainActivity
import id.web.izs.sshclient.data.local.ConfigDisk

/**
 * Foreground-service anchor that keeps SSH sessions alive in the background.
 *
 * Why a service, not the ViewModel alone: sshj runs as threads inside our
 * own process (no forked children, so Android 12's phantom-process killer
 * does not apply to us), but a plain background process is still a prime
 * candidate for Doze / App Standby / OEM task killers. A foreground service
 * with an ongoing notification raises the process priority so sessions
 * survive being backgrounded — the same approach Termux uses
 * (TermuxService), minus the forked-shell part we never had.
 *
 * Deliberately NOT a session owner: [id.web.izs.sshclient.ui.SshSessionViewModel]
 * keeps owning every socket/shell (no 600-line rewrite, no behavior change).
 * This service only mirrors the connected-session list it is handed via
 * [refresh]: non-empty = go/stay foreground with an accurate notification,
 * empty = stand down. The count and per-host lines are derived from the live
 * registry at every transition, so the notification can never drift.
 *
 * Notification contract (user-confirmed):
 * - collapsed: "N SSH session(s) active" + host list summary;
 * - expanded (the arrow): one line per connected host/profile;
 * - expanded actions: "Disconnect all" (routes to MainActivity, singleTop,
 *   which closes every session — the registry then reports empty and the
 *   service stops itself).
 */
class SessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    // Cached per process: building ConfigDisk pays EncryptedSharedPreferences
    // + Keystore init, which must not run on Main at every status transition.
    private var disk: ConfigDisk? = null

    private fun disk(): ConfigDisk = disk ?: ConfigDisk(this).also { disk = it }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> {
                val labels = intent.getStringArrayListExtra(EXTRA_LABELS).orEmpty()
                applyState(labels)
            }
            // Reserved: notification actions route through MainActivity
            // (singleTop) instead — the service never touches the registry.
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun applyState(labels: List<String>) {
        if (labels.isEmpty()) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        ensureChannel(this)
        val notification = buildSessionsNotification(this, labels)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID_SESSIONS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID_SESSIONS, notification)
        }
        applyWakeLock(true)
    }

    private fun stopIfIdle() {
        // Defensive: a stray start with no payload must never park a
        // permanent notification.
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun applyWakeLock(active: Boolean) {
        val want = active && disk().keepAwake
        val held = wakeLock?.isHeld == true
        if (want && !held) {
            val pm = getSystemService(PowerManager::class.java) ?: return
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire()
            wakeLock = lock
        } else if (!want && held) {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_REFRESH = "id.web.izs.sshclient.session.REFRESH"
        const val EXTRA_LABELS = "labels"
        const val CHANNEL_ID = "ssh_sessions"
        const val NOTIF_ID_SESSIONS = 1001
        const val NOTIF_ID_LOST = 1002
        const val WAKELOCK_TAG = "izs-ssh:session"

        /**
         * Failure reporter for [refresh]: the catch swallows by design (a
         * background kill must never crash the caller), but a missing
         * notification with sessions connected must be VISIBLE — MainActivity
         * installs a Toast here. Runs on the caller's thread.
         */
        var onError: ((String) -> Unit)? = null

        /**
         * Mirror [labels] (one `user@host` per CONNECTED session) into the
         * service. Empty stops it. Safe to call from any thread; start
         * failures (e.g. FGS-start from background on API 31+) degrade to
         * "sessions run unprotected" instead of crashing the caller.
         */
        fun refresh(context: Context, labels: List<String>) {
            val intent = Intent(context, SessionService::class.java).apply {
                action = ACTION_REFRESH
                putStringArrayListExtra(EXTRA_LABELS, ArrayList(labels))
            }
            try {
                if (labels.isEmpty()) {
                    context.stopService(intent)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Swallowed by design (a background kill must never crash
                // the caller), but loud in debug AND via onError — a missing
                // notification with sessions connected always lands here first.
                if (id.web.izs.sshclient.BuildConfig.DEBUG) {
                    android.util.Log.w("SessionService", "refresh failed", e)
                }
                onError?.invoke(e.message ?: e.toString())
            }
        }

        /** Collapsed title: exact count, singular/plural handled. Pure for tests. */
        fun sessionsTitle(count: Int): String = sessionsTitleText(count)

        /**
         * Collapsed summary line: up to two labels, then a remainder.
         * Pure for tests.
         */
        fun sessionsSummary(labels: List<String>): String = sessionsSummaryText(labels)

        /**
         * Expanded lines: one per session, capped with a remainder line so a
         * full house (8 max) still fits the shade. Pure for tests.
         */
        fun expandedLines(labels: List<String>): List<String> = expandedLinesText(labels)

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "SSH sessions",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Keeps SSH sessions alive in the background" },
            )
        }

        fun buildSessionsNotification(context: Context, labels: List<String>): Notification {
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val disconnectAll = PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_DISCONNECT_ALL
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val inbox = NotificationCompat.InboxStyle()
                .setSummaryText(sessionsTitle(labels.size))
            for (line in expandedLines(labels)) inbox.addLine(line)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(id.web.izs.sshclient.R.drawable.ic_stat_terminal)
                .setContentTitle(sessionsTitle(labels.size))
                .setContentText(sessionsSummary(labels))
                .setStyle(inbox)
                .setContentIntent(openApp)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Disconnect all",
                    disconnectAll,
                )
                .setOngoing(true)
                .setShowWhen(false)
                .build()
        }

        /**
         * One-shot "session lost" notice (process/network kill while the app
         * was backgrounded). Auto-cancels on tap; intentionally NOT cleared
         * on recovery — tapping it just opens the app, which is harmless.
         * The caller must check POST_NOTIFICATIONS first (API 33+); without
         * it this is a silent no-op and the in-app error card remains the
         * fallback.
         */
        fun notifyLost(context: Context, label: String) {
            ensureChannel(context)
            val openApp = PendingIntent.getActivity(
                context,
                2,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("SSH session lost")
                .setContentText("$label. Trying to reconnect. Tap to open.")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            try {
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIF_ID_LOST, notification)
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * File-level pure builders for the notification text (same file keeps the
 * contract next to its renderer; top-level keeps them loadable in JVM unit
 * tests without the Android framework).
 */
private const val MAX_EXPANDED_LINES_TOP = 5

/** Collapsed title: exact count, singular/plural handled. */
fun sessionsTitleText(count: Int): String =
    if (count == 1) "1 SSH session active" else "$count SSH sessions active"

/** Collapsed summary line: up to two labels, then a remainder. */
fun sessionsSummaryText(labels: List<String>): String = when {
    labels.isEmpty() -> ""
    labels.size <= 2 -> labels.joinToString(", ")
    else -> labels.take(2).joinToString(", ") + " +${labels.size - 2} more"
}

/** Expanded lines: one per session, capped with a remainder line. */
fun expandedLinesText(labels: List<String>): List<String> {
    val lines = labels.take(MAX_EXPANDED_LINES_TOP).toList()
    return if (labels.size > MAX_EXPANDED_LINES_TOP) {
        lines + "+${labels.size - MAX_EXPANDED_LINES_TOP} more"
    } else {
        lines
    }
}
