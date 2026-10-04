package com.example.frogreader.ui.reader

/** Foreground reading time, measured with a monotonic clock and drained exactly once. */
internal class ReaderReadingSession(private val nowMillis: () -> Long) {
    private var startedAt: Long? = null
    private var pendingMillis = 0L
    private var totalActiveMillis = 0L

    /** A brief accidental open must not replace the profile's last reading. */
    val qualifiesAsReading: Boolean get() = totalActiveMillis >= 10_000L

    fun setRunning(running: Boolean) {
        if (running) {
            if (startedAt == null) startedAt = nowMillis()
        } else {
            startedAt?.let {
                val elapsed = (nowMillis() - it).coerceAtLeast(0)
                pendingMillis += elapsed
                totalActiveMillis += elapsed
            }
            startedAt = null
        }
    }

    /** Keeps subsecond time across short pauses instead of rounding each session down. */
    fun drainSeconds(): Long {
        val running = startedAt != null
        setRunning(false)
        val seconds = pendingMillis / 1_000
        pendingMillis %= 1_000
        if (running) setRunning(true)
        return seconds
    }

    fun reset() {
        startedAt = null
        pendingMillis = 0L
        totalActiveMillis = 0L
    }
}
