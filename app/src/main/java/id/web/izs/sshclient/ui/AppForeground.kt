package id.web.izs.sshclient.ui

/**
 * Process-wide foreground tracking without extra dependencies
 * (lifecycle-process is not on the classpath): MainActivity pumps the
 * started-activity counter; readers (auto-retry, lost notices) only ask
 * [isForeground]. Plain @Volatile ints — callbacks run on Main.
 */
object AppForeground {
    @Volatile private var started = 0

    fun onActivityStarted() {
        started++
    }

    fun onActivityStopped() {
        if (started > 0) started--
    }

    val isForeground: Boolean get() = started > 0
}
