package org.xuxiangjun.xhttp

import io.ktor.http.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormattingTest {
    @Test
    fun formatsByteCounts() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KiB", formatBytes(1024))
        assertEquals("1.5 MiB", formatBytes(1024L * 1024 * 3 / 2))
        assertEquals("2.00 GiB", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun roundsDecimalsIncludingTheCarry() {
        assertEquals("10.0", formatDecimal(9.99, 1))
        assertEquals("0.10", formatDecimal(0.1, 2))
        assertEquals("-1.3", formatDecimal(-1.25, 1))
        assertEquals("0.0", formatDecimal(-0.0001, 1))
        assertEquals("3", formatDecimal(3.4, 0))
    }

    @Test
    fun formatsAClock() {
        assertEquals("00:09", formatClock(9.4))
        assertEquals("01:05", formatClock(65.0))
        assertEquals("1:00:00", formatClock(3600.0))
        assertEquals("--:--", formatClock(Double.NaN))
    }

    @Test
    fun formatsARate() {
        assertEquals("512 B/s", formatRate(512.0))
        assertEquals("1.0 KiB/s", formatRate(1024.0))
        assertEquals("--", formatRate(0.0))
    }

    @Test
    fun parsesARateLimit() {
        assertEquals(200L * 1024, parseRate("200K"))
        assertEquals(2L * 1024 * 1024, parseRate("2M"))
        assertEquals(500L, parseRate("500"))
        assertFailsWith<XhttpException> { parseRate("fast") }
    }

    @Test
    fun unescapesWriteOutFormats() {
        assertEquals("a\nb\tc\\d", unescape("a\\nb\\tc\\\\d"))
    }
}

class ContentTypeTest {
    @Test
    fun recognisesTextualTypes() {
        assertTrue(isTextualContentType(ContentType.parse("text/html")), "text/html")
        assertTrue(isTextualContentType(ContentType.parse("application/xml")), "application/xml")
        assertTrue(isTextualContentType(ContentType.parse("application/problem+json")), "problem+json")
        assertTrue(isTextualContentType(null), "unknown type")
        assertFalse(isTextualContentType(ContentType.parse("image/png")), "image/png")
        assertFalse(isTextualContentType(ContentType.parse("application/zip")), "application/zip")
    }

    @Test
    fun recognisesJson() {
        assertTrue(isJsonContentType(ContentType.parse("application/json; charset=utf-8")), "json")
        assertTrue(isJsonContentType(ContentType.parse("application/vnd.api+json")), "vendor json")
        assertFalse(isJsonContentType(ContentType.parse("text/plain")), "text/plain")
    }

    @Test
    fun guessesUploadContentTypes() {
        assertEquals("image/png", guessContentType("logo.png"))
        assertEquals("application/json", guessContentType("body.json"))
        assertEquals("application/octet-stream", guessContentType("data"))
    }
}

class JsonRenderingTest {
    private val document = parseJsonOrNull("""{"a":{"b":[10,20]},"list":[{"id":1},{"id":2}],"s":"hi"}""")!!

    @Test
    fun prettyPrintsWithIndentation() {
        assertEquals(
            "{\n  \"a\": 1\n}",
            renderJson(parseJsonOrNull("""{"a":1}""")!!, pretty = true, color = false),
        )
    }

    @Test
    fun printsCompactlyWhenPrettyIsOff() {
        assertEquals(
            """{"a":1}""",
            renderJson(parseJsonOrNull("""{"a":1}""")!!, pretty = false, color = false),
        )
    }

    @Test
    fun coloursKeysAndValues() {
        val rendered = renderJson(parseJsonOrNull("""{"a":1}""")!!, pretty = false, color = true)
        assertTrue(rendered.contains(Ansi.CYAN), "key colour")
        assertTrue(rendered.contains(Ansi.YELLOW), "number colour")
    }

    @Test
    fun selectsANestedField() {
        assertEquals("20", renderSelected(selectJson(document, ".a.b[1]").single(), false, false))
    }

    @Test
    fun selectsWithANegativeIndex() {
        assertEquals("20", renderSelected(selectJson(document, ".a.b[-1]").single(), false, false))
    }

    @Test
    fun fansOutOverAnArray() {
        val ids = selectJson(document, ".list[].id").map { renderSelected(it, false, false) }
        assertEquals(listOf("1", "2"), ids)
    }

    @Test
    fun returnsARawStringWithoutQuotes() {
        assertEquals("hi", renderSelected(selectJson(document, ".s").single(), false, false))
    }

    @Test
    fun yieldsNullForAMissingField() {
        assertEquals("null", renderSelected(selectJson(document, ".nope").single(), false, false))
    }

    @Test
    fun supportsQuotedKeys() {
        assertEquals("hi", renderSelected(selectJson(document, """["s"]""").single(), false, false))
    }

    @Test
    fun rejectsAMalformedPath() {
        assertFailsWith<XhttpException> { selectJson(document, ".a[0") }
    }

    @Test
    fun returnsNullForInvalidJson() {
        assertNull(parseJsonOrNull("not json"))
    }
}

class WriteOutTest {
    private val transfer = Transfer(
        status = 404,
        effectiveUrl = "https://example.com/x",
        redirects = 2,
        bytesDownloaded = 1234,
        timing = Timing(),
        contentType = "application/json",
        headerBytes = 88,
    )

    @Test
    fun expandsKnownVariables() {
        assertEquals(
            "404 https://example.com/x 2 1234 application/json",
            expandWriteOut(
                "%{http_code} %{url_effective} %{num_redirects} %{size_download} %{content_type}",
                transfer,
            ),
        )
    }

    @Test
    fun keepsLiteralPercentSignsAndText() {
        assertEquals("100% done", expandWriteOut("100%% done", transfer))
    }

    @Test
    fun rejectsAnUnknownVariable() {
        val error = assertFailsWith<XhttpException> { expandWriteOut("%{time_namelookup}", transfer) }
        assertTrue(error.message!!.contains("Supported"), "message lists the supported variables")
    }

    @Test
    fun emitsAJsonSummary() {
        assertTrue(
            expandWriteOut("%{json}", transfer).startsWith("""{"http_code":404"""),
            "json summary",
        )
    }
}

class PrintSpecTest {
    @Test
    fun parsesAPrintSpec() {
        val spec = PrintSpec.parse("hb")
        assertTrue(spec.responseHeaders && spec.responseBody, "response headers and body")
        assertFalse(spec.requestHeaders, "request headers")
    }

    @Test
    fun rejectsAnUnknownSelector() {
        assertFailsWith<XhttpException> { PrintSpec.parse("x") }
    }
}

class MiscTest {
    @Test
    fun encodesBasicCredentials() {
        assertEquals("Basic dXNlcjpwYXNz", basicAuthorization("user:pass"))
        assertEquals("Basic dXNlcjo=", basicAuthorization("user"))
    }

    @Test
    fun measuresEditDistance() {
        assertEquals(1, editDistance("--verbose", "--verbse"))
        assertEquals(0, editDistance("abc", "abc"))
        assertEquals(3, editDistance("", "abc"))
    }

    @Test
    fun colourizesOnlyWhenEnabled() {
        assertEquals("x", colorize("x", Ansi.RED, false))
        assertTrue(colorize("x", Ansi.RED, true).endsWith(Ansi.RESET), "reset suffix")
    }
}
