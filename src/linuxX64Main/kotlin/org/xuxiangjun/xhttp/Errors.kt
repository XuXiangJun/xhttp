package org.xuxiangjun.xhttp

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.stderr
import kotlin.system.exitProcess

/** An error caused by invalid user input or I/O failure; message is shown without a stack trace. */
internal class XhttpException(message: String) : Exception(message)

@OptIn(ExperimentalForeignApi::class)
internal object Stderr {
    fun println(message: String) {
        fprintf(stderr, "%s\n", message)
        fflush(stderr)
    }

    fun print(message: String) {
        fprintf(stderr, "%s", message)
        fflush(stderr)
    }
}

/** Prints [message] to stderr and terminates with a non-zero exit code, no stack trace. */
internal fun fail(message: String): Nothing {
    Stderr.println("Error: $message")
    exitProcess(1)
}

/** Like [fail], but appends a pointer to `--help`. */
internal fun failWithUsage(message: String): Nothing {
    Stderr.println("Error: $message")
    Stderr.println("Run 'xhttp --help' for usage.")
    exitProcess(1)
}
