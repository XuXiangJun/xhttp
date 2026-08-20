package org.xuxiangjun.xhttp

/** ANSI escapes, used only when the destination is a colour-capable terminal. */
internal object Ansi {
    const val RESET = "\u001b[0m"
    const val BOLD = "\u001b[1m"
    const val RED = "\u001b[31m"
    const val GREEN = "\u001b[32m"
    const val YELLOW = "\u001b[33m"
    const val MAGENTA = "\u001b[35m"
    const val CYAN = "\u001b[36m"
    const val GRAY = "\u001b[90m"
}

/** Wraps [text] in [code] when [enabled], otherwise returns it unchanged. */
internal fun colorize(text: String, code: String, enabled: Boolean): String =
    if (enabled) "$code$text${Ansi.RESET}" else text
