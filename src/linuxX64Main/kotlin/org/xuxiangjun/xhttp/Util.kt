package org.xuxiangjun.xhttp

import kotlin.math.abs
import kotlin.math.floor

private val headerNameToken = Regex("""[!#$%&'*+.^_`|~0-9A-Za-z-]+""")

/** Parses `name=value` strings (query parameters, path variables, form fields, ...). */
internal fun parseKeyValuePairs(items: List<String>, what: String): List<Pair<String, String>> =
    items.map { parseKeyValuePair(it, what) }

/** Parses a single `name=value` string, splitting on the first `=`. */
internal fun parseKeyValuePair(item: String, what: String): Pair<String, String> {
    val index = item.indexOf('=')
    if (index <= 0) {
        failWithUsage("Invalid $what '$item': expected 'name=value'.")
    }
    return item.substring(0, index) to item.substring(index + 1)
}

/**
 * Parses `Name: value` header arguments.
 *
 * The split is always on the *first* colon, so values that themselves contain `": "` survive
 * (`-H 'X-Trace: id: 42'`). `Name;` sends the header with an empty value, like curl.
 */
internal fun parseHeaderPairs(items: List<String>): List<Pair<String, String>> = items.map { raw ->
    val item = raw.trim()
    if (item.endsWith(";") && !item.contains(':')) {
        val name = item.dropLast(1)
        validateHeaderName(name, raw)
        return@map name to ""
    }
    val index = item.indexOf(':')
    if (index <= 0) {
        failWithUsage("Invalid header '$raw': expected 'Name: value' (or 'Name;' for an empty value).")
    }
    val name = item.substring(0, index).trimEnd()
    validateHeaderName(name, raw)
    name to item.substring(index + 1).trimStart(' ', '\t')
}

private fun validateHeaderName(name: String, raw: String) {
    if (name.isEmpty() || !headerNameToken.matches(name)) {
        failWithUsage("Invalid header '$raw': '$name' is not a valid header name.")
    }
}

private const val KIBIBYTE = 1024.0
private const val MEBIBYTE = 1024.0 * 1024.0
private const val GIBIBYTE = 1024.0 * 1024.0 * 1024.0

/** Formats a byte count using the most readable binary unit. */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${formatDecimal(bytes / KIBIBYTE, 1)} KiB"
    bytes < 1024L * 1024L * 1024L -> "${formatDecimal(bytes / MEBIBYTE, 1)} MiB"
    else -> "${formatDecimal(bytes / GIBIBYTE, 2)} GiB"
}

/** Formats a transfer rate such as `1.4 MiB/s`. */
internal fun formatRate(bytesPerSecond: Double): String = when {
    bytesPerSecond.isNaN() || bytesPerSecond <= 0.0 -> "--"
    bytesPerSecond < KIBIBYTE -> "${formatDecimal(bytesPerSecond, 0)} B/s"
    bytesPerSecond < MEBIBYTE -> "${formatDecimal(bytesPerSecond / KIBIBYTE, 1)} KiB/s"
    bytesPerSecond < GIBIBYTE -> "${formatDecimal(bytesPerSecond / MEBIBYTE, 1)} MiB/s"
    else -> "${formatDecimal(bytesPerSecond / GIBIBYTE, 2)} GiB/s"
}

/** Formats a duration in seconds as `mm:ss`, or `--:--` when it is unknown. */
internal fun formatClock(seconds: Double): String {
    if (seconds.isNaN() || seconds.isInfinite() || seconds < 0.0) return "--:--"
    val total = seconds.toLong()
    if (total >= 100L * 3600L) return "--:--"
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
    }
}

private val decimalFactors = longArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000)

/**
 * Rounds [value] to [decimalPlaces] digits.
 *
 * Kotlin/Native has no `String.format`, so the digits are assembled by hand; negatives and the
 * carry from `9.99 -> 10.0` are handled explicitly.
 */
internal fun formatDecimal(value: Double, decimalPlaces: Int): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Inf" else "-Inf"
    val places = decimalPlaces.coerceIn(0, decimalFactors.size - 1)
    val factor = decimalFactors[places]
    val negative = value < 0
    // floor(x + 0.5) rather than kotlin.math.round: the latter rounds ties to even, so 1.25 would
    // display as 1.2 while every other tool shows 1.3.
    val scaled = floor(abs(value) * factor + 0.5).toLong()
    val whole = scaled / factor
    val fraction = scaled % factor
    val sign = if (negative && scaled != 0L) "-" else ""
    return if (places == 0) {
        "$sign$whole"
    } else {
        "$sign$whole.${fraction.toString().padStart(places, '0')}"
    }
}

/** Levenshtein distance, used to suggest the option the user probably meant. */
internal fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}
