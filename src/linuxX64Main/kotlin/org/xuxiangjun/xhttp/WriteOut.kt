package org.xuxiangjun.xhttp

/**
 * Expands a `--write-out` format string.
 *
 * Only variables that can actually be measured are supported; curl exposes a per-phase DNS/TCP/TLS
 * breakdown that the Ktor curl engine does not surface, and reporting a made-up zero for those
 * would be worse than rejecting them.
 */
internal fun expandWriteOut(format: String, transfer: Transfer): String {
    val builder = StringBuilder(format.length)
    var index = 0
    while (index < format.length) {
        val char = format[index]
        if (char != '%') {
            builder.append(char)
            index++
            continue
        }
        if (index + 1 < format.length && format[index + 1] == '%') {
            builder.append('%')
            index += 2
            continue
        }
        if (index + 1 >= format.length || format[index + 1] != '{') {
            builder.append(char)
            index++
            continue
        }
        val end = format.indexOf('}', index + 2)
        if (end < 0) failWithUsage("Invalid --write-out format: unbalanced '{' in '$format'.")
        val name = format.substring(index + 2, end)
        builder.append(writeOutVariable(name, transfer))
        index = end + 1
    }
    return builder.toString()
}

private fun writeOutVariable(name: String, transfer: Transfer): String {
    val elapsed = transfer.timing.elapsedSeconds()
    return when (name) {
        "http_code", "response_code" -> transfer.status.toString()
        "url_effective" -> transfer.effectiveUrl
        "num_redirects" -> transfer.redirects.toString()
        "size_download" -> transfer.bytesDownloaded.toString()
        "size_header" -> transfer.headerBytes.toString()
        "speed_download" -> formatDecimal(
            if (elapsed > 0) transfer.bytesDownloaded / elapsed else 0.0, 0,
        )
        "time_total" -> formatDecimal(elapsed, 6)
        "time_starttransfer" -> formatDecimal(transfer.timing.firstByteSeconds, 6)
        "content_type" -> transfer.contentType ?: ""
        "json" -> writeOutJson(transfer)
        else -> failWithUsage(
            "Unknown --write-out variable '%{$name}'. Supported: http_code, response_code, " +
                "url_effective, num_redirects, size_download, size_header, speed_download, " +
                "time_total, time_starttransfer, content_type, json."
        )
    }
}

private fun writeOutJson(transfer: Transfer): String {
    val elapsed = transfer.timing.elapsedSeconds()
    return buildString {
        append("{\"http_code\":").append(transfer.status)
        append(",\"url_effective\":\"").append(transfer.effectiveUrl).append('"')
        append(",\"num_redirects\":").append(transfer.redirects)
        append(",\"size_download\":").append(transfer.bytesDownloaded)
        append(",\"time_total\":").append(formatDecimal(elapsed, 6))
        append(",\"time_starttransfer\":").append(formatDecimal(transfer.timing.firstByteSeconds, 6))
        append(",\"content_type\":\"").append(transfer.contentType ?: "").append('"')
        append('}')
    }
}
