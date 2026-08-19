package org.xuxiangjun.xhttp

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.curl.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Builds the HTTP client with all engine/plugin settings derived from the CLI arguments. */
internal fun createHttpClient(args: XhttpArgs): HttpClient = HttpClient(Curl) {
    followRedirects = args.allowRedirects
    expectSuccess = false

    engine {
        args.proxy?.let { proxy = ProxyBuilder.http(it) }
        sslVerify = !args.insecure
    }

    install(HttpTimeout) {
        requestTimeoutMillis = args.requestTimeoutSeconds?.let { it * 1000L }
        connectTimeoutMillis = args.connectTimeoutSeconds?.let { it * 1000L }
        socketTimeoutMillis = args.socketTimeoutSeconds?.let { it * 1000L }
    }

    if (args.session) {
        install(HttpCookies)
    }

    defaultRequest {
        headers.append(HttpHeaders.UserAgent, "xhttp/$APP_VERSION")
    }
}

/** Executes the request described by [args] and returns the raw response. */
internal suspend fun executeRequest(client: HttpClient, args: XhttpArgs): HttpResponse {
    val targetUrl = args.url ?: failWithUsage("No URL provided")
    val requestUrl = applyPathVariables(targetUrl, args.pathVariables)

    return client.request(requestUrl) {
        method = args.method
        for ((name, value) in args.parameters) {
            parameter(name, value)
        }
        for ((name, value) in args.headers) {
            header(name, value)
        }
        if (args.cookies.isNotEmpty()) {
            header(HttpHeaders.Cookie, args.cookies.joinToString("; ") { (name, value) -> "$name=$value" })
        }
        applyAuthentication(args)
        if (args.json) {
            applyJsonHeaders()
        }
        when {
            args.formFields.isNotEmpty() -> setBody(buildMultipart(args.formFields))
            args.data != null -> setBody(args.data)
        }
    }
}

private fun HttpRequestBuilder.applyAuthentication(args: XhttpArgs) {
    args.user?.let { header(HttpHeaders.Authorization, basicAuthorization(it)) }
    args.bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
}

private fun HttpRequestBuilder.applyJsonHeaders() {
    if (contentType() == null) {
        contentType(ContentType.Application.Json)
    }
    if (!headers.contains(HttpHeaders.Accept)) {
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun basicAuthorization(userColonPassword: String): String {
    val separator = userColonPassword.indexOf(':')
    val user = if (separator >= 0) userColonPassword.substring(0, separator) else userColonPassword
    val password = if (separator >= 0) userColonPassword.substring(separator + 1) else ""
    return "Basic " + Base64.encode("$user:$password".encodeToByteArray())
}

@OptIn(InternalAPI::class)
private fun buildMultipart(fields: List<FormField>): MultiPartFormDataContent {
    val parts = fields.map { field ->
        if (field.file != null) {
            val bytes = readFileBytes(field.file)
            val fileName = field.file.substringAfterLast("/")
            PartData.FileItem(
                { ByteReadChannel(bytes) },
                {},
                Headers.build {
                    append(HttpHeaders.ContentDisposition, "form-data; name=${quote(field.name)}; filename=${quote(fileName)}")
                    append(HttpHeaders.ContentType, "application/octet-stream")
                }
            )
        } else {
            PartData.FormItem(
                field.value ?: "",
                {},
                Headers.build {
                    append(HttpHeaders.ContentDisposition, "form-data; name=${quote(field.name)}")
                }
            )
        }
    }
    return MultiPartFormDataContent(parts)
}

private fun quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
