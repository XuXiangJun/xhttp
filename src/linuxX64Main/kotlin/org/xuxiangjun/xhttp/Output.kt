package org.xuxiangjun.xhttp

import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess
import platform.posix.STDERR_FILENO
import platform.posix.isatty

private val jsonPretty = Json { prettyPrint = true }

/**
 * Prints the response according to the CLI flags:
 *  - `-v`    full exchange to stderr;
 *  - `-i`    status line + headers to stdout;
 *  - `-o`    raw body streamed to a file, with download progress on stderr;
 *  - `-p`    JSON is pretty-printed, other bodies are passed through.
 */
internal suspend fun writeResponse(args: XhttpArgs, response: HttpResponse) {
    if (args.verbose) {
        printExchangeToStderr(response)
    }

    if (args.includeHeaders) {
        printResponseHeaders(response)
    }

    if (args.output != null) {
        writeBodyToFile(args, response)
        return
    }

    val contentType = response.contentType()
    if (!isTextualContentType(contentType)) {
        Stderr.println("Error: refusing to print a binary response body to the terminal.")
        Stderr.println("Hint: use --output <file> to save it to a file instead.")
        exitProcess(1)
    }

    val bodyText = response.bodyAsText()
    if (args.prettyPrint && isJsonContentType(contentType)) {
        printPrettyJsonOrRaw(bodyText)
    } else {
        println(bodyText)
    }
}

private fun printExchangeToStderr(response: HttpResponse) {
    val request = response.request
    Stderr.println("> ${request.method.value} ${request.url}")
    request.headers.forEach { name, values ->
        for (value in values) {
            Stderr.println("> $name: $value")
        }
    }
    Stderr.println(">")
    printResponseHeadersToStderr(response)
}

private fun printResponseHeaders(response: HttpResponse) {
    println("< ${response.status}")
    response.headers.forEach { name, values ->
        for (value in values) {
            println("< $name: $value")
        }
    }
    println("<")
}

private fun printResponseHeadersToStderr(response: HttpResponse) {
    Stderr.println("< ${response.status}")
    response.headers.forEach { name, values ->
        for (value in values) {
            Stderr.println("< $name: $value")
        }
    }
    Stderr.println("<")
}

private suspend fun writeBodyToFile(args: XhttpArgs, response: HttpResponse) {
    val path = args.output!!
    val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    val channel = response.bodyAsChannel()
    val showProgress = stderrIsTty()
    var received = 0L
    var printed = false
    val buffer = ByteArray(64 * 1024)
    // Created inside the try so that a failure to open the file (missing directory,
    // no permission, ...) surfaces as a friendly "Cannot write file ..." error.
    var sink: RawSink? = null
    try {
        sink = SystemFileSystem.sink(Path(path)).buffered()
        while (true) {
            val read = channel.readAvailable(buffer)
            if (read == -1) break
            if (read > 0) {
                sink.write(buffer, 0, read)
                received += read
                if (showProgress) {
                    printed = true
                    if (total != null && total > 0) {
                        val percent = received.toDouble() / total * 100.0
                        Stderr.print(
                            "\r${formatDownloadedSize(received)} / ${formatDownloadedSize(total)} " +
                                "(${formatPercent(percent)})"
                        )
                    } else {
                        Stderr.print("\r${formatDownloadedSize(received)} downloaded")
                    }
                }
            }
        }
        sink.flush()
        if (printed) {
            Stderr.println("")
        }
    } catch (e: Exception) {
        throw XhttpException("Cannot write file '$path': ${e.message}")
    } finally {
        // close() may itself throw (e.g. the file could never be opened); never let it
        // mask the original exception.
        try {
            sink?.close()
        } catch (_: Exception) {
            // ignore
        }
    }
}

/** Whether stderr is attached to a terminal; download progress is only shown then. */
@OptIn(ExperimentalForeignApi::class)
private fun stderrIsTty(): Boolean = isatty(STDERR_FILENO) != 0

private const val KIBIBYTE = 1024.0
private const val MEBIBYTE = 1024.0 * 1024.0
private const val GIBIBYTE = 1024.0 * 1024.0 * 1024.0

/** Formats a downloaded byte count using the most readable binary unit. */
private fun formatDownloadedSize(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes bytes"
        bytes < 1024L * 1024L -> "${formatDecimal(bytes / KIBIBYTE, 1)} KB"
        bytes < 1024L * 1024L * 1024L -> "${formatDecimal(bytes / MEBIBYTE, 1)} MB"
        else -> "${formatDecimal(bytes / GIBIBYTE, 2)} GB"
    }
}

private fun formatPercent(percent: Double): String = "${formatDecimal(percent, 1)}%"

/** Rounds [value] to [decimalPlaces] decimal places without relying on JVM-only String.format. */
private fun formatDecimal(value: Double, decimalPlaces: Int): String {
    val factor = when (decimalPlaces) {
        1 -> 10.0
        2 -> 100.0
        else -> 1.0
    }
    val scaled = kotlin.math.round(value * factor).toLong()
    val factorLong = factor.toLong()
    val whole = scaled / factorLong
    val fraction = scaled % factorLong
    return "$whole.${fraction.toString().padStart(decimalPlaces, '0')}"
}

private fun printPrettyJsonOrRaw(text: String) {
    try {
        val element = jsonPretty.parseToJsonElement(text)
        println(jsonPretty.encodeToString(element))
    } catch (e: Exception) {
        // Invalid JSON: degrade gracefully instead of crashing.
        println(text)
    }
}

/** Whether a response body of this content type is safe to print as text. */
private fun isTextualContentType(contentType: ContentType?): Boolean {
    if (contentType == null) return true
    if (isJsonContentType(contentType)) return true
    val type = contentType.contentType.lowercase()
    val subtype = contentType.contentSubtype.lowercase()
    return when {
        type == "text" -> true
        type == "multipart" -> true
        type == "application" && (
            subtype == "xhtml+xml" || subtype == "xml" || subtype == "javascript" ||
                subtype == "ecmascript" || subtype == "x-www-form-urlencoded" ||
                subtype == "graphql" || subtype.endsWith("+xml") || subtype.endsWith("+json")
            ) -> true
        type == "image" && subtype == "svg+xml" -> true
        else -> false
    }
}

private fun isJsonContentType(contentType: ContentType?): Boolean {
    if (contentType == null) return false
    return contentType.match(ContentType.Application.Json) ||
        contentType.contentSubtype.endsWith("+json", ignoreCase = true)
}
