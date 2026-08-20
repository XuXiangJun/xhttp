import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import org.xuxiangjun.xhttp.APP_VERSION
import org.xuxiangjun.xhttp.Ansi
import org.xuxiangjun.xhttp.CliOutcome
import org.xuxiangjun.xhttp.ExitCode
import org.xuxiangjun.xhttp.PROGRAM
import org.xuxiangjun.xhttp.Stderr
import org.xuxiangjun.xhttp.Stdout
import org.xuxiangjun.xhttp.XhttpArgs
import org.xuxiangjun.xhttp.XhttpException
import org.xuxiangjun.xhttp.applyPathVariables
import org.xuxiangjun.xhttp.colorize
import org.xuxiangjun.xhttp.createCookieStorage
import org.xuxiangjun.xhttp.createHttpClient
import org.xuxiangjun.xhttp.describeRequest
import org.xuxiangjun.xhttp.describeTransportFailure
import org.xuxiangjun.xhttp.executeExchange
import org.xuxiangjun.xhttp.expandWriteOut
import org.xuxiangjun.xhttp.fileSize
import org.xuxiangjun.xhttp.helpText
import org.xuxiangjun.xhttp.parseCliArgs
import org.xuxiangjun.xhttp.renderResponse
import org.xuxiangjun.xhttp.resolveOutputPath
import org.xuxiangjun.xhttp.resolveProxy
import org.xuxiangjun.xhttp.saveCookieStorage
import kotlin.system.exitProcess

/**
 * Set once the command line has been parsed and `--silent` turned out to be in effect. It has to
 * be a global because failures before and during parsing also go through [reportError].
 */
private var suppressErrors = false

fun main(argv: Array<String>) {
    val exitCode = try {
        run(argv.toList())
    } catch (e: XhttpException) {
        reportError(e)
        e.exitCode
    } catch (e: Exception) {
        reportError(XhttpException(e.message ?: e.toString()))
        ExitCode.UNKNOWN
    }
    // Everything is flushed here rather than at an `exitProcess` deep inside the request loop,
    // so buffered output can never be dropped on the way out.
    Stdout.flush()
    Stderr.flush()
    exitProcess(exitCode)
}

private fun run(argv: List<String>): Int = when (val outcome = parseCliArgs(argv)) {
    is CliOutcome.Help -> {
        Stdout.print(helpText())
        ExitCode.OK
    }
    is CliOutcome.Version -> {
        Stdout.println("$PROGRAM/$APP_VERSION")
        ExitCode.OK
    }
    is CliOutcome.Run -> {
        suppressErrors = !outcome.args.showError
        runRequests(outcome.args)
    }
}

private fun runRequests(args: XhttpArgs): Int = runBlocking {
    val targets = args.urls.map { applyPathVariables(it, args.pathVariables) }
    if (args.dryRun) {
        targets.forEachIndexed { index, url ->
            if (index > 0) Stdout.println()
            describeRequest(args, url)
        }
        return@runBlocking ExitCode.OK
    }

    val cookies = createCookieStorage(args.cookieStore)
    val clients = mutableMapOf<String?, HttpClient>()
    var worstExitCode = ExitCode.OK

    try {
        for (url in targets) {
            val status = transfer(args, url, clients, cookies)
            if (status != ExitCode.OK) worstExitCode = status
        }
    } finally {
        clients.values.forEach { it.close() }
        cookies.close()
        saveCookieStorage(args.cookieStore, cookies)
    }
    worstExitCode
}

private suspend fun transfer(
    args: XhttpArgs,
    url: String,
    clients: MutableMap<String?, HttpClient>,
    cookies: org.xuxiangjun.xhttp.PersistentCookiesStorage,
): Int {
    val proxy = resolveProxy(args, url)
    val client = clients.getOrPut(proxy) { createHttpClient(args, proxy, cookies) }
    val outputPath = resolveOutputPath(args, url)
    val effective = withResumeOffset(args, outputPath)

    return try {
        val result = executeExchange(client, effective, url) { response, _ ->
            renderResponse(effective, response, outputPath)
        }
        args.writeOut?.let { Stdout.print(expandWriteOut(it, result)) }
        Stdout.flush()
        if (args.failOnError && result.status >= 400) {
            reportError(
                XhttpException(
                    "The requested URL returned error: ${result.status}",
                    ExitCode.HTTP_RETURNED_ERROR,
                )
            )
            ExitCode.HTTP_RETURNED_ERROR
        } else {
            ExitCode.OK
        }
    } catch (e: Exception) {
        // One bad URL must not abandon the rest of the batch; the worst code is returned at the end.
        val failure = describeTransportFailure(e, hostOf(url))
        reportError(failure)
        failure.exitCode
    }
}

/** Adds the `Range` header that turns `-C -` into an actual resume. */
private fun withResumeOffset(args: XhttpArgs, outputPath: String?): XhttpArgs {
    if (!args.resume || outputPath == null) return args
    val offset = fileSize(outputPath) ?: 0L
    if (offset <= 0L) return args
    return args.copy(headers = args.headers + ("Range" to "bytes=$offset-"))
}

private fun hostOf(url: String): String? = try {
    Url(url).let { if (it.port == it.protocol.defaultPort) it.host else "${it.host}:${it.port}" }
} catch (_: Exception) {
    null
}

private fun reportError(e: XhttpException) {
    if (suppressErrors) return
    Stderr.println("${colorize("Error", Ansi.RED, Stderr.isTty)}: ${e.message}")
    e.hint?.let { Stderr.println(it) }
}
