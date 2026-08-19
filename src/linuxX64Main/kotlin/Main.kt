import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import org.xuxiangjun.xhttp.APP_VERSION
import org.xuxiangjun.xhttp.Stderr
import org.xuxiangjun.xhttp.XhttpException
import org.xuxiangjun.xhttp.createHttpClient
import org.xuxiangjun.xhttp.prepareRequestStatement
import org.xuxiangjun.xhttp.failWithUsage
import org.xuxiangjun.xhttp.parseCliArgs
import org.xuxiangjun.xhttp.writeResponse

/** curl-compatible exit code used with `--fail` for HTTP status >= 400. */
private const val FAIL_EXIT_CODE = 22

fun main(args: Array<String>) {
    try {
        val cli = parseCliArgs(args)
        if (cli.version) {
            println("xhttp / $APP_VERSION")
            exitProcess(0)
        }
        val resolved = cli.copy(url = cli.url ?: failWithUsage(
            "No URL provided: pass it as a positional argument, e.g. 'xhttp https://example.com'"
        ))

        runBlocking {
            createHttpClient(resolved).use { client ->
                val statement = prepareRequestStatement(client, resolved)
                if (resolved.output != null) {
                    // Stream the body: `statement.execute { }` keeps the connection open while the
                    // block runs, so -o writes chunks to disk as they arrive instead of buffering
                    // the whole body first.
                    statement.execute { response ->
                        writeResponse(resolved, response)
                        if (resolved.failOnError && response.status.value >= 400) {
                            exitProcess(FAIL_EXIT_CODE)
                        }
                    }
                } else {
                    val response = statement.execute()
                    writeResponse(resolved, response)
                    if (resolved.failOnError && response.status.value >= 400) {
                        exitProcess(FAIL_EXIT_CODE)
                    }
                }
            }
        }
    } catch (e: XhttpException) {
        Stderr.println("Error: ${e.message}")
        exitProcess(1)
    } catch (e: Exception) {
        Stderr.println("Error: ${e.message ?: e.toString()}")
        exitProcess(1)
    }
}
