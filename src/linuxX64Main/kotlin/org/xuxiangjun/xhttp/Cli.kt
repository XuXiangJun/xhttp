package org.xuxiangjun.xhttp

import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json

internal const val PROGRAM = "xhttp"

private val methodToken = Regex("""[!#$%&'*+.^_`|~0-9A-Za-z-]+""")
private val jsonValidator = Json

private const val REQUEST = "Request"
private const val BODY = "Request body"
private const val OUTPUT = "Output"
private const val CONNECTION = "Connection"
private const val AUTH = "Authentication and TLS"
private const val COOKIES = "Cookies"
private const val GENERAL = "General"

private val options = listOf(
    // ---- request ----------------------------------------------------------
    Opt("request", 'X', "METHOD", section = REQUEST, help = "HTTP method (default: GET, or POST with a body)"),
    Opt("header", 'H', "LINE", repeatable = true, section = REQUEST, help = "Header \"Name: value\", or \"Name;\" for an empty one (repeatable)"),
    Opt("param", 'P', "NAME=VALUE", repeatable = true, section = REQUEST, help = "Query parameter (repeatable)"),
    Opt("path-variable", 'V', "NAME=VALUE", repeatable = true, section = REQUEST, help = "Replace {name} in the URL path (repeatable)"),
    Opt("user-agent", 'A', "STRING", section = REQUEST, help = "User-Agent header (default: $PROGRAM/<version>)"),
    Opt("referer", 'e', "URL", section = REQUEST, help = "Referer header"),
    Opt("head", 'I', section = REQUEST, help = "Send HEAD and print only the response headers"),
    Opt("get", 'G', section = REQUEST, help = "Send the --data payload as a query string instead of a body"),

    // ---- body -------------------------------------------------------------
    Opt("data", 'd', "DATA", repeatable = true, section = BODY, help = "Request body; @file reads a file, @- reads stdin (repeatable, joined with &)"),
    Opt("data-raw", null, "DATA", repeatable = true, section = BODY, help = "Like --data but '@' is not special"),
    Opt("data-binary", null, "DATA", repeatable = true, section = BODY, help = "Like --data but files are sent verbatim and streamed"),
    Opt("data-urlencode", null, "DATA", repeatable = true, section = BODY, help = "URL-encode the value: [name=]value, [name]@file"),
    Opt("data-file", null, "FILE", repeatable = true, section = BODY, help = "Stream the request body from a file (alias of --data-binary @file)"),
    Opt("form", 'F', "NAME=VALUE", repeatable = true, section = BODY, help = "Multipart field; name=@file uploads (add ;type= / ;filename=), name=<file reads the value"),
    Opt("form-string", null, "NAME=VALUE", repeatable = true, section = BODY, help = "Multipart field whose value is always literal"),
    Opt("json", null, section = BODY, help = "Set Content-Type/Accept to application/json and validate the payload"),

    // ---- output -----------------------------------------------------------
    Opt("output", 'o', "FILE", section = OUTPUT, help = "Write the body to FILE ('-' means stdout)"),
    Opt("remote-name", 'O', section = OUTPUT, help = "Save to a file named after the remote path"),
    Opt("output-dir", null, "DIR", section = OUTPUT, help = "Directory for -o/-O output files"),
    Opt("create-dirs", null, section = OUTPUT, help = "Create missing directories for the output file"),
    Opt("continue-at", 'C', "OFFSET", section = OUTPUT, help = "Resume a download; '-' continues from the existing file size"),
    Opt("include", 'i', section = OUTPUT, help = "Print the response status line and headers before the body"),
    Opt("print", null, "SPEC", section = OUTPUT, help = "Select what goes to stdout: H=request headers, B=request body, h/b=response"),
    Opt("pretty", null, negatable = true, section = OUTPUT, help = "Pretty-print JSON bodies (default: on for a terminal)"),
    Opt("color", null, negatable = true, section = OUTPUT, help = "Colorize JSON and headers (default: on for a terminal)"),
    Opt("select", null, "PATH", section = OUTPUT, help = "Extract part of a JSON body, e.g. '.items[0].name' or '.items[].id'"),
    Opt("write-out", 'w', "FORMAT", section = OUTPUT, help = "Print a summary after the transfer, e.g. '%{http_code} %{time_total}\\n'"),
    Opt("silent", 's', section = OUTPUT, help = "No progress and no error messages"),
    Opt("show-error", 'S', section = OUTPUT, help = "Keep error messages even with --silent"),
    Opt("verbose", 'v', section = OUTPUT, help = "Print the request and response headers to stderr"),
    Opt("trace", null, section = OUTPUT, help = "Like --verbose, and also dump the request body"),
    Opt("fail", 'f', section = OUTPUT, help = "Suppress the body and exit 22 when the status is >= 400"),

    // ---- connection -------------------------------------------------------
    Opt("allow-redirects", 'L', negatable = true, section = CONNECTION, help = "Follow redirects (default: on; --no-allow-redirects turns it off)"),
    Opt("max-redirs", null, "N", section = CONNECTION, help = "Maximum number of redirects to follow (default: 20)"),
    Opt("connect-timeout", null, "SEC", section = CONNECTION, help = "Connection timeout in seconds"),
    Opt("socket-timeout", null, "SEC", section = CONNECTION, help = "Idle socket timeout in seconds"),
    Opt("request-timeout", null, "SEC", section = CONNECTION, help = "Overall request timeout in seconds"),
    Opt("max-time", 'm', "SEC", section = CONNECTION, help = "Alias of --request-timeout"),
    Opt("retry", null, "N", section = CONNECTION, help = "Retry transient failures and 5xx/429 up to N times"),
    Opt("retry-delay", null, "SEC", section = CONNECTION, help = "Fixed delay between retries (default: exponential back-off)"),
    Opt("limit-rate", null, "RATE", section = CONNECTION, help = "Cap the download speed, e.g. 200K, 2M"),
    Opt("proxy", 'x', "URL", section = CONNECTION, help = "Proxy URL: http://, https:// or socks5://host:port"),
    Opt("proxy-user", null, "USER:PASS", section = CONNECTION, help = "Proxy credentials"),
    Opt("noproxy", null, "LIST", section = CONNECTION, help = "Comma-separated hosts that bypass the proxy; '*' disables it entirely"),

    // ---- auth / TLS -------------------------------------------------------
    Opt("user", 'u', "USER[:PASS]", section = AUTH, help = "HTTP Basic credentials; the password is prompted for when omitted"),
    Opt("bearer", null, "TOKEN", section = AUTH, help = "Bearer token"),
    Opt("netrc", 'n', section = AUTH, help = "Take credentials from ~/.netrc"),
    Opt("netrc-file", null, "FILE", section = AUTH, help = "Use FILE instead of ~/.netrc"),
    Opt("insecure", 'k', section = AUTH, help = "Do not verify the server certificate (unsafe)"),
    Opt("cacert", null, "FILE", section = AUTH, help = "CA bundle used to verify the server"),
    Opt("capath", null, "DIR", section = AUTH, help = "Directory of CA certificates"),

    // ---- cookies ----------------------------------------------------------
    Opt("cookie", 'c', "NAME=VALUE", repeatable = true, section = COOKIES, help = "Send a cookie (repeatable); a file name loads a cookie jar"),
    Opt("cookie-file", 'b', "FILE", section = COOKIES, help = "Load cookies from a Netscape cookie file"),
    Opt("cookie-jar", null, "FILE", section = COOKIES, help = "Write the cookies back to FILE when the run ends"),
    Opt("session", null, "NAME", section = COOKIES, help = "Load and store cookies in ~/.config/$PROGRAM/sessions/NAME.txt"),

    // ---- general ----------------------------------------------------------
    Opt("config", null, "FILE", section = GENERAL, help = "Read default options from FILE (default: ~/.config/$PROGRAM/config)"),
    Opt("no-config", null, section = GENERAL, help = "Ignore the configuration file"),
    Opt("globoff", 'g', section = GENERAL, help = "Do not expand {a,b} and [1-9] in the URL"),
    Opt("dry-run", null, section = GENERAL, help = "Print the request that would be sent and exit"),
    Opt("version", null, section = GENERAL, help = "Print the version and exit"),
    Opt("help", 'h', section = GENERAL, help = "Print this help and exit"),
)

private val spec = ArgSpec(options)

private const val DESCRIPTION = """
A small, script-friendly HTTP client. Reads like curl, with JSON pretty-printing, path
variables and multipart forms built in.

The URL is a positional argument; several may be given.
"""

private const val FOOTER = """
Exit codes follow curl: 0 ok, 2 usage, 3 bad URL, 6 DNS, 7 connection refused, 22 --fail on
HTTP >= 400, 23 write error, 26 read error, 28 timeout, 35 TLS, 47 too many redirects,
60 certificate not trusted.

Environment: HTTP_PROXY, HTTPS_PROXY, ALL_PROXY, NO_PROXY, NO_COLOR, XHTTP_CONFIG.
"""

internal fun helpText(): String = spec.helpText("$PROGRAM [options] <URL>...", DESCRIPTION, FOOTER)

/** What the command line asked for. */
internal sealed interface CliOutcome {
    data object Help : CliOutcome
    data object Version : CliOutcome
    class Run(val args: XhttpArgs) : CliOutcome
}

private val dataOptions = setOf("data", "data-raw", "data-binary", "data-urlencode", "data-file")

internal fun parseCliArgs(argv: List<String>): CliOutcome {
    val tokens = configTokens(argv) + argv
    val parsed = spec.parse(tokens)

    if (parsed.flag("help")) return CliOutcome.Help
    if (parsed.flag("version")) return CliOutcome.Version

    val urls = resolveUrls(parsed)
    val formFields = parseFormFields(parsed)
    val dataParts = collectDataParts(parsed)

    if (dataParts.isNotEmpty() && formFields.isNotEmpty()) {
        failWithUsage("--data/--data-file and --form cannot be combined.")
    }
    if (parsed.flag("json") && formFields.isNotEmpty()) {
        failWithUsage("--json cannot be combined with --form.")
    }
    if (parsed.present("user") && parsed.present("bearer")) {
        failWithUsage("--user and --bearer are mutually exclusive.")
    }
    if (parsed.flag("get") && dataParts.isEmpty()) {
        failWithUsage("--get needs a --data payload to turn into a query string.")
    }

    val body = buildBody(dataParts, formFields)
    val json = parsed.flag("json")
    if (json && body is RequestBody.Raw) {
        try {
            jsonValidator.parseToJsonElement(body.bytes.decodeToString())
        } catch (e: Exception) {
            failWithUsage("--json was requested but the payload is not valid JSON: ${e.message}")
        }
    }

    val head = parsed.flag("head")
    val method = resolveMethod(parsed.string("request"), head, body)
    val print = resolvePrintSpec(parsed, head)
    val output = resolveOutput(parsed, urls)
    val interactive = Stdout.isTty

    return CliOutcome.Run(
        XhttpArgs(
            urls = urls,
            method = method,
            parameters = parseKeyValuePairs(parsed.list("param"), "parameter"),
            headers = buildHeaderList(parsed),
            pathVariables = parseKeyValuePairs(parsed.list("path-variable"), "path variable"),
            cookies = parseCookieArguments(parsed.list("cookie")),
            body = body,
            bodyAsQuery = parsed.flag("get"),
            defaultContentType = defaultContentType(json, body),
            allowRedirects = parsed.flag("allow-redirects", default = true),
            maxRedirects = parsed.int("max-redirs")?.also {
                if (it < 0) failWithUsage("--max-redirs cannot be negative.")
            } ?: 20,
            prettyPrint = parsed.flag("pretty", default = interactive),
            color = resolveColor(parsed, interactive),
            select = parsed.string("select"),
            verbose = parsed.flag("verbose") || parsed.flag("trace"),
            trace = parsed.flag("trace"),
            print = print,
            silent = parsed.flag("silent"),
            showError = parsed.flag("show-error") || !parsed.flag("silent"),
            failOnError = parsed.flag("fail"),
            json = json,
            user = resolveUser(parsed.string("user")),
            bearer = parsed.string("bearer"),
            netrc = parsed.flag("netrc") || parsed.present("netrc-file"),
            netrcFile = parsed.string("netrc-file"),
            insecure = parsed.flag("insecure"),
            caCert = parsed.string("cacert"),
            caPath = parsed.string("capath"),
            proxy = parsed.string("proxy"),
            proxyUser = parsed.string("proxy-user"),
            noProxy = parsed.string("noproxy"),
            output = output.file,
            outputDir = parsed.string("output-dir"),
            remoteName = parsed.flag("remote-name"),
            createDirs = parsed.flag("create-dirs"),
            resume = output.resume,
            cookieStore = resolveCookieStore(parsed),
            connectTimeoutSeconds = positiveSeconds(parsed.int("connect-timeout"), "connect-timeout"),
            socketTimeoutSeconds = positiveSeconds(parsed.int("socket-timeout"), "socket-timeout"),
            requestTimeoutSeconds = positiveSeconds(
                parsed.int("request-timeout") ?: parsed.int("max-time"), "request-timeout",
            ),
            retries = parsed.int("retry")?.also {
                if (it < 0) failWithUsage("--retry cannot be negative.")
            } ?: 0,
            retryDelaySeconds = parsed.string("retry-delay")?.toDoubleOrNull()?.also {
                if (it < 0) failWithUsage("--retry-delay cannot be negative.")
            } ?: -1.0,
            limitRateBytesPerSecond = parsed.string("limit-rate")?.let(::parseRate),
            writeOut = parsed.string("write-out")?.let(::unescape),
            dryRun = parsed.flag("dry-run"),
        )
    )
}

/** `-H` headers plus the dedicated `-A` / `-e` shortcuts. */
private fun buildHeaderList(parsed: ParsedArgs): List<Pair<String, String>> {
    val headers = parseHeaderPairs(parsed.list("header")).toMutableList()
    parsed.string("user-agent")?.let { agent ->
        headers.removeAll { it.first.equals("User-Agent", ignoreCase = true) }
        headers += "User-Agent" to agent
    }
    parsed.string("referer")?.let { referer ->
        headers.removeAll { it.first.equals("Referer", ignoreCase = true) }
        headers += "Referer" to referer
    }
    return headers
}

// ---- URLs -----------------------------------------------------------------

private fun resolveUrls(parsed: ParsedArgs): List<String> {
    if (parsed.positionals.isEmpty()) {
        failWithUsage("No URL given: pass it as a positional argument, e.g. '$PROGRAM https://example.com'.")
    }
    val expanded = if (parsed.flag("globoff")) {
        parsed.positionals
    } else {
        parsed.positionals.flatMap { expandUrlGlobs(it) }
    }
    return expanded.map(::normalizeUrl)
}

// ---- body -----------------------------------------------------------------

private class DataPart(val bytes: ByteArray?, val streamPath: String?)

private fun collectDataParts(parsed: ParsedArgs): List<DataPart> =
    parsed.sequence.filter { (name, _) -> name in dataOptions }.map { (name, raw) -> dataPart(name, raw) }

private fun dataPart(option: String, raw: String): DataPart = when (option) {
    "data" -> when {
        raw.startsWith("@") && raw.length > 1 -> DataPart(stripNewlines(readDataSource(raw.substring(1))), null)
        else -> DataPart(raw.encodeToByteArray(), null)
    }
    "data-raw" -> DataPart(raw.encodeToByteArray(), null)
    "data-binary" -> when {
        raw == "@-" -> DataPart(readStdinBytes(), null)
        raw.startsWith("@") && raw.length > 1 -> DataPart(null, raw.substring(1))
        else -> DataPart(raw.encodeToByteArray(), null)
    }
    "data-file" -> if (raw == "-") DataPart(readStdinBytes(), null) else DataPart(null, raw)
    "data-urlencode" -> DataPart(urlEncodedData(raw).encodeToByteArray(), null)
    else -> failWithUsage("Unsupported data option '--$option'.")
}

private fun readDataSource(path: String): ByteArray =
    if (path == "-") readStdinBytes() else readFileBytes(path)

private fun stripNewlines(bytes: ByteArray): ByteArray =
    bytes.filter { it != '\n'.code.toByte() && it != '\r'.code.toByte() }.toByteArray()

/** `--data-urlencode` accepts `value`, `=value`, `name=value`, `@file` and `name@file`. */
private fun urlEncodedData(raw: String): String {
    val equals = raw.indexOf('=')
    val at = raw.indexOf('@')
    return when {
        equals == 0 -> raw.substring(1).encodeURLParameter()
        equals > 0 && (at < 0 || equals < at) ->
            raw.substring(0, equals) + "=" + raw.substring(equals + 1).encodeURLParameter()
        at == 0 -> readDataSource(raw.substring(1)).decodeToString().encodeURLParameter()
        at > 0 -> raw.substring(0, at) + "=" +
            readDataSource(raw.substring(at + 1)).decodeToString().encodeURLParameter()
        else -> raw.encodeURLParameter()
    }
}

private fun buildBody(parts: List<DataPart>, formFields: List<FormField>): RequestBody = when {
    formFields.isNotEmpty() -> RequestBody.Multipart(formFields)
    parts.isEmpty() -> RequestBody.None
    parts.size == 1 && parts[0].streamPath != null -> RequestBody.Streamed(parts[0].streamPath!!)
    else -> {
        val chunks = parts.map { it.bytes ?: readFileBytes(it.streamPath!!) }
        val separator = "&".encodeToByteArray()
        val total = chunks.sumOf { it.size } + separator.size * (chunks.size - 1)
        val joined = ByteArray(total)
        var offset = 0
        chunks.forEachIndexed { index, chunk ->
            if (index > 0) {
                separator.copyInto(joined, offset)
                offset += separator.size
            }
            chunk.copyInto(joined, offset)
            offset += chunk.size
        }
        RequestBody.Raw(joined)
    }
}

private fun defaultContentType(json: Boolean, body: RequestBody): String? = when {
    json -> "application/json"
    body is RequestBody.Raw -> "application/x-www-form-urlencoded"
    body is RequestBody.Streamed -> "application/octet-stream"
    else -> null
}

private fun parseFormFields(parsed: ParsedArgs): List<FormField> {
    val literal = parsed.list("form-string").map { raw ->
        val (name, value) = parseKeyValuePair(raw, "form field")
        FormField(name = name, value = value)
    }
    val regular = parsed.list("form").map { raw ->
        val (name, spec) = parseKeyValuePair(raw, "form field")
        when {
            spec.startsWith("@") && spec.length > 1 -> fileField(name, spec.substring(1))
            spec.startsWith("<") && spec.length > 1 ->
                FormField(name = name, value = readDataSource(spec.substring(1)).decodeToString())
            else -> FormField(name = name, value = spec)
        }
    }
    return literal + regular
}

private fun fileField(name: String, spec: String): FormField {
    val segments = spec.split(";")
    val path = segments.first()
    var contentType: String? = null
    var fileName: String? = null
    for (segment in segments.drop(1)) {
        val trimmed = segment.trim()
        when {
            trimmed.startsWith("type=") -> contentType = trimmed.removePrefix("type=")
            trimmed.startsWith("filename=") -> fileName = trimmed.removePrefix("filename=")
            trimmed.isEmpty() -> Unit
            else -> failWithUsage("Invalid form field '$name=@$spec': unknown attribute '$trimmed'.")
        }
    }
    if (fileSize(path) == null) {
        fail("Cannot read file '$path': no such file.", ExitCode.READ_ERROR)
    }
    return FormField(
        name = name,
        file = path,
        fileName = fileName ?: path.substringAfterLast('/'),
        contentType = contentType,
    )
}

// ---- misc resolution ------------------------------------------------------

private fun resolveMethod(requested: String?, head: Boolean, body: RequestBody): HttpMethod {
    if (head && requested == null) return HttpMethod.Head
    val name = (requested ?: if (body is RequestBody.None) "GET" else "POST").uppercase()
    if (name.isEmpty() || !methodToken.matches(name)) {
        failWithUsage("Invalid HTTP method '$requested'.")
    }
    return HttpMethod.parse(name)
}

private fun resolvePrintSpec(parsed: ParsedArgs, head: Boolean): PrintSpec {
    parsed.string("print")?.let { return PrintSpec.parse(it) }
    if (head) return PrintSpec(responseHeaders = true, responseBody = false)
    if (parsed.flag("include")) return PrintSpec(responseHeaders = true, responseBody = true)
    return PrintSpec()
}

private class OutputTarget(val file: String?, val resume: Boolean)

private fun resolveOutput(parsed: ParsedArgs, urls: List<String>): OutputTarget {
    val file = parsed.string("output")
    val remoteName = parsed.flag("remote-name")
    if (file != null && remoteName) {
        failWithUsage("--output and --remote-name are mutually exclusive.")
    }
    // `-` and /dev/null are the two sinks where "one file for many URLs" is what the user meant.
    if (file != null && file != "-" && file != "/dev/null" && urls.size > 1) {
        failWithUsage("--output writes a single file but ${urls.size} URLs were given; use -O or --output-dir.")
    }
    val resumeSpec = parsed.string("continue-at")
    if (resumeSpec != null) {
        if (resumeSpec != "-") {
            failWithUsage("--continue-at only supports '-' (continue from the current file size).")
        }
        if (file == null && !remoteName) {
            failWithUsage("--continue-at needs --output or --remote-name.")
        }
    }
    return OutputTarget(file, resumeSpec != null)
}

private fun resolveColor(parsed: ParsedArgs, interactive: Boolean): Boolean {
    if (parsed.present("color")) return parsed.flag("color")
    if (env("NO_COLOR") != null) return false
    if (env("TERM") == "dumb") return false
    return interactive
}

/** Prompts for the password when `-u user` is given without one, keeping it out of `ps` output. */
private fun resolveUser(user: String?): String? {
    if (user == null) return null
    if (user.contains(':')) return user
    val password = readPasswordFromTerminal("Enter host password for user '$user': ")
    return "$user:$password"
}

/** `-c` accepts both `name=value` pairs and, like curl's `-b`, a cookie file name. */
private fun parseCookieArguments(items: List<String>): List<Pair<String, String>> =
    items.filter { it.contains('=') }.map { parseKeyValuePair(it, "cookie") }

private fun cookieFileArguments(items: List<String>): List<String> = items.filterNot { it.contains('=') }

private fun resolveCookieStore(parsed: ParsedArgs): CookieStore {
    val session = parsed.string("session")
    if (session != null) {
        if (session.contains('/')) failWithUsage("--session takes a plain name, not a path.")
        val path = "${configHome()}/sessions/$session.txt"
        return CookieStore(loadFrom = path, saveTo = path)
    }
    val load = parsed.string("cookie-file") ?: cookieFileArguments(parsed.list("cookie")).lastOrNull()
    return CookieStore(loadFrom = load, saveTo = parsed.string("cookie-jar"))
}

private fun positiveSeconds(value: Int?, name: String): Long? {
    if (value == null) return null
    if (value <= 0) failWithUsage("--$name must be a positive number of seconds.")
    return value.toLong()
}

/** Parses `200`, `200K`, `2M`, `1G` into bytes per second. */
internal fun parseRate(raw: String): Long {
    val text = raw.trim()
    val multiplier = when (text.lastOrNull()?.uppercaseChar()) {
        'K' -> 1024L
        'M' -> 1024L * 1024L
        'G' -> 1024L * 1024L * 1024L
        else -> 1L
    }
    val digits = if (multiplier == 1L) text else text.dropLast(1)
    val value = digits.toLongOrNull()
        ?: failWithUsage("Invalid --limit-rate '$raw': expected a number optionally followed by K, M or G.")
    if (value <= 0) failWithUsage("--limit-rate must be positive.")
    return value * multiplier
}

/** Expands the `\n`, `\t` and `\\` escapes accepted in a `--write-out` format. */
internal fun unescape(text: String): String {
    if (!text.contains('\\')) return text
    val builder = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        if (char != '\\' || index >= text.length) {
            builder.append(char)
            continue
        }
        when (val escape = text[index++]) {
            'n' -> builder.append('\n')
            't' -> builder.append('\t')
            'r' -> builder.append('\r')
            '\\' -> builder.append('\\')
            else -> builder.append('\\').append(escape)
        }
    }
    return builder.toString()
}
