package org.xuxiangjun.xhttp

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom

private val schemePattern = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""")
private val placeholderPattern = Regex("""\{[A-Za-z0-9_.\-]+}""")

/**
 * Validates the URL and fills in what a user would expect to be able to omit.
 *
 * Without this, a blank argument silently became a request to `http://localhost`, and a
 * scheme-less `example.com:8080/x` failed with a raw parser error.
 */
internal fun normalizeUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        failWithUsage("Empty URL: pass a target such as 'https://example.com'.")
    }
    val withScheme = if (schemePattern.containsMatchIn(trimmed)) trimmed else "http://$trimmed"
    val url = try {
        URLBuilder().takeFrom(withScheme).build()
    } catch (e: Exception) {
        fail("Invalid URL '$raw': ${e.message}", ExitCode.URL_MALFORMED)
    }
    if (url.host.isEmpty()) {
        fail("Invalid URL '$raw': no host.", ExitCode.URL_MALFORMED)
    }
    val scheme = url.protocol.name.lowercase()
    if (scheme != "http" && scheme != "https") {
        fail(
            "Unsupported protocol '$scheme' in '$raw'.",
            ExitCode.UNSUPPORTED_PROTOCOL,
            "Only http:// and https:// are supported.",
        )
    }
    return url.toString()
}

/**
 * Replaces `{name}` placeholders in the URL path with the given values.
 *
 * The URL is rebuilt through [URLBuilder], so values are percent-encoded and cannot inject extra
 * path segments or query parameters. Placeholders left over after substitution are an error: they
 * used to be silently sent as `%7Bid%7D`.
 */
internal fun applyPathVariables(url: String, pathVariables: List<Pair<String, String>>): String {
    val builder = try {
        URLBuilder().takeFrom(url)
    } catch (e: Exception) {
        fail("Invalid URL '$url': ${e.message}", ExitCode.URL_MALFORMED)
    }
    if (pathVariables.isNotEmpty()) {
        builder.pathSegments = builder.pathSegments.map { segment ->
            var result = segment
            for ((name, value) in pathVariables) {
                result = result.replace("{$name}", value)
            }
            result
        }
    }
    val unresolved = builder.pathSegments.flatMap { placeholderPattern.findAll(it).map { m -> m.value } }
    if (unresolved.isNotEmpty()) {
        val names = unresolved.distinct().joinToString(", ")
        failWithUsage(
            "Unresolved path variable(s) $names in the URL. " +
                "Provide them with -V name=value, or percent-encode literal braces as %7B / %7D."
        )
    }
    return builder.buildString()
}

/** Resolves a possibly relative `Location` header against the URL that produced it. */
internal fun resolveRedirect(base: Url, location: String): String =
    URLBuilder(base).takeFrom(location.trim()).buildString()

private const val MAX_GLOB_EXPANSION = 10_000

/**
 * Expands curl-style globs into concrete URLs: `{a,b}` alternatives and `[1-9]`, `[1-9:2]`,
 * `[01-12]`, `[a-f]` ranges.
 *
 * A `{...}` group without a comma is left alone so it keeps working as a `-V` path-variable
 * placeholder, and `[::1]` style IPv6 hosts do not match the range syntax.
 */
internal fun expandUrlGlobs(spec: String): List<String> {
    val result = mutableListOf<String>()
    expandInto(spec, result)
    return result
}

private fun expandInto(spec: String, out: MutableList<String>) {
    if (out.size > MAX_GLOB_EXPANSION) {
        failWithUsage("URL glob expands to more than $MAX_GLOB_EXPANSION URLs; use -g to disable globbing.")
    }
    val alternatives = findAlternatives(spec)
    if (alternatives != null) {
        val (start, end, choices) = alternatives
        for (choice in choices) {
            expandInto(spec.substring(0, start) + choice + spec.substring(end + 1), out)
        }
        return
    }
    val range = findRange(spec)
    if (range != null) {
        val (start, end, values) = range
        for (value in values) {
            expandInto(spec.substring(0, start) + value + spec.substring(end + 1), out)
        }
        return
    }
    out += spec
}

private data class GlobMatch(val start: Int, val end: Int, val values: List<String>)

private fun findAlternatives(spec: String): GlobMatch? {
    val start = spec.indexOf('{')
    if (start < 0) return null
    val end = spec.indexOf('}', start + 1)
    if (end < 0) return null
    val body = spec.substring(start + 1, end)
    // No comma: this is a `-V` path-variable placeholder, not a glob.
    if (!body.contains(',')) return null
    return GlobMatch(start, end, body.split(','))
}

private val numericRange = Regex("""^(\d+)-(\d+)(?::(\d+))?$""")
private val alphaRange = Regex("""^([a-zA-Z])-([a-zA-Z])(?::(\d+))?$""")

private fun findRange(spec: String): GlobMatch? {
    var start = spec.indexOf('[')
    while (start >= 0) {
        val end = spec.indexOf(']', start + 1)
        if (end < 0) return null
        val body = spec.substring(start + 1, end)
        val values = rangeValues(body)
        if (values != null) return GlobMatch(start, end, values)
        start = spec.indexOf('[', start + 1)
    }
    return null
}

private fun rangeValues(body: String): List<String>? {
    numericRange.matchEntire(body)?.let { match ->
        val (fromText, toText, stepText) = match.destructured
        val from = fromText.toLongOrNull() ?: return null
        val to = toText.toLongOrNull() ?: return null
        val step = if (stepText.isEmpty()) 1L else stepText.toLongOrNull() ?: return null
        if (step <= 0 || from > to) failWithUsage("Invalid URL range '[$body]'.")
        val width = if (fromText.length > 1 && fromText.startsWith("0")) fromText.length else 0
        val values = mutableListOf<String>()
        var value = from
        while (value <= to) {
            values += if (width > 0) value.toString().padStart(width, '0') else value.toString()
            value += step
        }
        return values
    }
    alphaRange.matchEntire(body)?.let { match ->
        val (fromText, toText, stepText) = match.destructured
        val from = fromText[0]
        val to = toText[0]
        val step = if (stepText.isEmpty()) 1 else stepText.toIntOrNull() ?: return null
        if (step <= 0 || from > to) failWithUsage("Invalid URL range '[$body]'.")
        val values = mutableListOf<String>()
        var value = from
        while (value <= to) {
            values += value.toString()
            value += step
        }
        return values
    }
    return null
}
