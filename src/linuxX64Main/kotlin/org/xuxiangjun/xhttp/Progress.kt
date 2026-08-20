package org.xuxiangjun.xhttp

import kotlinx.coroutines.delay
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

private const val CLEAR_TO_END = "\u001b[K"
private const val REDRAW_INTERVAL_SECONDS = 0.1

/**
 * Download progress on stderr.
 *
 * Redraws are throttled: the previous version wrote and flushed on every 64 KiB chunk, which on a
 * fast link cost thousands of syscalls per second and slowed the transfer it was measuring.
 */
internal class ProgressMeter(
    private val total: Long?,
    private val enabled: Boolean,
    private val label: String,
) {
    private val start = TimeSource.Monotonic.markNow()
    private var lastDrawSeconds = -1.0
    private var drawn = false

    fun update(received: Long) {
        if (!enabled) return
        val elapsed = start.elapsedNow().toDouble(DurationUnit.SECONDS)
        if (lastDrawSeconds >= 0 && elapsed - lastDrawSeconds < REDRAW_INTERVAL_SECONDS) return
        lastDrawSeconds = elapsed
        draw(received, elapsed)
    }

    fun finish(received: Long) {
        if (!enabled) return
        draw(received, start.elapsedNow().toDouble(DurationUnit.SECONDS))
        if (drawn) Stderr.println()
    }

    private fun draw(received: Long, elapsed: Double) {
        drawn = true
        val rate = if (elapsed > 0) received / elapsed else 0.0
        val line = buildString {
            append('\r')
            append(label)
            append("  ")
            append(formatBytes(received))
            if (total != null && total > 0) {
                append(" / ")
                append(formatBytes(total))
                append("  ")
                append(formatDecimal(received.toDouble() / total * 100.0, 1))
                append('%')
            }
            append("  ")
            append(formatRate(rate))
            if (total != null && total > received && rate > 0) {
                append("  eta ")
                append(formatClock((total - received) / rate))
            }
            append(CLEAR_TO_END)
        }
        Stderr.print(line)
    }
}

/** Client-side throttle for `--limit-rate`. */
internal class RateLimiter(private val bytesPerSecond: Long?) {
    private val start = TimeSource.Monotonic.markNow()

    suspend fun await(transferred: Long) {
        val limit = bytesPerSecond ?: return
        val expected = transferred.toDouble() / limit
        val actual = start.elapsedNow().toDouble(DurationUnit.SECONDS)
        val sleep = expected - actual
        if (sleep > 0) delay((sleep * 1000).toLong())
    }
}
