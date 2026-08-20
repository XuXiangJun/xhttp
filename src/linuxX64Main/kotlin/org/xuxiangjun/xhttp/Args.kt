package org.xuxiangjun.xhttp

import io.ktor.http.HttpMethod

/** A multipart form field: a literal value, or a file to stream. */
internal data class FormField(
    val name: String,
    val value: String? = null,
    val file: String? = null,
    val fileName: String? = null,
    val contentType: String? = null,
)

/** Where the request body comes from. */
internal sealed interface RequestBody {
    /** No body. */
    data object None : RequestBody

    /** A body fully known up front (`-d`, `--data-raw`, `--data-urlencode`). */
    class Raw(val bytes: ByteArray) : RequestBody

    /** A file streamed straight from disk, never held in memory (`--data-binary @file`). */
    class Streamed(val path: String) : RequestBody

    /** A `multipart/form-data` body; file parts are streamed (`-F`). */
    class Multipart(val fields: List<FormField>) : RequestBody
}

/** Which parts of the exchange go to stdout. */
internal data class PrintSpec(
    val requestHeaders: Boolean = false,
    val requestBody: Boolean = false,
    val responseHeaders: Boolean = false,
    val responseBody: Boolean = true,
) {
    companion object {
        /** Parses a `--print` spec: `H` request headers, `B` request body, `h`/`b` the response. */
        fun parse(spec: String): PrintSpec {
            var value = PrintSpec(responseBody = false)
            for (char in spec) {
                value = when (char) {
                    'H' -> value.copy(requestHeaders = true)
                    'B' -> value.copy(requestBody = true)
                    'h' -> value.copy(responseHeaders = true)
                    'b' -> value.copy(responseBody = true)
                    else -> failWithUsage("Invalid --print spec '$spec': '$char' is not one of H, B, h, b.")
                }
            }
            return value
        }
    }
}

/** How cookies are persisted between runs. */
internal data class CookieStore(
    /** File cookies are loaded from at start-up. */
    val loadFrom: String? = null,
    /** File cookies are written back to when the run finishes. */
    val saveTo: String? = null,
)

/** Fully parsed and validated command-line arguments. */
internal data class XhttpArgs(
    val urls: List<String>,
    val method: HttpMethod,
    val parameters: List<Pair<String, String>>,
    val headers: List<Pair<String, String>>,
    val pathVariables: List<Pair<String, String>>,
    val cookies: List<Pair<String, String>>,
    val body: RequestBody,
    val bodyAsQuery: Boolean,
    val defaultContentType: String?,
    val allowRedirects: Boolean,
    val maxRedirects: Int,
    val prettyPrint: Boolean,
    val color: Boolean,
    val select: String?,
    val verbose: Boolean,
    val trace: Boolean,
    val print: PrintSpec,
    val silent: Boolean,
    val showError: Boolean,
    val failOnError: Boolean,
    val json: Boolean,
    val user: String?,
    val bearer: String?,
    val netrc: Boolean,
    val netrcFile: String?,
    val insecure: Boolean,
    val caCert: String?,
    val caPath: String?,
    val proxy: String?,
    val proxyUser: String?,
    val noProxy: String?,
    val output: String?,
    val outputDir: String?,
    val remoteName: Boolean,
    val createDirs: Boolean,
    val resume: Boolean,
    val cookieStore: CookieStore,
    val connectTimeoutSeconds: Long?,
    val socketTimeoutSeconds: Long?,
    val requestTimeoutSeconds: Long?,
    val retries: Int,
    val retryDelaySeconds: Double,
    val limitRateBytesPerSecond: Long?,
    val writeOut: String?,
    val dryRun: Boolean,
)
