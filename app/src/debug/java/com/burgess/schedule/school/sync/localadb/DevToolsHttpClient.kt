package com.burgess.schedule.school.sync.localadb

import io.github.muntashirakon.adb.AdbStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

internal data class DevToolsHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String
)

internal enum class DevToolsHttpFailure {
    RESPONSE_TOO_LARGE,
    MALFORMED_STATUS_LINE,
    MALFORMED_HEADERS,
    INVALID_CONTENT_LENGTH,
    INVALID_CHUNKED_BODY,
    PREMATURE_EOF,
    TIMEOUT,
    IO_ERROR
}

internal sealed interface DevToolsHttpParseResult {
    data object NeedMore : DevToolsHttpParseResult
    data class Complete(val response: DevToolsHttpResponse) : DevToolsHttpParseResult
    data class Failed(val failure: DevToolsHttpFailure) : DevToolsHttpParseResult
}

internal sealed interface DevToolsHttpReadResult {
    data class Success(val response: DevToolsHttpResponse) : DevToolsHttpReadResult
    data class Failed(val failure: DevToolsHttpFailure) : DevToolsHttpReadResult
}

internal fun buildDevToolsDiscoveryRequest(endpoint: DevToolsEndpoint): ByteArray =
    ("GET ${endpoint.path} HTTP/1.1\r\n" +
        "Host: localhost\r\n" +
        "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)

internal fun shouldTryLegacyDevToolsEndpoint(statusCode: Int): Boolean =
    statusCode == 404 || statusCode == 405 || statusCode == 501

internal fun parseDevToolsHttpResponse(
    bytes: ByteArray,
    endOfStream: Boolean,
    maxBodyBytes: Int = MAX_HTTP_BODY_BYTES
): DevToolsHttpParseResult {
    val headerEnd = findHeaderEnd(bytes)
    if (headerEnd == null) {
        return when {
            bytes.size > MAX_HTTP_HEADER_BYTES -> DevToolsHttpParseResult.Failed(DevToolsHttpFailure.RESPONSE_TOO_LARGE)
            endOfStream -> DevToolsHttpParseResult.Failed(DevToolsHttpFailure.PREMATURE_EOF)
            else -> DevToolsHttpParseResult.NeedMore
        }
    }
    if (headerEnd.bodyStart > MAX_HTTP_HEADER_BYTES) {
        return DevToolsHttpParseResult.Failed(DevToolsHttpFailure.RESPONSE_TOO_LARGE)
    }

    val headerText = String(bytes, 0, headerEnd.headerLength, StandardCharsets.ISO_8859_1)
    val lines = headerText.split('\n').map { it.trimEnd('\r') }
    val statusMatch = HTTP_STATUS_LINE.matchEntire(lines.firstOrNull().orEmpty())
        ?: return DevToolsHttpParseResult.Failed(DevToolsHttpFailure.MALFORMED_STATUS_LINE)
    val statusCode = statusMatch.groupValues[1].toIntOrNull()
        ?: return DevToolsHttpParseResult.Failed(DevToolsHttpFailure.MALFORMED_STATUS_LINE)

    val headers = linkedMapOf<String, String>()
    for (line in lines.drop(1)) {
        if (line.isBlank()) continue
        val colon = line.indexOf(':')
        if (colon <= 0) return DevToolsHttpParseResult.Failed(DevToolsHttpFailure.MALFORMED_HEADERS)
        val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
        val value = line.substring(colon + 1).trim()
        headers[name] = headers[name]?.let { "$it, $value" } ?: value
    }

    val bodyOffset = headerEnd.bodyStart
    val availableBodyBytes = bytes.size - bodyOffset
    if (availableBodyBytes < 0) return DevToolsHttpParseResult.Failed(DevToolsHttpFailure.MALFORMED_HEADERS)
    if (availableBodyBytes > maxBodyBytes) {
        return DevToolsHttpParseResult.Failed(DevToolsHttpFailure.RESPONSE_TOO_LARGE)
    }

    val transferEncoding = headers["transfer-encoding"].orEmpty()
    val isChunked = transferEncoding.split(',').any { it.trim().equals("chunked", ignoreCase = true) }
    val body = if (isChunked) {
        when (val decoded = decodeChunkedBody(bytes, bodyOffset, endOfStream, maxBodyBytes)) {
            ChunkDecodeResult.NeedMore -> return DevToolsHttpParseResult.NeedMore
            is ChunkDecodeResult.Failed -> return DevToolsHttpParseResult.Failed(decoded.failure)
            is ChunkDecodeResult.Complete -> decoded.body
        }
    } else {
        val lengthHeader = headers["content-length"]
        if (lengthHeader != null) {
            val contentLength = lengthHeader.toLongOrNull()
                ?.takeIf { it in 0..maxBodyBytes.toLong() }
                ?: return DevToolsHttpParseResult.Failed(DevToolsHttpFailure.INVALID_CONTENT_LENGTH)
            when {
                availableBodyBytes < contentLength -> {
                    if (endOfStream) return DevToolsHttpParseResult.Failed(DevToolsHttpFailure.PREMATURE_EOF)
                    return DevToolsHttpParseResult.NeedMore
                }
                else -> bytes.copyOfRange(bodyOffset, bodyOffset + contentLength.toInt())
            }
        } else {
            if (!endOfStream) return DevToolsHttpParseResult.NeedMore
            bytes.copyOfRange(bodyOffset, bytes.size)
        }
    }

    return DevToolsHttpParseResult.Complete(
        DevToolsHttpResponse(
            statusCode = statusCode,
            headers = headers,
            body = String(body, StandardCharsets.UTF_8)
        )
    )
}

internal suspend fun readDevToolsHttpResponse(
    stream: AdbStream,
    endpoint: DevToolsEndpoint,
    timeoutMs: Long = HTTP_READ_TIMEOUT_MS
): DevToolsHttpReadResult = withContext(Dispatchers.IO) {
    val input = stream.openInputStream()
    val output = stream.openOutputStream()
    try {
        output.write(buildDevToolsDiscoveryRequest(endpoint))
        output.flush()

        val response = withTimeoutOrNull(timeoutMs) {
            val accumulated = ByteArrayOutputStream()
            val chunk = ByteArray(HTTP_READ_CHUNK_BYTES)
            while (true) {
                val available = input.available()
                if (available > 0) {
                    val count = input.read(chunk, 0, minOf(available, chunk.size))
                    if (count < 0) {
                        when (val parsed = parseDevToolsHttpResponse(accumulated.toByteArray(), endOfStream = true)) {
                            is DevToolsHttpParseResult.Complete -> return@withTimeoutOrNull parsed.response
                            is DevToolsHttpParseResult.Failed -> throw DevToolsHttpReadException(parsed.failure)
                            DevToolsHttpParseResult.NeedMore -> throw DevToolsHttpReadException(DevToolsHttpFailure.PREMATURE_EOF)
                        }
                    }
                    if (accumulated.size() + count > MAX_HTTP_RESPONSE_BYTES) {
                        throw DevToolsHttpReadException(DevToolsHttpFailure.RESPONSE_TOO_LARGE)
                    }
                    accumulated.write(chunk, 0, count)
                    when (val parsed = parseDevToolsHttpResponse(accumulated.toByteArray(), endOfStream = false)) {
                        is DevToolsHttpParseResult.Complete -> return@withTimeoutOrNull parsed.response
                        is DevToolsHttpParseResult.Failed -> throw DevToolsHttpReadException(parsed.failure)
                        DevToolsHttpParseResult.NeedMore -> Unit
                    }
                } else if (stream.isClosed) {
                    when (val parsed = parseDevToolsHttpResponse(accumulated.toByteArray(), endOfStream = true)) {
                        is DevToolsHttpParseResult.Complete -> return@withTimeoutOrNull parsed.response
                        is DevToolsHttpParseResult.Failed -> throw DevToolsHttpReadException(parsed.failure)
                        DevToolsHttpParseResult.NeedMore -> throw DevToolsHttpReadException(DevToolsHttpFailure.PREMATURE_EOF)
                    }
                } else {
                    delay(HTTP_POLL_INTERVAL_MS)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        if (response == null) DevToolsHttpReadResult.Failed(DevToolsHttpFailure.TIMEOUT)
        else DevToolsHttpReadResult.Success(response)
    } catch (exception: DevToolsHttpReadException) {
        DevToolsHttpReadResult.Failed(exception.failure)
    } catch (exception: IOException) {
        DevToolsHttpReadResult.Failed(DevToolsHttpFailure.IO_ERROR)
    } finally {
        runCatching { stream.close() }
    }
}

private fun findHeaderEnd(bytes: ByteArray): HeaderEnd? {
    for (index in 0 until bytes.size) {
        if (index + 3 < bytes.size &&
            bytes[index] == '\r'.code.toByte() && bytes[index + 1] == '\n'.code.toByte() &&
            bytes[index + 2] == '\r'.code.toByte() && bytes[index + 3] == '\n'.code.toByte()
        ) return HeaderEnd(index, index + 4)
        if (index + 1 < bytes.size &&
            bytes[index] == '\n'.code.toByte() && bytes[index + 1] == '\n'.code.toByte()
        ) return HeaderEnd(index, index + 2)
    }
    return null
}

private fun decodeChunkedBody(
    bytes: ByteArray,
    bodyOffset: Int,
    endOfStream: Boolean,
    maxBodyBytes: Int
): ChunkDecodeResult {
    var cursor = bodyOffset
    val output = ByteArrayOutputStream()
    while (true) {
        val lineEnd = findLineEnd(bytes, cursor) ?: return if (endOfStream) {
            ChunkDecodeResult.Failed(DevToolsHttpFailure.PREMATURE_EOF)
        } else {
            ChunkDecodeResult.NeedMore
        }
        val sizeLine = String(bytes, cursor, lineEnd.contentEnd - cursor, StandardCharsets.US_ASCII)
            .substringBefore(';')
            .trim()
        val chunkSize = sizeLine.toLongOrNull(16)
            ?.takeIf { it >= 0 }
            ?: return ChunkDecodeResult.Failed(DevToolsHttpFailure.INVALID_CHUNKED_BODY)
        cursor = lineEnd.next
        if (chunkSize == 0L) {
            var trailerCursor = cursor
            while (true) {
                val trailerEnd = findLineEnd(bytes, trailerCursor) ?: return if (endOfStream) {
                    ChunkDecodeResult.Failed(DevToolsHttpFailure.PREMATURE_EOF)
                } else {
                    ChunkDecodeResult.NeedMore
                }
                if (trailerEnd.contentEnd == trailerCursor) return ChunkDecodeResult.Complete(output.toByteArray())
                trailerCursor = trailerEnd.next
            }
        }
        if (chunkSize > maxBodyBytes.toLong() || output.size().toLong() + chunkSize > maxBodyBytes) {
            return ChunkDecodeResult.Failed(DevToolsHttpFailure.RESPONSE_TOO_LARGE)
        }
        val payloadEnd = cursor.toLong() + chunkSize
        if (payloadEnd > bytes.size) return if (endOfStream) {
            ChunkDecodeResult.Failed(DevToolsHttpFailure.PREMATURE_EOF)
        } else {
            ChunkDecodeResult.NeedMore
        }
        output.write(bytes, cursor, chunkSize.toInt())
        cursor = when {
            cursor + chunkSize.toInt() + 1 < bytes.size &&
                bytes[cursor + chunkSize.toInt()] == '\r'.code.toByte() &&
                bytes[cursor + chunkSize.toInt() + 1] == '\n'.code.toByte() -> cursor + chunkSize.toInt() + 2
            cursor + chunkSize.toInt() < bytes.size &&
                bytes[cursor + chunkSize.toInt()] == '\n'.code.toByte() -> cursor + chunkSize.toInt() + 1
            cursor + chunkSize.toInt() == bytes.size -> return if (endOfStream) {
                ChunkDecodeResult.Failed(DevToolsHttpFailure.PREMATURE_EOF)
            } else {
                ChunkDecodeResult.NeedMore
            }
            cursor + chunkSize.toInt() + 1 == bytes.size &&
                bytes[cursor + chunkSize.toInt()] == '\r'.code.toByte() -> return if (endOfStream) {
                ChunkDecodeResult.Failed(DevToolsHttpFailure.PREMATURE_EOF)
            } else {
                ChunkDecodeResult.NeedMore
            }
            else -> return ChunkDecodeResult.Failed(DevToolsHttpFailure.INVALID_CHUNKED_BODY)
        }
    }
}

private fun findLineEnd(bytes: ByteArray, start: Int): LineEnd? {
    for (index in start until bytes.size) {
        if (bytes[index] == '\n'.code.toByte()) {
            val contentEnd = if (index > start && bytes[index - 1] == '\r'.code.toByte()) index - 1 else index
            return LineEnd(contentEnd, index + 1)
        }
    }
    return null
}

private data class HeaderEnd(val headerLength: Int, val bodyStart: Int)
private data class LineEnd(val contentEnd: Int, val next: Int)

private sealed interface ChunkDecodeResult {
    data object NeedMore : ChunkDecodeResult
    data class Complete(val body: ByteArray) : ChunkDecodeResult
    data class Failed(val failure: DevToolsHttpFailure) : ChunkDecodeResult
}

private class DevToolsHttpReadException(val failure: DevToolsHttpFailure) : IOException()

private val HTTP_STATUS_LINE = Regex("HTTP/1\\.[01] ([0-9]{3})(?: .*)?")
private const val MAX_HTTP_HEADER_BYTES = 16 * 1024
private const val MAX_HTTP_BODY_BYTES = 512 * 1024
private const val MAX_HTTP_RESPONSE_BYTES = MAX_HTTP_HEADER_BYTES + MAX_HTTP_BODY_BYTES
private const val HTTP_READ_TIMEOUT_MS = 4_000L
private const val HTTP_READ_CHUNK_BYTES = 4 * 1024
private const val HTTP_POLL_INTERVAL_MS = 20L
