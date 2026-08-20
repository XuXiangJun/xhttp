package org.xuxiangjun.xhttp

/**
 * Process exit codes. Values are aligned with `curl`'s for every condition curl also has, so
 * existing scripts that branch on `$?` keep working.
 */
internal object ExitCode {
    const val OK = 0
    const val UNSUPPORTED_PROTOCOL = 1
    const val USAGE = 2
    const val URL_MALFORMED = 3
    const val COULDNT_RESOLVE_PROXY = 5
    const val COULDNT_RESOLVE_HOST = 6
    const val COULDNT_CONNECT = 7
    const val PARTIAL_FILE = 18
    const val HTTP_RETURNED_ERROR = 22
    const val WRITE_ERROR = 23
    const val READ_ERROR = 26
    const val OPERATION_TIMEDOUT = 28
    const val SSL_CONNECT_ERROR = 35
    const val TOO_MANY_REDIRECTS = 47
    const val SEND_ERROR = 55
    const val RECV_ERROR = 56
    const val PEER_FAILED_VERIFICATION = 60
    const val UNKNOWN = 1
}

/**
 * An error caused by invalid user input, I/O or the network. It carries the process exit code and
 * is reported as a single `Error: ...` line, never as a stack trace.
 */
internal class XhttpException(
    message: String,
    val exitCode: Int = ExitCode.UNKNOWN,
    val hint: String? = null,
) : Exception(message)

/** Aborts with [message] and [exitCode]. */
internal fun fail(message: String, exitCode: Int = ExitCode.UNKNOWN, hint: String? = null): Nothing =
    throw XhttpException(message, exitCode, hint)

/** Aborts because of a command-line mistake; the message is followed by a pointer to `--help`. */
internal fun failWithUsage(message: String): Nothing =
    throw XhttpException(message, ExitCode.USAGE, "Run 'xhttp --help' for usage.")

private val curlErrorPattern = Regex("""CURLE_[A-Z0-9_]+""")

/** curl error name -> (exit code, human readable explanation). */
private val curlErrors: Map<String, Pair<Int, String>> = mapOf(
    "CURLE_UNSUPPORTED_PROTOCOL" to (ExitCode.UNSUPPORTED_PROTOCOL to "unsupported protocol"),
    "CURLE_URL_MALFORMAT" to (ExitCode.URL_MALFORMED to "malformed URL"),
    "CURLE_COULDNT_RESOLVE_PROXY" to (ExitCode.COULDNT_RESOLVE_PROXY to "could not resolve the proxy host"),
    "CURLE_COULDNT_RESOLVE_HOST" to (ExitCode.COULDNT_RESOLVE_HOST to "could not resolve host"),
    "CURLE_COULDNT_CONNECT" to (ExitCode.COULDNT_CONNECT to "connection refused or host unreachable"),
    "CURLE_OPERATION_TIMEDOUT" to (ExitCode.OPERATION_TIMEDOUT to "the operation timed out"),
    "CURLE_SSL_CONNECT_ERROR" to (ExitCode.SSL_CONNECT_ERROR to "the TLS handshake failed"),
    "CURLE_SSL_CACERT" to (ExitCode.PEER_FAILED_VERIFICATION to "the server certificate is not trusted"),
    "CURLE_PEER_FAILED_VERIFICATION" to
        (ExitCode.PEER_FAILED_VERIFICATION to "the server certificate could not be verified"),
    "CURLE_SEND_ERROR" to (ExitCode.SEND_ERROR to "failed sending data to the server"),
    "CURLE_RECV_ERROR" to (ExitCode.RECV_ERROR to "failed receiving data from the server"),
    "CURLE_GOT_NOTHING" to (ExitCode.RECV_ERROR to "the server closed the connection without a reply"),
    "CURLE_PARTIAL_FILE" to (ExitCode.PARTIAL_FILE to "the transfer ended before the announced size"),
    "CURLE_TOO_MANY_REDIRECTS" to (ExitCode.TOO_MANY_REDIRECTS to "too many redirects"),
)

/**
 * Turns a transport-level exception into a short, actionable message.
 *
 * The raw Ktor/curl exception text leaks internal data classes and `CURLE_*` constants
 * (`Connection failed for request: CurlRequestData(url=..., ...) Reason: ... (CURLE_COULDNT_CONNECT)`),
 * which is not something a user should have to read.
 */
internal fun describeTransportFailure(cause: Throwable, target: String?): XhttpException {
    if (cause is XhttpException) return cause
    val raw = cause.message ?: cause.toString()
    val where = target?.let { " to $it" } ?: ""

    if (raw.contains("HttpRequestTimeoutException") || cause::class.simpleName == "HttpRequestTimeoutException") {
        return XhttpException(
            "The request$where timed out.", ExitCode.OPERATION_TIMEDOUT,
            "Raise --request-timeout / --max-time, or drop it to wait indefinitely.",
        )
    }
    if (cause::class.simpleName == "ConnectTimeoutException") {
        return XhttpException(
            "Timed out while connecting$where.", ExitCode.OPERATION_TIMEDOUT,
            "Raise --connect-timeout.",
        )
    }
    if (cause::class.simpleName == "SocketTimeoutException") {
        return XhttpException(
            "The connection$where stalled with no data.", ExitCode.OPERATION_TIMEDOUT,
            "Raise --socket-timeout.",
        )
    }

    val code = curlErrorPattern.find(raw)?.value
    val known = code?.let { curlErrors[it] }
    if (known != null) {
        val (exitCode, explanation) = known
        val hint = when (exitCode) {
            ExitCode.PEER_FAILED_VERIFICATION ->
                "Use --cacert <file> to trust a private CA, or -k to skip verification (unsafe)."
            ExitCode.COULDNT_RESOLVE_HOST -> "Check the host name, DNS settings and any proxy configuration."
            ExitCode.COULDNT_CONNECT -> "Check that the port is open and that nothing blocks the connection."
            else -> null
        }
        return XhttpException("Request$where failed: $explanation ($code).", exitCode, hint)
    }
    return XhttpException("Request$where failed: $raw", ExitCode.UNKNOWN)
}
