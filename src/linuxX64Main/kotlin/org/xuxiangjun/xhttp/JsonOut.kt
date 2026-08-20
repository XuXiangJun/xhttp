package org.xuxiangjun.xhttp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val jsonParser = Json

/** Parses [text] as JSON, or returns null when it is not JSON at all. */
internal fun parseJsonOrNull(text: String): JsonElement? = try {
    jsonParser.parseToJsonElement(text)
} catch (_: Exception) {
    null
}

/**
 * Renders JSON with optional indentation and syntax colouring.
 *
 * Written by hand rather than using `Json { prettyPrint = true }` because the colours have to be
 * interleaved with the structure, and only a custom walk can do that.
 */
internal fun renderJson(element: JsonElement, pretty: Boolean, color: Boolean): String {
    if (!pretty && !color) return element.toString()
    val builder = StringBuilder()
    appendElement(builder, element, pretty, color, 0)
    return builder.toString()
}

private const val INDENT = "  "

private fun appendElement(out: StringBuilder, element: JsonElement, pretty: Boolean, color: Boolean, depth: Int) {
    when (element) {
        is JsonNull -> out.append(colorize("null", Ansi.MAGENTA, color))
        is JsonPrimitive -> out.append(renderPrimitive(element, color))
        is JsonArray -> appendArray(out, element, pretty, color, depth)
        is JsonObject -> appendObject(out, element, pretty, color, depth)
    }
}

private fun renderPrimitive(primitive: JsonPrimitive, color: Boolean): String {
    val text = primitive.toString()
    return when {
        primitive.isString -> colorize(text, Ansi.GREEN, color)
        text == "true" || text == "false" -> colorize(text, Ansi.MAGENTA, color)
        else -> colorize(text, Ansi.YELLOW, color)
    }
}

private fun appendArray(out: StringBuilder, array: JsonArray, pretty: Boolean, color: Boolean, depth: Int) {
    if (array.isEmpty()) {
        out.append(colorize("[]", Ansi.GRAY, color))
        return
    }
    out.append(colorize("[", Ansi.GRAY, color))
    array.forEachIndexed { index, item ->
        if (index > 0) out.append(colorize(",", Ansi.GRAY, color))
        newline(out, pretty, depth + 1)
        appendElement(out, item, pretty, color, depth + 1)
    }
    newline(out, pretty, depth)
    out.append(colorize("]", Ansi.GRAY, color))
}

private fun appendObject(out: StringBuilder, obj: JsonObject, pretty: Boolean, color: Boolean, depth: Int) {
    if (obj.isEmpty()) {
        out.append(colorize("{}", Ansi.GRAY, color))
        return
    }
    out.append(colorize("{", Ansi.GRAY, color))
    var first = true
    for ((key, value) in obj) {
        if (!first) out.append(colorize(",", Ansi.GRAY, color))
        first = false
        newline(out, pretty, depth + 1)
        out.append(colorize(JsonPrimitive(key).toString(), Ansi.CYAN, color))
        out.append(colorize(":", Ansi.GRAY, color))
        if (pretty) out.append(' ')
        appendElement(out, value, pretty, color, depth + 1)
    }
    newline(out, pretty, depth)
    out.append(colorize("}", Ansi.GRAY, color))
}

private fun newline(out: StringBuilder, pretty: Boolean, depth: Int) {
    if (!pretty) return
    out.append('\n')
    repeat(depth) { out.append(INDENT) }
}

// ---- --select -------------------------------------------------------------

/**
 * Evaluates a small JSONPath-like expression: `.a.b`, `["a b"]`, `[0]`, `.items[]`.
 *
 * A missing key or an out-of-range index yields `null`, the way jq does, so scripts do not have to
 * special-case absent fields.
 */
internal fun selectJson(root: JsonElement, path: String): List<JsonElement> {
    var current = listOf(root)
    var index = 0
    val spec = path.trim()
    if (spec.isEmpty() || spec == ".") return current

    while (index < spec.length) {
        when (val char = spec[index]) {
            '.' -> {
                index++
                if (index < spec.length && spec[index] == '[') continue
                val start = index
                while (index < spec.length && spec[index] != '.' && spec[index] != '[') index++
                val key = spec.substring(start, index)
                if (key.isEmpty()) failWithUsage("Invalid --select path '$path': empty field name.")
                current = current.map { field(it, key, path) }
            }
            '[' -> {
                val end = spec.indexOf(']', index)
                if (end < 0) failWithUsage("Invalid --select path '$path': unbalanced '['.")
                val body = spec.substring(index + 1, end).trim()
                index = end + 1
                current = when {
                    body.isEmpty() -> current.flatMap { elements(it, path) }
                    body.startsWith("\"") && body.endsWith("\"") && body.length >= 2 ->
                        current.map { field(it, body.substring(1, body.length - 1), path) }
                    else -> {
                        val position = body.toIntOrNull()
                            ?: failWithUsage("Invalid --select path '$path': '$body' is not an index.")
                        current.map { at(it, position, path) }
                    }
                }
            }
            else -> failWithUsage("Invalid --select path '$path': unexpected '$char'.")
        }
    }
    return current
}

private fun field(element: JsonElement, key: String, path: String): JsonElement = when (element) {
    is JsonObject -> element[key] ?: JsonNull
    is JsonNull -> JsonNull
    else -> failWithUsage("--select '$path': '.$key' expects an object.")
}

private fun at(element: JsonElement, index: Int, path: String): JsonElement = when (element) {
    is JsonArray -> {
        val position = if (index < 0) element.size + index else index
        element.getOrNull(position) ?: JsonNull
    }
    is JsonNull -> JsonNull
    else -> failWithUsage("--select '$path': '[$index]' expects an array.")
}

private fun elements(element: JsonElement, path: String): List<JsonElement> = when (element) {
    is JsonArray -> element.toList()
    is JsonObject -> element.values.toList()
    is JsonNull -> emptyList()
    else -> failWithUsage("--select '$path': '[]' expects an array or object.")
}

/** Renders one `--select` result: primitives raw so they can be used directly in a script. */
internal fun renderSelected(element: JsonElement, pretty: Boolean, color: Boolean): String = when {
    element is JsonNull -> "null"
    element is JsonPrimitive && element.isString -> element.content
    element is JsonPrimitive -> element.content
    else -> renderJson(element, pretty, color)
}
