package org.xuxiangjun.xhttp

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.parseQueryString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** Wall-clock measurements for `--write-out`. */
internal class Timing {
    private val start = TimeSource.Monotonic.markNow()
    var firstByteSeconds: Double = 0.0
        private set

    fun markFirstByte() {
        if (firstByteSeconds == 0.0) firstByteSeconds = elapsedSeconds()
    }

    fun elapsedSeconds(): Double = start.elapsedNow().toDouble(DurationUnit.SECONDS)
}

/** What happened during one URL's transfer. */
internal class Transfer(
    val status: Int,
    val effectiveUrl: String,
    val redirects: Int,
    val bytesDownloaded: Long,
    val timing: Timing,
    val contentType: String?,
    val headerBytes: Int,
)

// ---- client ---------------------------------------------------------------

/**
 * Builds the client for one proxy configuration.
 *
 * Redirects are handled by [executeExchange] instead of the engine so that `--max-redirs`,
 * `%{num_redirects}` and dropping credentials when the host changes all become possible.
 */
internal fun createHttpClient(args: XhttpArgs, proxyUrl: String?, cookies: PersistentCookiesStorage): HttpClient =
    HttpClient(Curl) {
        followRedirects = false
        expectSuccess = false

        engine {
            sslVerify = !args.insecure
            args.caCert?.let { caInfo = expandUserPath(it) }
            args.caPath?.let { caPath = expandUserPath(it) }
            proxyUrl?.let { proxy = buildProxy(it) }
        }

        install(HttpTimeout) {
            requestTimeoutMillis = args.requestTimeoutSeconds?.let { it * 1000L }
            connectTimeoutMillis = args.connectTimeoutSeconds?.let { it * 1000L }
            socketTimeoutMillis = args.socketTimeoutSeconds?.let { it * 1000L }
        }

        install(HttpCookies) {
            storage = cookies
        }

        if (args.retries > 0) {
            install(HttpRequestRetry) {
                maxRetries = args.retries
                retryIf(args.retries) { _, response ->
                    response.status.value >= 500 || response.status.value == 429
                }
                retryOnExceptionIf(args.retries) { _, cause -> cause !is XhttpException }
                if (args.retryDelaySeconds >= 0) {
                    constantDelay((args.retryDelaySeconds * 1000).toLong())
                } else {
                    exponentialDelay()
                }
            }
        }
    }

private fun buildProxy(spec: String) = try {
    val url = Url(spec)
    when (url.protocol.name.lowercase()) {
        "socks5", "socks5h", "socks4", "socks" -> ProxyBuilder.socks(url.host, url.port)
        "http", "https" -> ProxyBuilder.http(url)
        else -> failWithUsage("Unsupported proxy scheme '${url.protocol.name}' in '$spec'.")
    }
} catch (e: XhttpException) {
    throw e
} catch (e: Exception) {
    failWithUsage("Invalid proxy URL '$spec': ${e.message}")
}

/**
 * Picks the proxy for [target]: `--proxy` first, then the conventional environment variables,
 * honouring `NO_PROXY`.
 */
internal fun resolveProxy(args: XhttpArgs, target: String): String? {
    val url = Url(target)
    // --noproxy wins over an explicit --proxy, exactly as in curl.
    if (isProxyExempt(url.host, args.noProxy)) return null
    args.proxy?.let { return normalizeProxy(it) }
    val scheme = url.protocol.name.lowercase()
    val fromEnv = when (scheme) {
        "https" -> env("HTTPS_PROXY") ?: env("https_proxy")
        else -> env("HTTP_PROXY") ?: env("http_proxy")
    } ?: env("ALL_PROXY") ?: env("all_proxy")
    return fromEnv?.let { normalizeProxy(it) }
}

private fun normalizeProxy(spec: String): String =
    if (schemeless(spec)) "http://$spec" else spec

private fun schemeless(spec: String): Boolean = !spec.contains("://")

private fun isProxyExempt(host: String, explicit: String?): Boolean {
    val noProxy = explicit ?: env("NO_PROXY") ?: env("no_proxy") ?: return false
    if (noProxy.trim() == "*") return true
    return noProxy.split(',').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }
        .any { pattern -> host.equals(pattern, ignoreCase = true) || host.endsWith(".$pattern", ignoreCase = true) }
}

// ---- request --------------------------------------------------------------

private val redirectStatuses = setOf(301, 302, 303, 307, 308)

/**
 * Runs one URL to completion, following redirects itself, and hands the final response to
 * [onResponse].
 */
internal suspend fun executeExchange(
    client: HttpClient,
    args: XhttpArgs,
    startUrl: String,
    onResponse: suspend (HttpResponse, Timing) -> Long,
): Transfer {
    val timing = Timing()
    val origin = Url(startUrl)
    var currentUrl = startUrl
    var method = args.method
    var body = args.body
    var redirects = 0
    var first = true

    while (true) {
        val requestUrl = currentUrl
        val sameOrigin = Url(requestUrl).let { it.host == origin.host && it.protocol == origin.protocol }
        val statement = client.prepareRequest {
            buildRequest(args, requestUrl, method, body, sendAuth = sameOrigin, appendParameters = first)
        }

        var nextUrl: String? = null
        var redirectStatus = 0
        var transfer: Transfer? = null
        statement.execute { response ->
            timing.markFirstByte()
            val location = redirectTarget(response, args)
            if (location != null) {
                redirectStatus = response.status.value
                nextUrl = resolveRedirect(response.request.url, location)
                return@execute
            }
            val bytes = onResponse(response, timing)
            transfer = Transfer(
                status = response.status.value,
                effectiveUrl = response.request.url.toString(),
                redirects = redirects,
                bytesDownloaded = bytes,
                timing = timing,
                contentType = response.headers[HttpHeaders.ContentType],
                headerBytes = response.headers.entries().sumOf { (name, values) ->
                    values.sumOf { name.length + it.length + 4 }
                },
            )
        }

        val target = nextUrl ?: return transfer ?: fail("The response was lost before it could be read.")
        redirects++
        if (redirects > args.maxRedirects) {
            fail(
                "Stopped after $redirects redirects (--max-redirs ${args.maxRedirects}).",
                ExitCode.TOO_MANY_REDIRECTS,
            )
        }
        // 303 always downgrades to GET; 301/302 do too when the original request had a body,
        // which is what every browser and curl do in practice. 307/308 keep method and body.
        if (shouldDowngradeToGet(method, redirectStatus)) {
            method = HttpMethod.Get
            body = RequestBody.None
        }
        currentUrl = target
        first = false
    }
}

private fun redirectTarget(response: HttpResponse, args: XhttpArgs): String? {
    if (!args.allowRedirects) return null
    if (response.status.value !in redirectStatuses) return null
    val location = response.headers[HttpHeaders.Location]?.takeIf { it.isNotBlank() } ?: return null
    if (args.maxRedirects == 0) {
        fail("The server redirected but --max-redirs is 0.", ExitCode.TOO_MANY_REDIRECTS)
    }
    return location
}

private fun shouldDowngradeToGet(method: HttpMethod, redirectStatus: Int): Boolean = when (redirectStatus) {
    303 -> method != HttpMethod.Head
    301, 302 -> method == HttpMethod.Post
    else -> false
}

/** Fills in URL, headers, authentication and body for a single request. */
internal fun HttpRequestBuilder.buildRequest(
    args: XhttpArgs,
    targetUrl: String,
    requestMethod: HttpMethod,
    requestBody: RequestBody,
    sendAuth: Boolean,
    appendParameters: Boolean,
) {
    url(targetUrl)
    method = requestMethod

    if (appendParameters) {
        for ((name, value) in args.parameters) {
            parameter(name, value)
        }
        if (args.bodyAsQuery && requestBody is RequestBody.Raw) {
            appendQueryString(requestBody.bytes.decodeToString())
        }
    }

    header(HttpHeaders.UserAgent, "$PROGRAM/$APP_VERSION")
    val replaced = mutableSetOf<String>()
    for ((name, value) in args.headers) {
        // The first -H for a name replaces any default; repeats of the same name accumulate.
        if (replaced.add(name.lowercase())) headers.remove(name)
        headers.append(name, value)
    }
    if (args.cookies.isNotEmpty()) {
        header(HttpHeaders.Cookie, args.cookies.joinToString("; ") { (name, value) -> "$name=$value" })
    }
    if (args.json) {
        if (!headers.contains(HttpHeaders.Accept)) {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
    }
    if (sendAuth) {
        applyAuthentication(args, targetUrl)
    }
    args.proxyUser?.let { header(HttpHeaders.ProxyAuthorization, basicAuthorization(it)) }

    if (!args.bodyAsQuery) {
        applyBody(args, requestBody)
    }
}

private fun HttpRequestBuilder.appendQueryString(raw: String) {
    val parsed = parseQueryString(raw)
    for (name in parsed.names()) {
        for (value in parsed.getAll(name).orEmpty()) {
            url.parameters.append(name, value)
        }
    }
}

private fun HttpRequestBuilder.applyBody(args: XhttpArgs, requestBody: RequestBody) {
    val declared = args.headers.firstOrNull { it.first.equals(HttpHeaders.ContentType, ignoreCase = true) }?.second
    val contentType = declared ?: args.defaultContentType
    when (requestBody) {
        is RequestBody.None -> Unit
        is RequestBody.Raw -> {
            contentType?.let { header(HttpHeaders.ContentType, it) }
            setBody(requestBody.bytes)
        }
        is RequestBody.Streamed -> {
            val parsed = try {
                ContentType.parse(contentType ?: "application/octet-stream")
            } catch (_: Exception) {
                ContentType.Application.OctetStream
            }
            headers.remove(HttpHeaders.ContentType)
            setBody(FileBody(requestBody.path, parsed))
        }
        is RequestBody.Multipart -> setBody(buildMultipart(requestBody.fields))
    }
}

private fun HttpRequestBuilder.applyAuthentication(args: XhttpArgs, targetUrl: String) {
    val explicit = args.user?.let { basicAuthorization(it) }
        ?: args.bearer?.let { "Bearer $it" }
    if (explicit != null) {
        header(HttpHeaders.Authorization, explicit)
        return
    }
    if (!args.netrc) return
    val host = Url(targetUrl).host
    val entry = netrcCredentials(host, args.netrcFile) ?: return
    header(HttpHeaders.Authorization, basicAuthorization("${entry.login}:${entry.password}"))
}

@OptIn(ExperimentalEncodingApi::class)
internal fun basicAuthorization(userColonPassword: String): String {
    val separator = userColonPassword.indexOf(':')
    val user = if (separator >= 0) userColonPassword.substring(0, separator) else userColonPassword
    val password = if (separator >= 0) userColonPassword.substring(separator + 1) else ""
    return "Basic " + Base64.encode("$user:$password".encodeToByteArray())
}
