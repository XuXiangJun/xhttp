package org.xuxiangjun.xhttp

import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

private val jsonPretty = Json { prettyPrint = true }

/**
 * Prints the response according to the CLI flags:
 *  - `-v`    full exchange to stderr;
 *  - `-i`    status line + headers to stdout;
 *  - `-o`    raw body written to a file (optionally with progress);
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
    if (args.progress) {
        writeBodyToFileWithProgress(response, path)
    } else {
        writeFileBytes(path, response.bodyAsBytes())
    }
}

private suspend fun writeBodyToFileWithProgress(response: HttpResponse, path: String) {
    val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    val channel = response.bodyAsChannel()
    val sink = SystemFileSystem.sink(Path(path)).buffered()
    var received = 0L
    val buffer = ByteArray(64 * 1024)
    try {
        while (true) {
            val read = channel.readAvailable(buffer)
            if (read == -1) break
            if (read > 0) {
                sink.write(buffer, 0, read)
                received += read
                if (total != null && total > 0) {
                    val percent = received * 100 / total
                    Stderr.print("\r$received/$total bytes ($percent%)")
                } else {
                    Stderr.print("\r$received bytes received")
                }
            }
        }
        sink.flush()
        Stderr.println("")
    } catch (e: Exception) {
        throw XhttpException("Cannot write file '$path': ${e.message}")
    } finally {
        sink.close()
    }
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
