import io.ktor.client.*
import io.ktor.client.engine.curl.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import kotlinx.cli.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val VERSION = "0.9.1"

@OptIn(ExperimentalCli::class)
fun main(args: Array<String>) {
    val parser = ArgParser("xhttp")
    val url by parser.option(ArgType.String, shortName = "u", description = "HTTP URL")
    val method by parser.option(
        ArgType.String,
        shortName = "m",
        description = "HTTP method name: GET, POST, PUT, DELETE..."
    ).default("GET")
    val param by parser.option(ArgType.String, shortName = "P", description = "HTTP parameter: \"name=value\"")
        .multiple()
    val header by parser.option(ArgType.String, shortName = "H", description = "HTTP header: \"name: value\"")
        .multiple()
    val pathVariable by parser.option(ArgType.String, shortName = "V", description = "Path variable: \"name=value\"")
        .multiple()
    val prettyPrint by parser.option(ArgType.Boolean, shortName = "p", description = "Pretty print").default(false)
    val verbose by parser.option(ArgType.Boolean, shortName = "v", description = "Print verbose").default(false)
    val allowRedirects by parser.option(ArgType.Boolean, description = "Allow redirects").default(false)
    val version by parser.option(ArgType.Boolean, description = "Version").default(false)

    class HttpBody : Subcommand("body", "HTTP body") {
        val text by option(ArgType.String, shortName = "t", description = "HTTP text")
        val file by option(ArgType.String, shortName = "f", description = "HTTP file path")

        var data: ByteArray? = null

        override fun execute() {
            if (text != null && file != null) {
                throw Exception("You can only use one of the body text and file")
            }
            data = text?.toByteArray()
                ?: file?.let {
                    val path = Path(it)
                    SystemFileSystem.source(path).buffered().readByteArray()
                }

        }
    }
    val body = HttpBody()
    parser.subcommands(body)

    parser.parse(args)

    if (version) {
        println("xhttp version: $VERSION")
        return
    }

    if (url == null) {
        println("Error: Unknown url")
        return
    }

    val requestUrl = parsePathVariables(url!!, pathVariable)
    val httpMethod = HttpMethod.parse(method)
    val parameters = parseParameters(param)
    val headers = parseHeaders(header)
    val data = body.data

    runBlocking {
        HttpClient(Curl) {
            followRedirects = allowRedirects
        }.use { client ->
            val response = client.request(requestUrl) {
                this.method = httpMethod
                for (p in parameters) {
                    this.parameter(p.first, p.second)
                }
                for (h in headers) {
                    this.header(h.first, h.second)
                }
                if (data != null) {
                    this.setBody(data)
                }
            }

            if (verbose) {
                printHeaders(response)
            }
            if (prettyPrint) {
                prettyPrint(response)
            } else {
                println(response.bodyAsText())
            }
        }
    }
}

private fun parseParameters(params: List<String>?): List<Pair<String, String>> {
    if (params == null) {
        return emptyList()
    }

    val result = mutableListOf<Pair<String, String>>()
    for (param in params) {
        val index = param.indexOf('=')
        if (index <= 0) {
            throw Exception("Invalid param: $param")
        }
        val name = param.substring(0, index)
        val value = param.substring(index + 1)
        result.add(name to value)
    }
    return result
}

private fun parseHeaders(headers: List<String>?): List<Pair<String, String>> {
    if (headers == null) {
        return emptyList()
    }
    val result = mutableListOf<Pair<String, String>>()
    for (header in headers) {
        val index = header.indexOf(": ")
        if (index <= 0) {
            throw Exception("Invalid header: $header")
        }
        val name = header.substring(0, index)
        val value = header.substring(index + 2)
        result.add(name to value)
    }
    return result
}

private fun parsePathVariables(url: String, pathVariables: List<String>?): String {
    if (pathVariables.isNullOrEmpty()) {
        return url
    }

    var result = url
    for (pathVariable in pathVariables) {
        val index = pathVariable.indexOf('=')
        if (index <= 0) {
            throw Exception("Invalid path variable: $pathVariable")
        }
        val name = pathVariable.substring(0, index)
        val value = pathVariable.substring(index + 1)
        result = result.replace("{$name}", value)
    }
    return result
}

private fun printHeaders(response: HttpResponse) {
    println(response.status)
    response.headers.forEach { name, values ->
        if (values.size == 1) {
            println(name + ": " + values.first())
        } else {
            println("$name: $values")
        }
    }
    println()
}

private fun ContentType.equalsContentType(type: ContentType?): Boolean {
    return type != null && contentType == type.contentType && contentSubtype == type.contentSubtype
}

private suspend fun prettyPrint(response: HttpResponse) {
    val contentType = response.contentType()
    val bodyText = response.bodyAsText()
    if (ContentType.Application.Json.equalsContentType(contentType)) {
        prettyPrintJson(bodyText)
    } else {
        println(bodyText)
    }
}

private fun prettyPrintJson(text: String) {
    val json = Json {
        this.prettyPrint = true
    }
    val element = json.parseToJsonElement(text)
    println(json.encodeToString(element))
}
