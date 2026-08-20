package org.xuxiangjun.xhttp

/** A `login`/`password` pair from a netrc file. */
internal data class NetrcEntry(val login: String, val password: String)

/**
 * Looks up credentials for [host] in a netrc file, so tokens never have to appear on the
 * command line.
 */
internal fun netrcCredentials(host: String, file: String?): NetrcEntry? {
    val path = file ?: "${homeDirectory() ?: return null}/.netrc"
    val content = readTextFileOrNull(path) ?: return null
    return parseNetrc(content)[host] ?: parseNetrc(content)["default"]
}

/** Parses the whitespace-separated `machine`/`login`/`password` token stream of a netrc file. */
internal fun parseNetrc(content: String): Map<String, NetrcEntry> {
    val result = mutableMapOf<String, NetrcEntry>()
    val tokens = content.lineSequence()
        .map { it.substringBefore('#') }
        .flatMap { it.split(' ', '\t').asSequence() }
        .filter { it.isNotBlank() }
        .toList()

    var machine: String? = null
    var login: String? = null
    var password: String? = null

    fun flush() {
        val name = machine ?: return
        val user = login ?: return
        result[name] = NetrcEntry(user, password ?: "")
    }

    var index = 0
    while (index < tokens.size) {
        when (tokens[index]) {
            "machine" -> {
                flush()
                machine = tokens.getOrNull(index + 1)
                login = null
                password = null
                index += 2
            }
            "default" -> {
                flush()
                machine = "default"
                login = null
                password = null
                index += 1
            }
            "login" -> {
                login = tokens.getOrNull(index + 1)
                index += 2
            }
            "password" -> {
                password = tokens.getOrNull(index + 1)
                index += 2
            }
            else -> index += 1
        }
    }
    flush()
    return result
}
