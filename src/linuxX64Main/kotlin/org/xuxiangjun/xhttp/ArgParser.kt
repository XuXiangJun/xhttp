package org.xuxiangjun.xhttp

/**
 * A single command-line option.
 *
 * @param metavar name of the option's value in the help text; `null` marks a boolean flag.
 * @param negatable whether `--no-<long>` is accepted to turn the flag off. Needed because a flag
 *   that defaults to on (`--allow-redirects`) is otherwise impossible to switch off.
 */
internal class Opt(
    val long: String,
    val short: Char? = null,
    val metavar: String? = null,
    val repeatable: Boolean = false,
    val negatable: Boolean = false,
    val section: String = "Options",
    val help: String = "",
) {
    val takesValue: Boolean get() = metavar != null
}

/** Options and positional arguments after parsing. */
internal class ParsedArgs(
    private val values: Map<String, List<String>>,
    val positionals: List<String>,
    /**
     * Every option occurrence as `long name to value`, in command-line order.
     *
     * The `--data*` family concatenates in the order the user wrote it, which the per-option
     * buckets above cannot express.
     */
    val sequence: List<Pair<String, String>> = emptyList(),
) {
    fun present(long: String): Boolean = values.containsKey(long)

    fun string(long: String): String? = values[long]?.lastOrNull()

    fun list(long: String): List<String> = values[long] ?: emptyList()

    fun flag(long: String, default: Boolean = false): Boolean =
        values[long]?.lastOrNull()?.let { it == "true" } ?: default

    fun int(long: String): Int? {
        val raw = string(long) ?: return null
        return raw.toIntOrNull() ?: failWithUsage("--$long expects a whole number, got '$raw'.")
    }

    fun long(long: String): Long? {
        val raw = string(long) ?: return null
        return raw.toLongOrNull() ?: failWithUsage("--$long expects a whole number, got '$raw'.")
    }
}

private val truthy = setOf("true", "yes", "on", "1")
private val falsy = setOf("false", "no", "off", "0")

/**
 * A tiny hand-written parser.
 *
 * It replaces kotlinx-cli, which is no longer maintained and could not express `--no-` negation,
 * `-o-` style attached values or a readable help screen.
 */
internal class ArgSpec(options: List<Opt>) {
    private val byLong = options.associateBy { it.long }
    private val byShort = options.mapNotNull { opt -> opt.short?.let { it to opt } }.toMap()
    private val ordered = options

    fun parse(args: List<String>): ParsedArgs {
        val values = LinkedHashMap<String, MutableList<String>>()
        val sequence = mutableListOf<Pair<String, String>>()
        val positionals = mutableListOf<String>()
        var index = 0
        var optionsEnded = false

        fun record(opt: Opt, value: String) {
            val slot = values.getOrPut(opt.long) { mutableListOf() }
            if (!opt.repeatable) slot.clear()
            slot += value
            sequence += opt.long to value
        }

        fun valueFor(opt: Opt, attached: String?, token: String): String {
            if (attached != null) return attached
            if (index >= args.size) failWithUsage("Option '$token' expects a value (${opt.metavar}).")
            return args[index++]
        }

        while (index < args.size) {
            val token = args[index++]
            when {
                optionsEnded -> positionals += token
                token == "--" -> optionsEnded = true
                token.startsWith("--") -> {
                    val body = token.substring(2)
                    val eq = body.indexOf('=')
                    val name = if (eq >= 0) body.substring(0, eq) else body
                    val attached = if (eq >= 0) body.substring(eq + 1) else null
                    val opt = byLong[name]
                    if (opt != null) {
                        if (opt.takesValue) {
                            record(opt, valueFor(opt, attached, token))
                        } else {
                            record(opt, booleanValue(attached, token))
                        }
                    } else {
                        val negated = name.removePrefix("no-")
                        val target = byLong[negated]
                        if (name.startsWith("no-") && target != null && target.negatable) {
                            if (attached != null) failWithUsage("Option '--$name' does not take a value.")
                            record(target, "false")
                        } else {
                            unknownOption("--$name")
                        }
                    }
                }
                token.length > 1 && token.startsWith("-") -> {
                    var position = 1
                    while (position < token.length) {
                        val letter = token[position]
                        val opt = byShort[letter] ?: unknownOption("-$letter")
                        position++
                        if (opt.takesValue) {
                            val rest = token.substring(position)
                            val attached = rest.takeIf { it.isNotEmpty() }?.removePrefix("=")
                            record(opt, valueFor(opt, attached, "-$letter"))
                            break
                        }
                        record(opt, "true")
                    }
                }
                else -> positionals += token
            }
        }
        return ParsedArgs(values, positionals, sequence)
    }

    private fun booleanValue(attached: String?, token: String): String = when {
        attached == null -> "true"
        attached.lowercase() in truthy -> "true"
        attached.lowercase() in falsy -> "false"
        else -> failWithUsage("Option '$token' expects true or false, got '$attached'.")
    }

    private fun unknownOption(name: String): Nothing {
        val candidates = ordered.flatMap { opt ->
            listOfNotNull("--${opt.long}", opt.short?.let { "-$it" })
        }
        val suggestion = candidates
            .map { it to editDistance(it.lowercase(), name.lowercase()) }
            .filter { (_, distance) -> distance <= 2 }
            .minByOrNull { (_, distance) -> distance }
            ?.first
        val hint = if (suggestion != null) " Did you mean '$suggestion'?" else ""
        failWithUsage("Unknown option '$name'.$hint")
    }

    /** Renders the aligned, section-grouped help screen. */
    fun helpText(usage: String, description: String, footer: String): String {
        val builder = StringBuilder()
        builder.append("Usage: ").append(usage).append("\n\n")
        builder.append(description.trim()).append("\n")
        val width = ordered.maxOf { leftColumn(it).length }.coerceAtMost(MAX_LEFT_COLUMN)
        for (section in ordered.map { it.section }.distinct()) {
            builder.append('\n').append(section).append(":\n")
            for (opt in ordered.filter { it.section == section }) {
                val left = leftColumn(opt)
                if (left.length > width) {
                    builder.append("  ").append(left).append('\n')
                    builder.append("  ").append(" ".repeat(width + 2)).append(opt.help).append('\n')
                } else {
                    builder.append("  ").append(left.padEnd(width + 2)).append(opt.help).append('\n')
                }
            }
        }
        builder.append('\n').append(footer.trim()).append('\n')
        return builder.toString()
    }

    private fun leftColumn(opt: Opt): String {
        val short = opt.short?.let { "-$it, " } ?: "    "
        val negation = if (opt.negatable) "/--no-${opt.long}" else ""
        val value = opt.metavar?.let { " $it" } ?: ""
        return "$short--${opt.long}$negation$value"
    }

    private companion object {
        const val MAX_LEFT_COLUMN = 34
    }
}
