package org.xuxiangjun.xhttp

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.ECHO
import platform.posix.FILE
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.TCSANOW
import platform.posix.fflush
import platform.posix.fgets
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.isatty
import platform.posix.stderr
import platform.posix.stdin
import platform.posix.stdout
import platform.posix.tcgetattr
import platform.posix.tcsetattr
import platform.posix.termios

/**
 * Byte-exact output stream.
 *
 * Response bodies must reach stdout unchanged: no charset round-trip, no added newline. That rules
 * out `kotlin.io.println`, so everything goes through `fwrite` on the raw file handle.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ByteSink(private val handle: CPointer<FILE>?, fileno: Int) {
    /** Whether this stream is attached to a terminal (drives progress, colors and binary guards). */
    val isTty: Boolean = isatty(fileno) != 0

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (length <= 0) return
        bytes.usePinned { pinned ->
            val written = fwrite(pinned.addressOf(offset), 1.convert(), length.convert(), handle)
            if (written.toLong() != length.toLong()) {
                throw XhttpException("Failed to write output: the stream was closed.", ExitCode.WRITE_ERROR)
            }
        }
    }

    fun print(text: String) = write(text.encodeToByteArray())

    fun println(text: String = "") {
        print(text)
        write(NEWLINE)
    }

    fun flush() {
        fflush(handle)
    }

    private companion object {
        val NEWLINE = byteArrayOf('\n'.code.toByte())
    }
}

@OptIn(ExperimentalForeignApi::class)
internal val Stdout = ByteSink(stdout, STDOUT_FILENO)

@OptIn(ExperimentalForeignApi::class)
internal val Stderr = ByteSink(stderr, STDERR_FILENO)

/** Reads an environment variable, treating an empty value as unset. */
@OptIn(ExperimentalForeignApi::class)
internal fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotEmpty() }

/** The current user's home directory, or null when it cannot be determined. */
internal fun homeDirectory(): String? = env("HOME")

/** Expands a leading `~/` in a path. */
internal fun expandUserPath(path: String): String {
    if (path == "~") return homeDirectory() ?: path
    if (!path.startsWith("~/")) return path
    val home = homeDirectory() ?: return path
    return home.trimEnd('/') + path.substring(1)
}

/**
 * Prompts on stderr and reads one line from the terminal with echo disabled.
 *
 * Used so that a password never has to appear in the command line, where it would be visible in
 * `ps` output and shell history.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun readPasswordFromTerminal(prompt: String): String {
    if (isatty(STDIN_FILENO) == 0) {
        fail("Cannot prompt for a password: stdin is not a terminal.", ExitCode.USAGE)
    }
    Stderr.print(prompt)
    val line = memScoped {
        val term = alloc<termios>()
        val restore = tcgetattr(STDIN_FILENO, term.ptr) == 0
        val previousFlags = term.c_lflag
        if (restore) {
            term.c_lflag = previousFlags and ECHO.inv().convert()
            tcsetattr(STDIN_FILENO, TCSANOW, term.ptr)
        }
        try {
            val buffer = allocArray<ByteVar>(MAX_PASSWORD_LENGTH)
            if (fgets(buffer, MAX_PASSWORD_LENGTH, stdin) == null) "" else buffer.toKString()
        } finally {
            if (restore) {
                term.c_lflag = previousFlags
                tcsetattr(STDIN_FILENO, TCSANOW, term.ptr)
            }
        }
    }
    Stderr.println()
    return line.trimEnd('\n', '\r')
}

private const val MAX_PASSWORD_LENGTH = 512
