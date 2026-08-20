package org.xuxiangjun.xhttp

import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.time

private const val TAB = '\t'

/** One line of a Netscape cookie file, the format curl and browsers share. */
internal data class StoredCookie(
    val domain: String,
    val includeSubdomains: Boolean,
    val path: String,
    val secure: Boolean,
    val expiresAtEpochSeconds: Long,
    val name: String,
    val value: String,
)

@OptIn(ExperimentalForeignApi::class)
internal fun currentEpochSeconds(): Long = time(null)

internal fun parseNetscapeCookies(content: String): List<StoredCookie> {
    val now = currentEpochSeconds()
    return content.lineSequence().mapNotNull { rawLine ->
        // `#HttpOnly_` is the conventional prefix for a http-only cookie; other `#` lines are comments.
        val line = rawLine.trim().removePrefix("#HttpOnly_")
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        val fields = line.split(TAB)
        if (fields.size < 7) return@mapNotNull null
        val expires = fields[4].toLongOrNull() ?: 0L
        if (expires in 1 until now) return@mapNotNull null
        StoredCookie(
            domain = fields[0],
            includeSubdomains = fields[1].equals("TRUE", ignoreCase = true),
            path = fields[2].ifEmpty { "/" },
            secure = fields[3].equals("TRUE", ignoreCase = true),
            expiresAtEpochSeconds = expires,
            name = fields[5],
            value = fields[6],
        )
    }.toList()
}

internal fun renderNetscapeCookies(cookies: List<StoredCookie>): String = buildString {
    appendLine("# Netscape HTTP Cookie File")
    appendLine("# Written by $PROGRAM; edits are lost on the next run.")
    appendLine()
    for (cookie in cookies) {
        append(cookie.domain).append(TAB)
        append(if (cookie.includeSubdomains) "TRUE" else "FALSE").append(TAB)
        append(cookie.path).append(TAB)
        append(if (cookie.secure) "TRUE" else "FALSE").append(TAB)
        append(cookie.expiresAtEpochSeconds).append(TAB)
        append(cookie.name).append(TAB)
        appendLine(cookie.value)
    }
}

/**
 * Cookie jar that can outlive the process.
 *
 * The matching rules stay with Ktor's [AcceptAllCookiesStorage]; this wrapper only records what
 * passes through so `--session` and `--cookie-jar` can write it back to disk.
 */
internal class PersistentCookiesStorage(
    private val delegate: CookiesStorage = AcceptAllCookiesStorage(),
) : CookiesStorage {
    private val tracked = LinkedHashMap<String, StoredCookie>()

    suspend fun load(cookies: List<StoredCookie>) {
        for (cookie in cookies) {
            val host = cookie.domain.trimStart('.')
            val scheme = if (cookie.secure) "https" else "http"
            val url = try {
                Url("$scheme://$host${cookie.path}")
            } catch (_: Exception) {
                continue
            }
            delegate.addCookie(
                url,
                Cookie(
                    name = cookie.name,
                    value = cookie.value,
                    encoding = CookieEncoding.RAW,
                    domain = cookie.domain,
                    path = cookie.path,
                    secure = cookie.secure,
                ),
            )
            remember(cookie)
        }
    }

    override suspend fun get(requestUrl: Url): List<Cookie> = delegate.get(requestUrl)

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        delegate.addCookie(requestUrl, cookie)
        val domain = cookie.domain?.takeIf { it.isNotBlank() } ?: requestUrl.host
        val maxAge: Int? = cookie.maxAge
        val expiresAt: Long? = cookie.expires?.timestamp
        val expires = when {
            expiresAt != null -> expiresAt / 1000L
            maxAge != null && maxAge > 0 -> currentEpochSeconds() + maxAge.toLong()
            else -> 0L
        }
        remember(
            StoredCookie(
                domain = domain,
                includeSubdomains = domain.startsWith("."),
                path = cookie.path?.takeIf { it.isNotBlank() } ?: "/",
                secure = cookie.secure,
                expiresAtEpochSeconds = expires,
                name = cookie.name,
                value = cookie.value,
            )
        )
    }

    override fun close() {
        delegate.close()
    }

    private fun remember(cookie: StoredCookie) {
        tracked["${cookie.domain} ${cookie.path} ${cookie.name}"] = cookie
    }

    /** Cookies worth writing back: session cookies (expiry 0) are dropped, like curl's jar. */
    fun snapshot(): List<StoredCookie> {
        val now = currentEpochSeconds()
        return tracked.values.filter { it.expiresAtEpochSeconds > now }
    }
}

/** Builds the storage for this run, pre-loaded from `--cookie-file` / `--session`. */
internal suspend fun createCookieStorage(store: CookieStore): PersistentCookiesStorage {
    val storage = PersistentCookiesStorage()
    store.loadFrom?.let { path ->
        readTextFileOrNull(path)?.let { storage.load(parseNetscapeCookies(it)) }
    }
    return storage
}

/** Writes the jar back when `--cookie-jar` or `--session` asked for it. */
internal fun saveCookieStorage(store: CookieStore, storage: PersistentCookiesStorage) {
    val target = store.saveTo ?: return
    writeTextFile(target, renderNetscapeCookies(storage.snapshot()))
}
