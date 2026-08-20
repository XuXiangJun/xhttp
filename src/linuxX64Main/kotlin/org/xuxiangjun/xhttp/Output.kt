package org.xuxiangjun.xhttp

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

private const val COPY_BUFFER = 64 * 1024

/**
 * Writes the response out according to the CLI flags and returns the number of body bytes handled.
 *
 * Bodies are streamed: only `--pretty` and `--select`, which need the whole document, buffer.
 */
internal suspend fun renderResponse(args: XhttpArgs, response: HttpResponse, outputPath: String?): Long {
    if (args.verbose) dumpExchangeToStderr(args, response)
    if (args.print.requestHeaders) printRequestHead(args, response, Stdout)
    if (args.print.requestBody) printRequestBody(args, Stdout)
    if (args.print.responseHeaders) printResponseHead(args, response, Stdout)

    val failing = args.failOnError && response.status.value >= 400
    if (failing || (!args.print.responseBody && outputPath == null)) {
        response.bodyAsChannel().discard()
        return 0
    }
    if (outputPath != null) return writeBodyToFile(args, response, outputPath)
    return writeBodyToStdout(args, response)
}

private suspend fun ByteReadChannel.discard() {
    val buffer = ByteArray(COPY_BUFFER)
    while (readAvailable(buffer) >= 0) {
        // Drain so the connection can be reused or closed cleanly.
    }
}

// ---- headers --------------------------------------------------------------

private fun statusLine(response: HttpResponse): String =
    "${response.version} ${response.status.value} ${response.status.description}"

private fun printResponseHead(args: XhttpArgs, response: HttpResponse, sink: ByteSink) {
    sink.println(colorize(statusLine(response), Ansi.BOLD, args.color))
    response.headers.forEach { name, values ->
        for (value in values) {
            sink.println("${colorize(name, Ansi.CYAN, args.color)}: $value")
        }
    }
    sink.println()
}

private fun printRequestHead(args: XhttpArgs, response: HttpResponse, sink: ByteSink) {
    val request = response.request
    val url = request.url
    sink.println(colorize("${request.method.value} ${pathAndQuery(url)} HTTP/1.1", Ansi.BOLD, args.color))
    sink.println("${colorize("Host", Ansi.CYAN, args.color)}: ${url.hostWithPortIfRequired}")
    request.headers.forEach { name, values ->
        for (value in values) {
            sink.println("${colorize(name, Ansi.CYAN, args.color)}: ${maskSecret(name, value, args)}")
        }
    }
    sink.println()
}

private fun printRequestBody(args: XhttpArgs, sink: ByteSink) {
    when (val body = args.body) {
        is RequestBody.None -> Unit
        is RequestBody.Raw -> {
            sink.write(body.bytes)
            sink.println()
        }
        is RequestBody.Streamed -> sink.println("[body streamed from ${body.path}]")
        is RequestBody.Multipart -> sink.println("[multipart body with ${body.fields.size} field(s)]")
    }
}

private fun pathAndQuery(url: Url): String {
    val query = url.encodedQuery
    val path = url.encodedPath.ifEmpty { "/" }
    return if (query.isEmpty()) path else "$path?$query"
}

/**
 * Hides credentials in `-v` output.
 *
 * A verbose dump routinely ends up in a bug report or CI log, and an `Authorization` header there
 * is a leaked credential.
 */
private fun maskSecret(name: String, value: String, args: XhttpArgs): String {
    if (args.trace) return value
    val sensitive = name.equals(HttpHeaders.Authorization, ignoreCase = true) ||
        name.equals(HttpHeaders.ProxyAuthorization, ignoreCase = true) ||
        name.equals(HttpHeaders.Cookie, ignoreCase = true) ||
        name.equals(HttpHeaders.SetCookie, ignoreCase = true)
    if (!sensitive) return value
    val scheme = value.substringBefore(' ', "")
    return if (scheme.isNotEmpty() && value.contains(' ')) "$scheme <redacted>" else "<redacted>"
}

private fun dumpExchangeToStderr(args: XhttpArgs, response: HttpResponse) {
    val request = response.request
    val url = request.url
    Stderr.println(colorize("> ${request.method.value} ${pathAndQuery(url)} HTTP/1.1", Ansi.BOLD, args.color))
    Stderr.println("> Host: ${url.hostWithPortIfRequired}")
    request.headers.forEach { name, values ->
        for (value in values) {
            Stderr.println("> $name: ${maskSecret(name, value, args)}")
        }
    }
    Stderr.println(">")
    if (args.trace) {
        when (val body = args.body) {
            is RequestBody.Raw -> Stderr.println(body.bytes.decodeToString().prependIndent("> "))
            is RequestBody.Streamed -> Stderr.println("> [streamed from ${body.path}]")
            is RequestBody.Multipart -> Stderr.println("> [multipart, ${body.fields.size} field(s)]")
            is RequestBody.None -> Unit
        }
    }
    Stderr.println(colorize("< ${statusLine(response)}", Ansi.BOLD, args.color))
    response.headers.forEach { name, values ->
        for (value in values) {
            Stderr.println("< $name: ${maskSecret(name, value, args)}")
        }
    }
    Stderr.println("<")
}

// ---- body -----------------------------------------------------------------

private suspend fun writeBodyToStdout(args: XhttpArgs, response: HttpResponse): Long {
    val contentType = response.contentType()
    if (args.select != null || (args.prettyPrint && isJsonContentType(contentType))) {
        return writeTransformedBody(args, response)
    }

    val channel = response.bodyAsChannel()
    val buffer = ByteArray(COPY_BUFFER)
    var written = 0L
    var checked = false
    while (true) {
        val read = channel.readAvailable(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (!checked) {
            checked = true
            guardBinaryOutput(contentType, buffer, read)
        }
        Stdout.write(buffer, 0, read)
        written += read
    }
    Stdout.flush()
    // A terminal prompt should not start mid-line; pipes and files stay byte-exact.
    if (Stdout.isTty && written > 0 && buffer.isNotEmpty()) Stdout.println()
    return written
}

private suspend fun writeTransformedBody(args: XhttpArgs, response: HttpResponse): Long {
    val bytes = readAll(response.bodyAsChannel())
    val text = bytes.decodeToString()
    val element = parseJsonOrNull(text)
    if (element == null) {
        if (args.select != null) {
            fail("--select needs a JSON body, but the response is not JSON.", ExitCode.UNKNOWN)
        }
        // Not JSON after all: pass it through untouched rather than failing.
        Stdout.write(bytes)
        if (Stdout.isTty && bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) Stdout.println()
        return bytes.size.toLong()
    }
    val rendered = if (args.select != null) {
        selectJson(element, args.select).joinToString("\n") { renderSelected(it, args.prettyPrint, args.color) }
    } else {
        renderJson(element, args.prettyPrint, args.color)
    }
    Stdout.println(rendered)
    Stdout.flush()
    return bytes.size.toLong()
}

private suspend fun readAll(channel: ByteReadChannel): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    val buffer = ByteArray(COPY_BUFFER)
    var total = 0
    while (true) {
        val read = channel.readAvailable(buffer)
        if (read < 0) break
        if (read == 0) continue
        chunks += buffer.copyOf(read)
        total += read
    }
    val result = ByteArray(total)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(result, offset)
        offset += chunk.size
    }
    return result
}

private suspend fun writeBodyToFile(args: XhttpArgs, response: HttpResponse, path: String): Long {
    // The server only honoured the resume request if it answered 206; a 200 means start over.
    val resumed = args.resume && response.status.value == 206
    val alreadyOnDisk = if (resumed) fileSize(path) ?: 0L else 0L
    val announced = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    val total = announced?.let { it + alreadyOnDisk }

    val channel = response.bodyAsChannel()
    val progress = ProgressMeter(
        total = total,
        enabled = !args.silent && Stderr.isTty,
        label = path.substringAfterLast('/'),
    )
    val limiter = RateLimiter(args.limitRateBytesPerSecond)
    val buffer = ByteArray(COPY_BUFFER)
    var received = 0L

    val sink = openSink(path, append = resumed, createDirs = args.createDirs)
    try {
        while (true) {
            val read = channel.readAvailable(buffer)
            if (read < 0) break
            if (read == 0) continue
            try {
                sink.write(buffer, 0, read)
            } catch (e: Exception) {
                fail("Cannot write file '$path': ${e.message}", ExitCode.WRITE_ERROR)
            }
            received += read
            progress.update(alreadyOnDisk + received)
            limiter.await(received)
        }
        sink.flush()
    } finally {
        try {
            sink.close()
        } catch (_: Exception) {
            // A close failure must never mask the original error.
        }
    }
    progress.finish(alreadyOnDisk + received)
    if (announced != null && received < announced) {
        fail(
            "Transfer ended early: got $received of $announced bytes.",
            ExitCode.PARTIAL_FILE,
            "Re-run with -C - to resume.",
        )
    }
    return received
}

private fun guardBinaryOutput(contentType: ContentType?, chunk: ByteArray, length: Int) {
    // Only a terminal needs protecting: a pipe or a file wants the exact bytes.
    if (!Stdout.isTty) return
    val binary = if (contentType != null) {
        !isTextualContentType(contentType)
    } else {
        (0 until length).any { chunk[it] == 0.toByte() }
    }
    if (binary) {
        fail(
            "Refusing to print a binary response body to the terminal.",
            ExitCode.WRITE_ERROR,
            "Use --output <file> to save it, or pipe the output somewhere.",
        )
    }
}

/** Where the body should be written, taking `-o`, `-O` and `--output-dir` into account. */
internal fun resolveOutputPath(args: XhttpArgs, url: String): String? {
    val name = when {
        args.output == "-" -> return null
        args.output != null -> args.output
        args.remoteName -> Url(url).segments.lastOrNull()?.takeIf { it.isNotEmpty() }
            ?: fail(
                "--remote-name needs a file name in the URL path.",
                ExitCode.USAGE,
                "Use --output <file> instead.",
            )
        else -> return null
    }
    val directory = args.outputDir?.trimEnd('/') ?: return name
    return "$directory/${name.substringAfterLast('/')}"
}

/** Whether a body of this content type is safe to print to a terminal. */
internal fun isTextualContentType(contentType: ContentType?): Boolean {
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
                subtype == "graphql" || subtype == "yaml" || subtype == "x-ndjson" ||
                subtype.endsWith("+xml") || subtype.endsWith("+json")
            ) -> true
        type == "image" && subtype == "svg+xml" -> true
        else -> false
    }
}

internal fun isJsonContentType(contentType: ContentType?): Boolean {
    if (contentType == null) return false
    return contentType.match(ContentType.Application.Json) ||
        contentType.contentSubtype.endsWith("+json", ignoreCase = true)
}

/** The `--dry-run` report: exactly what would have been sent. */
internal fun describeRequest(args: XhttpArgs, url: String) {
    val target = Url(url)
    Stdout.println(colorize("${args.method.value} ${pathAndQuery(target)} HTTP/1.1", Ansi.BOLD, args.color))
    Stdout.println("Host: ${target.hostWithPortIfRequired}")
    Stdout.println("User-Agent: $PROGRAM/$APP_VERSION")
    for ((name, value) in args.headers) {
        Stdout.println("$name: $value")
    }
    if (args.cookies.isNotEmpty()) {
        Stdout.println("Cookie: ${args.cookies.joinToString("; ") { (name, value) -> "$name=$value" }}")
    }
    if (args.user != null) Stdout.println("Authorization: Basic <redacted>")
    if (args.bearer != null) Stdout.println("Authorization: Bearer <redacted>")
    val declared = args.headers.firstOrNull { it.first.equals(HttpHeaders.ContentType, ignoreCase = true) }
    if (declared == null) {
        args.defaultContentType?.let { Stdout.println("Content-Type: $it") }
    }
    Stdout.println()
    printRequestBody(args, Stdout)
}

private val Url.hostWithPortIfRequired: String
    get() = if (port == protocol.defaultPort) host else "$host:$port"
