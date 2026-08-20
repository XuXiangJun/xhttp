package org.xuxiangjun.xhttp

import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private const val COPY_BUFFER = 64 * 1024

/**
 * A request body streamed straight from a file.
 *
 * Uploading used to read the whole file into memory first, which put a hard ceiling on the file
 * size; this keeps memory flat regardless of how big the file is.
 */
internal class FileBody(
    private val path: String,
    override val contentType: ContentType,
) : OutgoingContent.WriteChannelContent() {

    override val contentLength: Long? = fileSize(path)
        ?: fail("Cannot read file '$path': no such file.", ExitCode.READ_ERROR)

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val source = try {
            SystemFileSystem.source(Path(expandUserPath(path))).buffered()
        } catch (e: Exception) {
            fail("Cannot read file '$path': ${e.message}", ExitCode.READ_ERROR)
        }
        source.use {
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val read = it.readAtMostTo(buffer, 0, buffer.size)
                if (read <= 0) break
                channel.writeFully(buffer, 0, read)
            }
        }
        channel.flush()
    }
}

/** Builds a `multipart/form-data` body; file parts are streamed from disk. */
internal fun buildMultipart(fields: List<FormField>): MultiPartFormDataContent =
    MultiPartFormDataContent(
        formData {
            for (field in fields) {
                val file = field.file
                if (file == null) {
                    append(field.name, field.value ?: "")
                    continue
                }
                val size = fileSize(file)
                val name = field.fileName ?: file.substringAfterLast('/')
                append(
                    key = field.name,
                    value = InputProvider(size) {
                        SystemFileSystem.source(Path(expandUserPath(file))).buffered()
                    },
                    headers = Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"${escapeQuotes(name)}\"")
                        append(HttpHeaders.ContentType, field.contentType ?: guessContentType(name))
                    },
                )
            }
        }
    )

private fun escapeQuotes(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

private val contentTypesByExtension = mapOf(
    "css" to "text/css",
    "csv" to "text/csv",
    "gif" to "image/gif",
    "gz" to "application/gzip",
    "htm" to "text/html",
    "html" to "text/html",
    "jpeg" to "image/jpeg",
    "jpg" to "image/jpeg",
    "js" to "text/javascript",
    "json" to "application/json",
    "md" to "text/markdown",
    "mp4" to "video/mp4",
    "pdf" to "application/pdf",
    "png" to "image/png",
    "svg" to "image/svg+xml",
    "tar" to "application/x-tar",
    "txt" to "text/plain",
    "wav" to "audio/wav",
    "webp" to "image/webp",
    "xml" to "application/xml",
    "yaml" to "application/yaml",
    "yml" to "application/yaml",
    "zip" to "application/zip",
)

/** Guesses a part's content type from its extension, defaulting to `application/octet-stream`. */
internal fun guessContentType(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return contentTypesByExtension[extension] ?: "application/octet-stream"
}
