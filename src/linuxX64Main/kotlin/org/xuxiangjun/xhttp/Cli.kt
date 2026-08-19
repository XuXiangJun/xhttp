package org.xuxiangjun.xhttp

import io.ktor.http.HttpMethod
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.ExperimentalCli
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.cli.optional
import kotlinx.serialization.json.Json

/** A single multipart form field: either a plain value or a file to upload. */
internal data class FormField(val name: String, val value: String? = null, val file: String? = null)

/** Fully parsed, validated command-line arguments. [url] is null until [main] enforces it. */
internal data class XhttpArgs(
    val url: String?,
    val method: HttpMethod,
    val parameters: List<Pair<String, String>>,
    val headers: List<Pair<String, String>>,
    val pathVariables: List<Pair<String, String>>,
    val cookies: List<Pair<String, String>>,
    val formFields: List<FormField>,
    val data: ByteArray?,
    val allowRedirects: Boolean,
    val prettyPrint: Boolean,
    val verbose: Boolean,
    val includeHeaders: Boolean,
    val failOnError: Boolean,
    val json: Boolean,
    val user: String?,
    val bearer: String?,
    val insecure: Boolean,
    val proxy: String?,
    val output: String?,
    val progress: Boolean,
    val session: Boolean,
    val connectTimeoutSeconds: Long?,
    val socketTimeoutSeconds: Long?,
    val requestTimeoutSeconds: Long?,
    val version: Boolean,
)

private val methodToken = Regex("""[!#$%&'*+.^_`|~0-9A-Za-z-]+""")
private val jsonValidator = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalCli::class)
internal fun parseCliArgs(args: Array<String>): XhttpArgs {
    val parser = ArgParser("xhttp")

    val positionalUrl by parser
        .argument(ArgType.String, description = "URL to request, e.g. https://example.com/api/{id}")
        .optional()

    val methodName by parser
        .option(ArgType.String, fullName = "request", shortName = "X", description = "HTTP method: GET, POST, PUT, DELETE, ...")
        .default("GET")
    val header by parser
        .option(ArgType.String, fullName = "header", shortName = "H", description = "HTTP header \"Name: value\" (repeatable)")
        .multiple()
    val param by parser
        .option(ArgType.String, fullName = "param", shortName = "P", description = "Query parameter \"name=value\" (repeatable)")
        .multiple()
    val pathVariable by parser
        .option(ArgType.String, fullName = "path-variable", shortName = "V", description = "Path variable \"name=value\" replacing {name} in the URL (repeatable)")
        .multiple()
    val dataText by parser
        .option(ArgType.String, fullName = "data", shortName = "d", description = "Request body text; prefix with @ to read from a file")
    val dataFile by parser
        .option(ArgType.String, fullName = "data-file", description = "Read the request body from a file")
    val form by parser
        .option(ArgType.String, fullName = "form", shortName = "F", description = "Multipart form field \"name=value\" or \"name=@file\" (repeatable)")
        .multiple()
    val output by parser
        .option(ArgType.String, fullName = "output", shortName = "o", description = "Write the response body to a file")
    val prettyPrint by parser
        .option(ArgType.Boolean, fullName = "pretty", shortName = "p", description = "Pretty-print JSON response bodies")
        .default(false)
    val verbose by parser
        .option(ArgType.Boolean, fullName = "verbose", shortName = "v", description = "Print the full request/response exchange to stderr")
        .default(false)
    val include by parser
        .option(ArgType.Boolean, fullName = "include", shortName = "i", description = "Include the response status line and headers in the output")
        .default(false)
    val allowRedirects by parser
        .option(ArgType.Boolean, fullName = "allow-redirects", shortName = "L", description = "Follow HTTP redirects")
        .default(false)
    val failOnError by parser
        .option(ArgType.Boolean, fullName = "fail", description = "Exit with status 22 when the HTTP status is >= 400")
        .default(false)
    val json by parser
        .option(ArgType.Boolean, fullName = "json", description = "Set Content-Type and Accept to application/json")
        .default(false)
    val user by parser
        .option(ArgType.String, fullName = "user", shortName = "u", description = "Basic auth credentials \"user:password\"")
    val bearer by parser
        .option(ArgType.String, fullName = "bearer", description = "Bearer auth token")
    val insecure by parser
        .option(ArgType.Boolean, fullName = "insecure", shortName = "k", description = "Disable TLS certificate verification")
        .default(false)
    val proxy by parser
        .option(ArgType.String, fullName = "proxy", description = "HTTP proxy URL, e.g. http://127.0.0.1:8080")
    val cookie by parser
        .option(ArgType.String, fullName = "cookie", shortName = "c", description = "Cookie \"name=value\" (repeatable)")
        .multiple()
    val session by parser
        .option(ArgType.Boolean, fullName = "session", description = "Keep an in-memory cookie jar and reuse cookies on redirects")
        .default(false)
    val progress by parser
        .option(ArgType.Boolean, fullName = "progress", description = "Show download progress to stderr (requires --output)")
        .default(false)
    val connectTimeout by parser
        .option(ArgType.Int, fullName = "connect-timeout", description = "Connection timeout in seconds")
    val socketTimeout by parser
        .option(ArgType.Int, fullName = "socket-timeout", description = "Socket timeout in seconds")
    val requestTimeout by parser
        .option(ArgType.Int, fullName = "request-timeout", description = "Overall request timeout in seconds")
    val version by parser
        .option(ArgType.Boolean, fullName = "version", description = "Print version and exit")
        .default(false)

    parser.parse(args)

    // ---- validation -------------------------------------------------------

    val httpMethod = parseMethod(methodName)

    if (dataText != null && dataFile != null) {
        failWithUsage("--data and --data-file are mutually exclusive")
    }
    if ((dataText != null || dataFile != null) && form.isNotEmpty()) {
        failWithUsage("--data/--data-file and --form are mutually exclusive")
    }
    if (json && form.isNotEmpty()) {
        failWithUsage("--json cannot be combined with --form")
    }
    if (user != null && bearer != null) {
        failWithUsage("--user and --bearer are mutually exclusive")
    }
    proxy?.let {
        if (!it.startsWith("http://")) {
            failWithUsage("--proxy must use the http:// scheme (HTTPS proxies are not supported by the curl engine)")
        }
    }

    val dataTextLocal = dataText
    val dataFileLocal = dataFile
    val data = when {
        dataFileLocal != null -> readFileBytes(dataFileLocal)
        dataTextLocal != null && dataTextLocal.startsWith("@") && dataTextLocal.length > 1 ->
            readFileBytes(dataTextLocal.substring(1))
        dataTextLocal != null -> dataTextLocal.encodeToByteArray()
        else -> null
    }
    if (json && data != null) {
        try {
            jsonValidator.parseToJsonElement(data.decodeToString())
        } catch (e: Exception) {
            failWithUsage("--data is not valid JSON but --json was requested: ${e.message}")
        }
    }

    val formFields = form.map { raw ->
        val index = raw.indexOf('=')
        if (index <= 0) {
            failWithUsage("Invalid form field '$raw': expected 'name=value' or 'name=@file'")
        }
        val name = raw.substring(0, index)
        val value = raw.substring(index + 1)
        if (value.startsWith("@") && value.length > 1) FormField(name, file = value.substring(1))
        else FormField(name, value = value)
    }

    return XhttpArgs(
        url = positionalUrl,
        method = httpMethod,
        parameters = parseKeyValuePairs(param, "parameter"),
        headers = parseHeaderPairs(header),
        pathVariables = parseKeyValuePairs(pathVariable, "path variable"),
        cookies = parseKeyValuePairs(cookie, "cookie"),
        formFields = formFields,
        data = data,
        allowRedirects = allowRedirects,
        prettyPrint = prettyPrint,
        verbose = verbose,
        includeHeaders = include,
        failOnError = failOnError,
        json = json,
        user = user,
        bearer = bearer,
        insecure = insecure,
        proxy = proxy,
        output = output,
        progress = progress,
        session = session,
        connectTimeoutSeconds = validateTimeout(connectTimeout, "connect-timeout"),
        socketTimeoutSeconds = validateTimeout(socketTimeout, "socket-timeout"),
        requestTimeoutSeconds = validateTimeout(requestTimeout, "request-timeout"),
        version = version,
    )
}

/** Normalizes and validates the HTTP method (must be a valid HTTP token; custom methods are allowed). */
private fun parseMethod(value: String): HttpMethod {
    val method = value.uppercase()
    if (method.isEmpty() || !methodToken.matches(method)) {
        failWithUsage("Invalid HTTP method '$value'")
    }
    return HttpMethod.parse(method)
}

private fun validateTimeout(value: Int?, name: String): Long? {
    if (value == null) return null
    if (value <= 0) {
        failWithUsage("--$name must be a positive number of seconds")
    }
    return value.toLong()
}
