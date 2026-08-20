package org.xuxiangjun.xhttp

/** Base directory for the config file and saved sessions. */
internal fun configHome(): String {
    val xdg = env("XDG_CONFIG_HOME")
    if (xdg != null) return "$xdg/$PROGRAM"
    val home = homeDirectory() ?: fail("Cannot locate the home directory (\$HOME is unset).", ExitCode.USAGE)
    return "$home/.config/$PROGRAM"
}

/**
 * Reads default options from the config file and renders them as argv tokens.
 *
 * They are placed *before* the real command line, so an explicit option always wins while
 * repeatable ones (headers, cookies) accumulate.
 *
 * Format: one `option = value` (or bare `option`) per line, using the long option names without
 * the leading dashes. `#` starts a comment.
 */
internal fun configTokens(argv: List<String>): List<String> {
    if (argv.contains("--no-config")) return emptyList()
    val explicit = explicitConfigPath(argv)
    val path = explicit ?: env("XHTTP_CONFIG") ?: "${configHome()}/config"
    val content = readTextFileOrNull(path)
    if (content == null) {
        if (explicit != null) fail("Cannot read config file '$path': no such file.", ExitCode.READ_ERROR)
        return emptyList()
    }
    return parseConfig(content, path)
}

private fun explicitConfigPath(argv: List<String>): String? {
    for ((index, token) in argv.withIndex()) {
        if (token == "--config") return argv.getOrNull(index + 1)
        if (token.startsWith("--config=")) return token.removePrefix("--config=")
    }
    return null
}

internal fun parseConfig(content: String, path: String): List<String> {
    val tokens = mutableListOf<String>()
    for ((number, rawLine) in content.lineSequence().withIndex()) {
        val line = rawLine.substringBefore('#').trim()
        if (line.isEmpty()) continue
        val separator = line.indexOfFirst { it == '=' || it == ' ' || it == '\t' }
        val name = (if (separator < 0) line else line.substring(0, separator)).trim().removePrefix("--")
        if (name.isEmpty()) {
            fail("Invalid line ${number + 1} in '$path': $rawLine", ExitCode.USAGE)
        }
        tokens += "--$name"
        if (separator >= 0) {
            val value = line.substring(separator + 1).trim().removePrefix("=").trim().removeSurrounding("\"")
            if (value.isNotEmpty()) tokens += value
        }
    }
    return tokens
}
