package org.xuxiangjun.xhttp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeaderParsingTest {
    @Test
    fun splitsOnTheFirstColon() {
        assertEquals(listOf("X-Trace" to "id: 42"), parseHeaderPairs(listOf("X-Trace: id: 42")))
    }

    @Test
    fun acceptsAMissingSpace() {
        assertEquals(listOf("Accept" to "*/*"), parseHeaderPairs(listOf("Accept:*/*")))
    }

    @Test
    fun trimsTheOptionalWhitespaceAroundAValue() {
        assertEquals(listOf("X" to "a"), parseHeaderPairs(listOf("X:   a ")))
    }

    @Test
    fun supportsTheCurlEmptyValueForm() {
        assertEquals(listOf("X-Empty" to ""), parseHeaderPairs(listOf("X-Empty;")))
    }

    @Test
    fun rejectsAHeaderWithoutAColon() {
        val error = assertFailsWith<XhttpException> { parseHeaderPairs(listOf("Accept")) }
        assertTrue(error.message!!.contains("Name: value"), "message mentions the expected form")
    }

    @Test
    fun rejectsAnIllegalHeaderName() {
        assertFailsWith<XhttpException> { parseHeaderPairs(listOf("Bad Name: v")) }
    }
}

class KeyValueParsingTest {
    @Test
    fun splitsOnTheFirstEqualsSign() {
        assertEquals(listOf("q" to "a=b"), parseKeyValuePairs(listOf("q=a=b"), "parameter"))
    }

    @Test
    fun allowsAnEmptyValue() {
        assertEquals(listOf("q" to ""), parseKeyValuePairs(listOf("q="), "parameter"))
    }

    @Test
    fun rejectsAMissingName() {
        assertFailsWith<XhttpException> { parseKeyValuePairs(listOf("=v"), "parameter") }
    }
}

class UrlTest {
    @Test
    fun rejectsAnEmptyUrl() {
        val error = assertFailsWith<XhttpException> { normalizeUrl("  ") }
        assertTrue(error.message!!.contains("Empty URL"), "message names the problem")
    }

    @Test
    fun addsTheMissingScheme() {
        assertEquals("http://example.com:8080/x", normalizeUrl("example.com:8080/x"))
    }

    @Test
    fun keepsAnExplicitScheme() {
        assertEquals("https://example.com", normalizeUrl("https://example.com"))
    }

    @Test
    fun rejectsANonHttpProtocol() {
        assertFailsWith<XhttpException> { normalizeUrl("ftp://example.com") }
    }

    @Test
    fun percentEncodesPathVariables() {
        assertEquals(
            "https://example.com/users/a%2Fb",
            applyPathVariables("https://example.com/users/{id}", listOf("id" to "a/b")),
        )
    }

    @Test
    fun reportsAnUnresolvedPathVariable() {
        val error = assertFailsWith<XhttpException> {
            applyPathVariables("https://example.com/users/{id}", listOf("other" to "1"))
        }
        assertTrue(error.message!!.contains("{id}"), "message names the placeholder")
    }

    @Test
    fun leavesAUrlWithoutVariablesAlone() {
        assertEquals("https://example.com/a/b", applyPathVariables("https://example.com/a/b", emptyList()))
    }
}

class UrlGlobTest {
    @Test
    fun expandsAlternatives() {
        assertEquals(listOf("http://h/a", "http://h/b"), expandUrlGlobs("http://h/{a,b}"))
    }

    @Test
    fun expandsNumericRanges() {
        assertEquals(listOf("http://h/1", "http://h/2", "http://h/3"), expandUrlGlobs("http://h/[1-3]"))
    }

    @Test
    fun honoursARangeStepAndZeroPadding() {
        assertEquals(listOf("h/01", "h/03"), expandUrlGlobs("h/[01-04:2]"))
    }

    @Test
    fun expandsLetterRanges() {
        assertEquals(listOf("h/a", "h/b"), expandUrlGlobs("h/[a-b]"))
    }

    @Test
    fun leavesPathVariablePlaceholdersAlone() {
        assertEquals(listOf("http://h/{id}"), expandUrlGlobs("http://h/{id}"))
    }

    @Test
    fun leavesIpv6HostsAlone() {
        assertEquals(listOf("http://[::1]:8080/x"), expandUrlGlobs("http://[::1]:8080/x"))
    }

    @Test
    fun combinesTwoGlobs() {
        assertEquals(listOf("a1", "a2", "b1", "b2"), expandUrlGlobs("{a,b}[1-2]"))
    }
}

class ConfigFileTest {
    @Test
    fun turnsConfigLinesIntoArgvTokens() {
        val tokens = parseConfig(
            """
            # defaults
            header = X-Api-Key: secret
            request-timeout = 30
            pretty
            """.trimIndent(),
            "config",
        )
        assertEquals(listOf("--header", "X-Api-Key: secret", "--request-timeout", "30", "--pretty"), tokens)
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        assertEquals(emptyList(), parseConfig("\n  # just a comment\n\n", "config"))
    }

    @Test
    fun acceptsLeadingDashesAndQuotes() {
        assertEquals(listOf("--user-agent", "my agent"), parseConfig("--user-agent = \"my agent\"", "config"))
    }
}

class NetrcTest {
    @Test
    fun parsesMachineEntries() {
        val entries = parseNetrc(
            """
            machine api.example.com login alice password s3cret
            machine other.example.com
              login bob
              password hunter2
            """.trimIndent()
        )
        assertEquals("alice", entries["api.example.com"]?.login)
        assertEquals("hunter2", entries["other.example.com"]?.password)
    }

    @Test
    fun parsesADefaultEntry() {
        assertEquals("anon", parseNetrc("default login anon password x")["default"]?.login)
    }

    @Test
    fun ignoresAnUnknownMachine() {
        assertNull(parseNetrc("machine a login b password c")["zzz"])
    }
}

class CookieJarTest {
    @Test
    fun roundTripsANetscapeCookieFile() {
        val cookies = listOf(StoredCookie("example.com", false, "/", true, 4102444800L, "sid", "abc"))
        assertEquals(cookies, parseNetscapeCookies(renderNetscapeCookies(cookies)))
    }

    @Test
    fun dropsExpiredCookiesWhileLoading() {
        val text = renderNetscapeCookies(
            listOf(StoredCookie("example.com", false, "/", false, 100L, "old", "x"))
        )
        assertEquals(0, parseNetscapeCookies(text).size)
    }

    @Test
    fun ignoresCommentsAndShortLines() {
        assertEquals(0, parseNetscapeCookies("# comment\nnot-a-cookie\n").size)
    }
}
