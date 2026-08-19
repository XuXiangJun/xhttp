package org.xuxiangjun.xhttp

import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Parses a list of `name=value` strings (query parameters, path variables, cookies, ...). */
internal fun parseKeyValuePairs(items: List<String>?, what: String): List<Pair<String, String>> {
    if (items.isNullOrEmpty()) return emptyList()
    return items.map { item ->
        val index = item.indexOf('=')
        if (index <= 0) {
            failWithUsage("Invalid $what '$item': expected 'name=value'")
        }
        item.substring(0, index) to item.substring(index + 1)
    }
}

/** Parses a list of header strings, accepting both `Name: value` and `Name:value`. */
internal fun parseHeaderPairs(items: List<String>?): List<Pair<String, String>> {
    if (items.isNullOrEmpty()) return emptyList()
    return items.map { raw ->
        val withSpace = raw.indexOf(": ")
        when {
            withSpace > 0 -> raw.substring(0, withSpace) to raw.substring(withSpace + 2)
            else -> {
                val index = raw.indexOf(':')
                if (index <= 0) {
                    failWithUsage("Invalid header '$raw': expected 'Name: value'")
                }
                raw.substring(0, index) to raw.substring(index + 1).trimStart(' ', '\t')
            }
        }
    }
}

/**
 * Replaces `{name}` placeholders in the URL path with the given values.
 *
 * The result is rebuilt through [URLBuilder], so path-variable values are properly percent-encoded
 * (a value containing `/`, `?` or spaces cannot corrupt or inject into the URL).
 */
internal fun applyPathVariables(url: String, pathVariables: List<Pair<String, String>>): String {
    if (pathVariables.isEmpty()) return url
    val builder = try {
        URLBuilder().takeFrom(url)
    } catch (e: Exception) {
        failWithUsage("Invalid URL '$url': ${e.message}")
    }
    builder.pathSegments = builder.pathSegments.map { segment ->
        var result = segment
        for ((name, value) in pathVariables) {
            result = result.replace("{$name}", value)
        }
        result
    }
    return builder.buildString()
}

/** Reads a whole file as bytes. Fails with a friendly message on any I/O error. */
internal fun readFileBytes(path: String): ByteArray {
    return try {
        SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }
    } catch (e: Exception) {
        failWithUsage("Cannot read file '$path': ${e.message}")
    }
}

/** Writes bytes to a file, creating/overwriting it. Throws [XhttpException] on failure. */
internal fun writeFileBytes(path: String, bytes: ByteArray) {
    try {
        SystemFileSystem.sink(Path(path)).buffered().use {
            it.write(bytes)
            it.flush()
        }
    } catch (e: Exception) {
        throw XhttpException("Cannot write file '$path': ${e.message}")
    }
}
