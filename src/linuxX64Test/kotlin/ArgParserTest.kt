package org.xuxiangjun.xhttp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArgParserTest {
    private val spec = ArgSpec(
        listOf(
            Opt("verbose", 'v'),
            Opt("follow", 'L', negatable = true),
            Opt("output", 'o', metavar = "FILE"),
            Opt("header", 'H', metavar = "LINE", repeatable = true),
        )
    )

    @Test
    fun parsesLongOptionsWithASeparateValue() {
        val parsed = spec.parse(listOf("--output", "file.txt", "url"))
        assertEquals("file.txt", parsed.string("output"))
        assertEquals(listOf("url"), parsed.positionals)
    }

    @Test
    fun parsesLongOptionsWithAnAttachedValue() {
        assertEquals("file.txt", spec.parse(listOf("--output=file.txt")).string("output"))
    }

    @Test
    fun parsesAttachedShortValues() {
        assertEquals("-", spec.parse(listOf("-o-")).string("output"))
        assertEquals("f", spec.parse(listOf("-of")).string("output"))
    }

    @Test
    fun bundlesShortFlags() {
        val parsed = spec.parse(listOf("-vL"))
        assertTrue(parsed.flag("verbose"), "verbose")
        assertTrue(parsed.flag("follow"), "follow")
    }

    @Test
    fun negatesAFlagThatDefaultsToOn() {
        assertFalse(spec.parse(listOf("--no-follow")).flag("follow", default = true), "--no-follow")
        assertFalse(spec.parse(listOf("--follow=false")).flag("follow", default = true), "--follow=false")
        assertTrue(spec.parse(emptyList()).flag("follow", default = true), "default")
    }

    @Test
    fun accumulatesRepeatableOptionsAndOverwritesScalars() {
        val parsed = spec.parse(listOf("-H", "a: 1", "-H", "b: 2", "-o", "x", "-o", "y"))
        assertEquals(listOf("a: 1", "b: 2"), parsed.list("header"))
        assertEquals("y", parsed.string("output"))
    }

    @Test
    fun stopsParsingOptionsAfterDoubleDash() {
        assertEquals(listOf("-v"), spec.parse(listOf("--", "-v")).positionals)
    }

    @Test
    fun suggestsACloseMatchForAnUnknownOption() {
        val error = assertFailsWith<XhttpException> { spec.parse(listOf("--verbse")) }
        assertTrue(error.message!!.contains("--verbose"), "message suggests --verbose")
    }

    @Test
    fun reportsAMissingValue() {
        assertFailsWith<XhttpException> { spec.parse(listOf("--output")) }
    }

    @Test
    fun recordsTheOrderOfRepeatedOptions() {
        val parsed = spec.parse(listOf("-H", "a: 1", "-o", "f", "-H", "b: 2"))
        assertEquals(listOf("header" to "a: 1", "output" to "f", "header" to "b: 2"), parsed.sequence)
    }

    @Test
    fun rendersHelpWithEveryOption() {
        val help = spec.helpText("prog [options]", "desc", "footer")
        assertTrue(help.contains("-o, --output FILE"), "help shows the value name")
        assertTrue(help.contains("--follow/--no-follow"), "help shows the negated form")
    }
}
