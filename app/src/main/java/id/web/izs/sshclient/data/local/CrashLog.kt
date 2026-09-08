package id.web.izs.sshclient.data.local

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Date

/**
 * Last-crash recorder.
 *
 * Installed first in MainActivity.onCreate so even an instant startup crash is
 * captured to internal storage. On the next launch the trace is shown on the
 * Crash Report screen (with copy-to-clipboard) instead of dying silently —
 * this is how we diagnose crashes on phones without adb access.
 *
 * Debug-only: MainActivity installs/reads this behind BuildConfig.DEBUG,
 * so release builds never record or show crash traces.
 */
object CrashLog {
    private const val NAME = "last-crash.log"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(app.filesDir, NAME).writeText(
                    "izs SSH crash report\n" +
                        "date: ${Date()}\n" +
                        "thread: ${thread.name}\n\n" +
                        Log.getStackTraceString(error),
                )
            } catch (_: Exception) {
                // Never break the crash chain from inside the handler.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? {
        val f = File(context.applicationContext.filesDir, NAME)
        return if (f.exists()) f.readText().ifBlank { null } else null
    }

    fun clear(context: Context) {
        try {
            File(context.applicationContext.filesDir, NAME).delete()
        } catch (_: Exception) {
        }
    }
}
