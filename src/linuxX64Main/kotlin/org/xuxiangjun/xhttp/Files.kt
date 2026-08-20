package org.xuxiangjun.xhttp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.posix.fread
import platform.posix.stdin

/** Reads a whole file into memory. Only for inputs that are known to be small. */
internal fun readFileBytes(path: String): ByteArray = try {
    SystemFileSystem.source(Path(expandUserPath(path))).buffered().use { it.readByteArray() }
} catch (e: XhttpException) {
    throw e
} catch (e: Exception) {
    fail("Cannot read file '$path': ${e.message}", ExitCode.READ_ERROR)
}

/** Reads a whole text file, or returns null when it does not exist. */
internal fun readTextFileOrNull(path: String): String? {
    val resolved = Path(expandUserPath(path))
    if (!fileExists(resolved)) return null
    return try {
        SystemFileSystem.source(resolved).buffered().use { it.readByteArray() }.decodeToString()
    } catch (e: Exception) {
        fail("Cannot read file '$path': ${e.message}", ExitCode.READ_ERROR)
    }
}

internal fun writeTextFile(path: String, text: String) {
    val resolved = Path(expandUserPath(path))
    try {
        resolved.parent?.let { SystemFileSystem.createDirectories(it, mustCreate = false) }
        SystemFileSystem.sink(resolved).buffered().use { it.write(text.encodeToByteArray()) }
    } catch (e: Exception) {
        fail("Cannot write file '$path': ${e.message}", ExitCode.WRITE_ERROR)
    }
}

internal fun fileExists(path: Path): Boolean = SystemFileSystem.exists(path)

/** Size of an existing regular file, or null when it does not exist. */
internal fun fileSize(path: String): Long? =
    SystemFileSystem.metadataOrNull(Path(expandUserPath(path)))?.size

/** Opens a file for writing, optionally appending, creating parent directories on request. */
internal fun openSink(path: String, append: Boolean, createDirs: Boolean): Sink {
    val resolved = Path(expandUserPath(path))
    try {
        if (createDirs) {
            resolved.parent?.let { SystemFileSystem.createDirectories(it, mustCreate = false) }
        }
        return SystemFileSystem.sink(resolved, append = append).buffered()
    } catch (e: Exception) {
        val hint = if (!createDirs && resolved.parent?.let { fileExists(it) } == false) {
            "The parent directory does not exist; pass --create-dirs to create it."
        } else {
            null
        }
        fail("Cannot write file '$path': ${e.message}", ExitCode.WRITE_ERROR, hint)
    }
}

/** Reads all of stdin, used for `-d @-` and `-F name=@-`. */
@OptIn(ExperimentalForeignApi::class)
internal fun readStdinBytes(): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val read = buffer.usePinned { pinned ->
            fread(pinned.addressOf(0), 1.convert(), buffer.size.convert(), stdin).toInt()
        }
        if (read <= 0) break
        chunks += buffer.copyOf(read)
        total += read
    }
    val result = ByteArray(total)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(result, offset)
        offset += chunk.size
    }
    return result
}
