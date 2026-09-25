package com.burgess.schedule.school.sync.localadb

import io.github.muntashirakon.adb.AdbStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

internal enum class DevToolsCdpMethod(val protocolName: String) {
    PAGE_GET_FRAME_TREE("Page.getFrameTree"),
    PAGE_GET_NAVIGATION_HISTORY("Page.getNavigationHistory")
}

internal enum class DevToolsCdpFailure {
    INVALID_WEBSOCKET_URL,
    UNSUPPORTED_WEBSOCKET_SCHEME,
    HANDSHAKE_INVALID,
    CDP_HANDSHAKE_ACCEPTED,
    CDP_REJECTED_ORIGIN,
    CDP_REJECTED_HOST,
    CDP_REJECTED_OTHER,
    TIMEOUT,
    STREAM_CLOSED,
    IO_ERROR,
    INVALID_FRAME,
    MESSAGE_TOO_LARGE,
    INVALID_JSON,
    PROTOCOL_ERROR,
    RESPONSE_ID_MISMATCH,
    URL_MISSING
}

internal sealed interface DevToolsCdpConnectResult {
    data class Connected(
        val client: DevToolsCdpClient,
        val handshakeDiagnostic: DevToolsHandshakeDiagnostic
    ) : DevToolsCdpConnectResult

    data class Failed(
        val failure: DevToolsCdpFailure,
        val handshakeDiagnostic: DevToolsHandshakeDiagnostic? = null
    ) : DevToolsCdpConnectResult
}

internal data class DevToolsHandshakeRequestDiagnostic(
    val headerNames: List<String>,
    val hostHeaderValue: String?,
    val originHeaderSent: Boolean,
    val originHeaderValue: String?,
    val upgradeHeaderValue: String?,
    val connectionHeaderValue: String?,
    val webSocketVersionHeaderValue: String?
)

internal data class DevToolsHandshakeDiagnostic(
    val responseStatus: Int?,
    val responseReason: String?,
    val responseBodySummary: String,
    val request: DevToolsHandshakeRequestDiagnostic,
    val classification: DevToolsCdpFailure
)

internal sealed interface DevToolsCdpCommandResult {
    class Success(internal val result: JSONObject) : DevToolsCdpCommandResult {
        override fun toString(): String = "DevToolsCdpCommandResult.Success(result=<redacted>)"
    }

    data class Failed(val failure: DevToolsCdpFailure, val protocolCode: Int? = null) :
        DevToolsCdpCommandResult
}

internal sealed interface DevToolsCdpUrlResolution {
    data class Resolved(
        val url: SanitizedTargetUrl,
        val source: TargetUrlSource,
        val method: DevToolsCdpMethod
    ) : DevToolsCdpUrlResolution

    data class Failed(val failure: DevToolsCdpFailure) : DevToolsCdpUrlResolution
}

internal interface DevToolsCdpCommandSession : Closeable {
    suspend fun sendCommand(method: DevToolsCdpMethod): DevToolsCdpCommandResult
}

internal class DevToolsCdpClient private constructor(
    private val stream: AdbStream,
    private val input: InputStream,
    private val output: OutputStream,
    initialBytes: ByteArray
) : DevToolsCdpCommandSession {
    private val bufferedInput = CdpByteQueue().apply { append(initialBytes) }
    private val nextCommandId = AtomicInteger(1)

    override suspend fun sendCommand(method: DevToolsCdpMethod): DevToolsCdpCommandResult =
        withContext(Dispatchers.IO) {
            val commandId = nextCommandId.getAndIncrement()
            val message = buildCdpCommandPayload(commandId, method)
            try {
                sendClientFrame(OPCODE_TEXT, message)
                val response = withTimeoutOrNull(CDP_COMMAND_TIMEOUT_MS) {
                    awaitResponse(commandId)
                } ?: return@withContext DevToolsCdpCommandResult.Failed(DevToolsCdpFailure.TIMEOUT)
                response
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: CdpWireException) {
                DevToolsCdpCommandResult.Failed(exception.failure, exception.protocolCode)
            } catch (_: IOException) {
                DevToolsCdpCommandResult.Failed(DevToolsCdpFailure.IO_ERROR)
            } catch (_: JSONException) {
                DevToolsCdpCommandResult.Failed(DevToolsCdpFailure.INVALID_JSON)
            }
        }

    private suspend fun awaitResponse(commandId: Int): DevToolsCdpCommandResult {
        while (true) {
            val message = readTextMessage()
            val json = try {
                JSONObject(message)
            } catch (_: JSONException) {
                throw CdpWireException(DevToolsCdpFailure.INVALID_JSON)
            } catch (_: RuntimeException) {
                throw CdpWireException(DevToolsCdpFailure.INVALID_JSON)
            }

            val responseId = json.opt("id") as? Number ?: continue
            if (responseId.toInt() != commandId) continue
            val error = json.optJSONObject("error")
            if (error != null) {
                throw CdpWireException(
                    failure = DevToolsCdpFailure.PROTOCOL_ERROR,
                    protocolCode = error.optInt("code").takeIf { error.has("code") }
                )
            }
            val result = json.optJSONObject("result")
                ?: throw CdpWireException(DevToolsCdpFailure.INVALID_JSON)
            return DevToolsCdpCommandResult.Success(result)
        }
    }

    private suspend fun readTextMessage(): String {
        val fragments = ByteArrayOutputStream()
        var collectingFragments = false
        while (true) {
            val frame = nextFrame()
            when (frame.opcode) {
                OPCODE_CLOSE -> throw CdpWireException(DevToolsCdpFailure.STREAM_CLOSED)
                OPCODE_PING -> sendClientFrame(OPCODE_PONG, frame.payload)
                OPCODE_PONG -> Unit
                OPCODE_TEXT -> {
                    if (!frame.fin) {
                        fragments.reset()
                        fragments.write(frame.payload)
                        collectingFragments = true
                    } else {
                        return decodeMessage(frame.payload)
                    }
                }
                OPCODE_CONTINUATION -> {
                    if (!collectingFragments) throw CdpWireException(DevToolsCdpFailure.INVALID_FRAME)
                    appendBounded(fragments, frame.payload)
                    if (frame.fin) {
                        collectingFragments = false
                        return decodeMessage(fragments.toByteArray())
                    }
                }
                else -> throw CdpWireException(DevToolsCdpFailure.INVALID_FRAME)
            }
        }
    }

    private fun decodeMessage(payload: ByteArray): String {
        if (payload.size > MAX_CDP_MESSAGE_BYTES) {
            throw CdpWireException(DevToolsCdpFailure.MESSAGE_TOO_LARGE)
        }
        return payload.toString(StandardCharsets.UTF_8)
    }

    private suspend fun nextFrame(): CdpWebSocketFrame {
        while (true) {
            when (val parsed = parseCdpWebSocketFrame(bufferedInput.toByteArray())) {
                CdpWebSocketFrameParseResult.NeedMore -> Unit
                is CdpWebSocketFrameParseResult.Invalid ->
                    throw CdpWireException(parsed.failure)
                is CdpWebSocketFrameParseResult.Parsed -> {
                    bufferedInput.discard(parsed.consumedBytes)
                    return parsed.frame
                }
            }

            val available = try {
                input.available()
            } catch (_: IOException) {
                throw CdpWireException(DevToolsCdpFailure.IO_ERROR)
            }
            if (available > 0) {
                val chunk = ByteArray(minOf(available, CDP_READ_CHUNK_BYTES))
                val count = try {
                    input.read(chunk)
                } catch (_: IOException) {
                    throw CdpWireException(DevToolsCdpFailure.IO_ERROR)
                }
                if (count < 0) throw CdpWireException(DevToolsCdpFailure.STREAM_CLOSED)
                if (count > 0) bufferedInput.append(chunk.copyOf(count))
                if (bufferedInput.size > MAX_CDP_MESSAGE_BYTES + MAX_WEBSOCKET_HEADER_BYTES) {
                    throw CdpWireException(DevToolsCdpFailure.MESSAGE_TOO_LARGE)
                }
            } else if (stream.isClosed) {
                throw CdpWireException(DevToolsCdpFailure.STREAM_CLOSED)
            } else {
                delay(CDP_POLL_INTERVAL_MS)
            }
        }
    }

    private fun sendClientFrame(opcode: Int, payload: ByteArray) {
        if (payload.size > MAX_CDP_MESSAGE_BYTES) {
            throw CdpWireException(DevToolsCdpFailure.MESSAGE_TOO_LARGE)
        }
        val mask = ByteArray(4).also(SecureRandom()::nextBytes)
        val frame = ByteArrayOutputStream(payload.size + 14)
        frame.write(0x80 or opcode)
        when {
            payload.size < 126 -> frame.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                frame.write(0x80 or 126)
                frame.write((payload.size ushr 8) and 0xFF)
                frame.write(payload.size and 0xFF)
            }
            else -> {
                frame.write(0x80 or 127)
                repeat(4) { frame.write(0) }
                frame.write((payload.size ushr 24) and 0xFF)
                frame.write((payload.size ushr 16) and 0xFF)
                frame.write((payload.size ushr 8) and 0xFF)
                frame.write(payload.size and 0xFF)
            }
        }
        frame.write(mask)
        payload.forEachIndexed { index, byte -> frame.write(byte.toInt() xor mask[index % 4].toInt()) }
        output.write(frame.toByteArray())
        output.flush()
    }

    override fun close() {
        runCatching { sendClientFrame(OPCODE_CLOSE, ByteArray(0)) }
        runCatching { stream.close() }
    }

    override fun toString(): String = "DevToolsCdpClient(targetSocket=<redacted>)"

    internal companion object Connector {
        private fun appendBounded(output: ByteArrayOutputStream, bytes: ByteArray) {
            if (output.size() + bytes.size > MAX_CDP_MESSAGE_BYTES) {
                throw CdpWireException(DevToolsCdpFailure.MESSAGE_TOO_LARGE)
            }
            output.write(bytes)
        }

        suspend fun connect(
            stream: AdbStream,
            webSocketUrl: String,
            timeoutMs: Long = CDP_CONNECT_TIMEOUT_MS
        ): DevToolsCdpConnectResult = withContext(Dispatchers.IO) {
            val uri = parseWebSocketUri(webSocketUrl)
                ?: return@withContext closeAndFail(stream, DevToolsCdpFailure.INVALID_WEBSOCKET_URL)
            if (!uri.scheme.equals("ws", ignoreCase = true)) {
                return@withContext closeAndFail(stream, DevToolsCdpFailure.UNSUPPORTED_WEBSOCKET_SCHEME)
            }

            try {
                val input = stream.openInputStream()
                val output = stream.openOutputStream()
                val keyBytes = ByteArray(16).also(SecureRandom()::nextBytes)
                val key = encodeBase64(keyBytes)
                val requestDiagnostic = websocketHandshakeRequestDiagnostic(uri)
                val request = buildWebSocketHandshakeRequest(uri, key)
                output.write(request)
                output.flush()
                val handshake = withTimeoutOrNull(timeoutMs) {
                    readWebSocketHandshake(input, stream)
                } ?: return@withContext closeAndFail(stream, DevToolsCdpFailure.TIMEOUT)
                val responseBytes = handshake.headerBytes + handshake.remainingBytes
                when (val parsed = validateWebSocketHandshake(responseBytes, key, requestDiagnostic)) {
                    is HandshakeValidation.Accepted ->
                        DevToolsCdpConnectResult.Connected(
                            DevToolsCdpClient(
                                stream = stream,
                                input = input,
                                output = output,
                                initialBytes = handshake.remainingBytes
                            ),
                            parsed.diagnostic
                        )
                    is HandshakeValidation.Rejected ->
                        closeAndFail(stream, parsed.failure, parsed.diagnostic)
                }
            } catch (cancelled: CancellationException) {
                runCatching { stream.close() }
                throw cancelled
            } catch (exception: CdpWireException) {
                closeAndFail(stream, exception.failure)
            } catch (_: IOException) {
                closeAndFail(stream, DevToolsCdpFailure.IO_ERROR)
            } catch (_: IllegalArgumentException) {
                closeAndFail(stream, DevToolsCdpFailure.INVALID_WEBSOCKET_URL)
            }
        }

        private fun closeAndFail(
            stream: AdbStream,
            failure: DevToolsCdpFailure,
            handshakeDiagnostic: DevToolsHandshakeDiagnostic? = null
        ): DevToolsCdpConnectResult.Failed {
            runCatching { stream.close() }
            return DevToolsCdpConnectResult.Failed(failure, handshakeDiagnostic)
        }
    }
}

internal fun buildCdpCommandPayload(commandId: Int, method: DevToolsCdpMethod): ByteArray =
    JSONObject()
        .put("id", commandId)
        .put("method", method.protocolName)
        .put("params", JSONObject())
        .toString()
        .toByteArray(StandardCharsets.UTF_8)

internal fun extractFrameTreeUrl(result: JSONObject): SanitizedTargetUrl? {
    val frame = result.optJSONObject("frameTree")
        ?.optJSONObject("frame")
        ?: return null
    return sanitizeDevToolsTargetUrl(frame.optString("url", ""))
}

internal fun extractCurrentNavigationHistoryUrl(result: JSONObject): SanitizedTargetUrl? {
    val currentIndex = result.optInt("currentIndex", -1)
    val entries = result.optJSONArray("entries") ?: return null
    if (currentIndex !in 0 until entries.length()) return null
    val currentEntry = entries.optJSONObject(currentIndex) ?: return null
    return sanitizeDevToolsTargetUrl(currentEntry.optString("url", ""))
}

internal suspend fun resolveTargetUrlViaCdp(
    session: DevToolsCdpCommandSession,
    onState: (DevToolsDiscoveryState) -> Unit
): DevToolsCdpUrlResolution {
    val frameTreeResponse = session.sendCommand(DevToolsCdpMethod.PAGE_GET_FRAME_TREE)
    if (frameTreeResponse is DevToolsCdpCommandResult.Success) {
        val frameUrl = extractFrameTreeUrl(frameTreeResponse.result)
        if (frameUrl != null) {
            onState(DevToolsDiscoveryState.CDP_FRAME_TREE_SUCCESS)
            onState(DevToolsDiscoveryState.TARGET_URL_RESOLVED)
            return DevToolsCdpUrlResolution.Resolved(
                frameUrl,
                TargetUrlSource.CDP_PAGE_FRAME_TREE,
                DevToolsCdpMethod.PAGE_GET_FRAME_TREE
            )
        }
    }

    val historyResponse = session.sendCommand(DevToolsCdpMethod.PAGE_GET_NAVIGATION_HISTORY)
    if (historyResponse is DevToolsCdpCommandResult.Success) {
        val historyUrl = extractCurrentNavigationHistoryUrl(historyResponse.result)
        if (historyUrl != null) {
            onState(DevToolsDiscoveryState.CDP_NAVIGATION_HISTORY_SUCCESS)
            onState(DevToolsDiscoveryState.TARGET_URL_RESOLVED)
            return DevToolsCdpUrlResolution.Resolved(
                historyUrl,
                TargetUrlSource.CDP_NAVIGATION_HISTORY,
                DevToolsCdpMethod.PAGE_GET_NAVIGATION_HISTORY
            )
        }
    }
    val failure = when (val response = historyResponse) {
        is DevToolsCdpCommandResult.Failed -> response.failure
        is DevToolsCdpCommandResult.Success -> DevToolsCdpFailure.URL_MISSING
    }
    return DevToolsCdpUrlResolution.Failed(failure)
}

internal fun buildWebSocketHandshakeRequest(uri: URI, key: String): ByteArray {
    val path = uri.rawPath.orEmpty().ifBlank { "/" }
    val requestTarget = path + uri.rawQuery?.let { "?$it" }.orEmpty()
    require(requestTarget.none(Char::isISOControl))
    val authority = safeWebSocketAuthority(uri)
    require(authority.none(Char::isISOControl) && '@' !in authority)
    return (
        "GET $requestTarget HTTP/1.1\r\n" +
            "Host: $authority\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "Origin: http://localhost\r\n\r\n"
        ).toByteArray(StandardCharsets.US_ASCII)
}

internal fun websocketHandshakeRequestDiagnostic(uri: URI): DevToolsHandshakeRequestDiagnostic =
    DevToolsHandshakeRequestDiagnostic(
        headerNames = listOf(
            "Host",
            "Upgrade",
            "Connection",
            "Sec-WebSocket-Key",
            "Sec-WebSocket-Version",
            "Origin"
        ),
        hostHeaderValue = safeWebSocketAuthority(uri),
        originHeaderSent = true,
        originHeaderValue = "http://localhost",
        upgradeHeaderValue = "websocket",
        connectionHeaderValue = "Upgrade",
        webSocketVersionHeaderValue = "13"
    )

internal fun validateWebSocketHandshake(
    responseBytes: ByteArray,
    key: String,
    request: DevToolsHandshakeRequestDiagnostic = emptyHandshakeRequestDiagnostic()
): HandshakeValidation {
    val headerEnd = findHandshakeHeaderEnd(responseBytes)
        ?: return HandshakeValidation.Rejected(
            DevToolsCdpFailure.HANDSHAKE_INVALID,
            diagnostic = diagnostic(
                statusLine = "",
                bodyBytes = ByteArray(0),
                request = request,
                classification = DevToolsCdpFailure.CDP_REJECTED_OTHER
            )
        )
    val text = String(responseBytes, 0, headerEnd, StandardCharsets.ISO_8859_1)
    val lines = text.split("\r\n")
    val statusLine = lines.firstOrNull().orEmpty()
    val headers = lines.drop(1).mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) null else {
            line.substring(0, colon).trim().lowercase(Locale.ROOT) to line.substring(colon + 1).trim()
        }
    }.toMap()
    val bodyStart = (headerEnd).coerceAtMost(responseBytes.size)
    val bodyBytes = responseBytes.copyOfRange(bodyStart, responseBytes.size)
    val statusCode = HTTP_STATUS_PATTERN.find(statusLine)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val isSwitchingProtocols =
        statusLine.startsWith("HTTP/1.1 101 ") || statusLine == "HTTP/1.1 101"
    if (!isSwitchingProtocols) {
        val rejection = classifyHandshakeRejection(statusCode, bodyBytes)
        return HandshakeValidation.Rejected(
            rejection,
            diagnostic(statusLine, bodyBytes, request, rejection)
        )
    }

    val expectedAccept = encodeBase64(
        MessageDigest.getInstance("SHA-1")
            .digest((key + WEBSOCKET_GUID).toByteArray(StandardCharsets.US_ASCII))
    )
    val upgrade = headers["upgrade"].orEmpty()
    val connection = headers["connection"].orEmpty()
    val valid =
        upgrade.equals("websocket", ignoreCase = true) &&
            connection.split(',').any { it.trim().equals("upgrade", ignoreCase = true) } &&
            headers["sec-websocket-accept"] == expectedAccept
    return if (valid) {
        HandshakeValidation.Accepted(
            diagnostic(
                statusLine,
                bodyBytes,
                request,
                DevToolsCdpFailure.CDP_HANDSHAKE_ACCEPTED
            )
        )
    } else {
        HandshakeValidation.Rejected(
            DevToolsCdpFailure.HANDSHAKE_INVALID,
            diagnostic(
                statusLine,
                bodyBytes,
                request,
                DevToolsCdpFailure.CDP_REJECTED_OTHER
            )
        )
    }
}

private fun diagnostic(
    statusLine: String,
    bodyBytes: ByteArray,
    request: DevToolsHandshakeRequestDiagnostic,
    classification: DevToolsCdpFailure
): DevToolsHandshakeDiagnostic {
    val match = HTTP_STATUS_PATTERN.find(statusLine)
    val reason = match?.groupValues?.getOrNull(2)
        ?.takeIf(String::isNotBlank)
        ?.let(::sanitizeResponseReason)
    return DevToolsHandshakeDiagnostic(
        responseStatus = match?.groupValues?.getOrNull(1)?.toIntOrNull(),
        responseReason = reason,
        responseBodySummary = summarizeResponseBody(bodyBytes),
        request = request,
        classification = classification
    )
}

private fun classifyHandshakeRejection(statusCode: Int?, bodyBytes: ByteArray): DevToolsCdpFailure {
    val body = bodyBytes
        .take(MAX_HANDSHAKE_BODY_SUMMARY_BYTES)
        .toByteArray()
        .toString(StandardCharsets.UTF_8)
        .lowercase(Locale.ROOT)
    val rejectionWordPresent = listOf("reject", "denied", "disallow", "blocked", "forbidden", "not allowed")
        .any(body::contains)
    return when {
        body.contains("origin") && (rejectionWordPresent || statusCode == 403) ->
            DevToolsCdpFailure.CDP_REJECTED_ORIGIN
        body.contains("host") && (rejectionWordPresent || statusCode == 403 || statusCode == 400) ->
            DevToolsCdpFailure.CDP_REJECTED_HOST
        else -> DevToolsCdpFailure.CDP_REJECTED_OTHER
    }
}

private fun summarizeResponseBody(bodyBytes: ByteArray): String {
    if (bodyBytes.isEmpty()) return "空正文"
    val sample = bodyBytes.take(MAX_HANDSHAKE_BODY_SUMMARY_BYTES).toByteArray()
        .toString(StandardCharsets.UTF_8).lowercase(Locale.ROOT)
    val byteCount = bodyBytes.size
    return when {
        sample.contains("origin") && listOf("reject", "denied", "disallow", "blocked", "forbidden", "not allowed")
            .any(sample::contains) -> "正文提示 Origin 被拒绝 · ${byteCount}B"
        sample.contains("host") && listOf("reject", "denied", "disallow", "blocked", "forbidden", "not allowed")
            .any(sample::contains) -> "正文提示 Host 被拒绝 · ${byteCount}B"
        else -> "存在响应正文（内容已隐藏）· ${byteCount}B"
    }
}

private fun sanitizeResponseReason(value: String): String = RESPONSE_URL_PATTERN
    .replace(value) { "<URL>" }
    .substringBefore('?')
    .substringBefore('#')
    .filterNot(Char::isISOControl)
    .take(MAX_RESPONSE_REASON_CHARS)

private fun safeWebSocketAuthority(uri: URI): String =
    uri.rawAuthority?.takeIf(String::isNotBlank)
        ?: uri.host ?: throw IllegalArgumentException("Missing WebSocket authority")

private fun emptyHandshakeRequestDiagnostic() = DevToolsHandshakeRequestDiagnostic(
    headerNames = emptyList(),
    hostHeaderValue = null,
    originHeaderSent = false,
    originHeaderValue = null,
    upgradeHeaderValue = null,
    connectionHeaderValue = null,
    webSocketVersionHeaderValue = null
)

private fun encodeBase64(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val output = StringBuilder(((bytes.size + 2) / 3) * 4)
    var index = 0
    while (index < bytes.size) {
        val first = bytes[index].toInt() and 0xFF
        val hasSecond = index + 1 < bytes.size
        val hasThird = index + 2 < bytes.size
        val second = if (hasSecond) bytes[index + 1].toInt() and 0xFF else 0
        val third = if (hasThird) bytes[index + 2].toInt() and 0xFF else 0
        output.append(alphabet[first ushr 2])
        output.append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
        output.append(if (hasSecond) alphabet[((second and 0x0F) shl 2) or (third ushr 6)] else '=')
        output.append(if (hasThird) alphabet[third and 0x3F] else '=')
        index += 3
    }
    return output.toString()
}

internal sealed interface HandshakeValidation {
    data class Accepted(val diagnostic: DevToolsHandshakeDiagnostic) : HandshakeValidation
    data class Rejected(
        val failure: DevToolsCdpFailure,
        val diagnostic: DevToolsHandshakeDiagnostic
    ) : HandshakeValidation
}

internal sealed interface CdpWebSocketFrameParseResult {
    data object NeedMore : CdpWebSocketFrameParseResult
    data class Parsed(val frame: CdpWebSocketFrame, val consumedBytes: Int) : CdpWebSocketFrameParseResult
    data class Invalid(val failure: DevToolsCdpFailure) : CdpWebSocketFrameParseResult
}

internal data class CdpWebSocketFrame(
    val fin: Boolean,
    val opcode: Int,
    val payload: ByteArray
)

internal fun parseCdpWebSocketFrame(
    bytes: ByteArray,
    maxPayloadBytes: Int = MAX_CDP_MESSAGE_BYTES
): CdpWebSocketFrameParseResult {
    if (bytes.size < 2) return CdpWebSocketFrameParseResult.NeedMore
    val first = bytes[0].toInt() and 0xFF
    val second = bytes[1].toInt() and 0xFF
    val fin = first and 0x80 != 0
    if (first and 0x70 != 0) return CdpWebSocketFrameParseResult.Invalid(DevToolsCdpFailure.INVALID_FRAME)
    val opcode = first and 0x0F
    if (opcode !in setOf(OPCODE_CONTINUATION, OPCODE_TEXT, OPCODE_CLOSE, OPCODE_PING, OPCODE_PONG)) {
        return CdpWebSocketFrameParseResult.Invalid(DevToolsCdpFailure.INVALID_FRAME)
    }
    val isMasked = second and 0x80 != 0
    val lengthMarker = second and 0x7F
    var cursor = 2
    val payloadLength: Long = when (lengthMarker) {
        in 0..125 -> lengthMarker.toLong()
        126 -> {
            if (bytes.size < cursor + 2) return CdpWebSocketFrameParseResult.NeedMore
            val length = ((bytes[cursor].toInt() and 0xFF) shl 8) or (bytes[cursor + 1].toInt() and 0xFF)
            cursor += 2
            length.toLong()
        }
        else -> {
            if (bytes.size < cursor + 8) return CdpWebSocketFrameParseResult.NeedMore
            if (bytes[cursor].toInt() and 0x80 != 0) {
                return CdpWebSocketFrameParseResult.Invalid(DevToolsCdpFailure.MESSAGE_TOO_LARGE)
            }
            var length = 0L
            repeat(8) { index -> length = (length shl 8) or (bytes[cursor + index].toLong() and 0xFF) }
            cursor += 8
            length
        }
    }
    if (payloadLength > maxPayloadBytes || payloadLength > Int.MAX_VALUE) {
        return CdpWebSocketFrameParseResult.Invalid(DevToolsCdpFailure.MESSAGE_TOO_LARGE)
    }
    val mask = if (isMasked) {
        if (bytes.size < cursor + 4) return CdpWebSocketFrameParseResult.NeedMore
        bytes.copyOfRange(cursor, cursor + 4).also { cursor += 4 }
    } else {
        null
    }
    val end = cursor.toLong() + payloadLength
    if (end > bytes.size) return CdpWebSocketFrameParseResult.NeedMore
    val payload = bytes.copyOfRange(cursor, end.toInt())
    if (mask != null) payload.indices.forEach { index ->
        payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
    }
    if (opcode >= OPCODE_CLOSE && (!fin || payloadLength > 125)) {
        return CdpWebSocketFrameParseResult.Invalid(DevToolsCdpFailure.INVALID_FRAME)
    }
    return CdpWebSocketFrameParseResult.Parsed(
        CdpWebSocketFrame(fin, opcode, payload),
        end.toInt()
    )
}

internal fun frameTreeUrlFromMessage(result: JSONObject): SanitizedTargetUrl? = extractFrameTreeUrl(result)

internal fun navigationHistoryUrlFromMessage(result: JSONObject): SanitizedTargetUrl? =
    extractCurrentNavigationHistoryUrl(result)

private suspend fun readWebSocketHandshake(
    input: InputStream,
    stream: AdbStream
): WebSocketHandshakeBytes {
    val accumulated = ByteArrayOutputStream()
    val chunk = ByteArray(CDP_READ_CHUNK_BYTES)
    while (true) {
        val available = input.available()
        if (available > 0) {
            val count = input.read(chunk, 0, minOf(available, chunk.size))
            if (count < 0) throw CdpWireException(DevToolsCdpFailure.STREAM_CLOSED)
            accumulated.write(chunk, 0, count)
            val bytes = accumulated.toByteArray()
            val headerEnd = findHandshakeHeaderEnd(bytes)
            if (headerEnd != null) {
                if (headerEnd > MAX_WEBSOCKET_HEADER_BYTES) {
                    throw CdpWireException(DevToolsCdpFailure.MESSAGE_TOO_LARGE)
                }
                return WebSocketHandshakeBytes(
                    headerBytes = bytes.copyOfRange(0, headerEnd),
                    remainingBytes = bytes.copyOfRange(headerEnd, bytes.size)
                )
            }
            if (bytes.size > MAX_WEBSOCKET_HEADER_BYTES) {
                throw CdpWireException(DevToolsCdpFailure.MESSAGE_TOO_LARGE)
            }
        } else if (stream.isClosed) {
            throw CdpWireException(DevToolsCdpFailure.STREAM_CLOSED)
        } else {
            delay(CDP_POLL_INTERVAL_MS)
        }
    }
}

private fun parseWebSocketUri(value: String): URI? {
    if (value.isBlank() || value.length > MAX_WEBSOCKET_URL_CHARS || value.any(Char::isISOControl)) return null
    return runCatching {
        URI(value).takeIf { it.scheme.equals("ws", ignoreCase = true) || it.scheme.equals("wss", ignoreCase = true) }
            ?.takeIf { !it.host.isNullOrBlank() && it.userInfo == null && it.rawFragment == null }
    }.getOrNull()
}

private fun findHandshakeHeaderEnd(bytes: ByteArray): Int? {
    for (index in 0 until bytes.size - 3) {
        if (
            bytes[index] == '\r'.code.toByte() &&
            bytes[index + 1] == '\n'.code.toByte() &&
            bytes[index + 2] == '\r'.code.toByte() &&
            bytes[index + 3] == '\n'.code.toByte()
        ) return index + 4
    }
    return null
}

private class CdpByteQueue {
    private var bytes = ByteArray(0)

    val size: Int get() = bytes.size

    fun append(next: ByteArray) {
        if (next.isEmpty()) return
        bytes = bytes + next
    }

    fun toByteArray(): ByteArray = bytes

    fun discard(count: Int) {
        bytes = bytes.copyOfRange(count.coerceIn(0, bytes.size), bytes.size)
    }
}

private data class WebSocketHandshakeBytes(
    val headerBytes: ByteArray,
    val remainingBytes: ByteArray
)

private class CdpWireException(
    val failure: DevToolsCdpFailure,
    val protocolCode: Int? = null
) : IOException()

private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
private const val OPCODE_CONTINUATION = 0x0
private const val OPCODE_TEXT = 0x1
private const val OPCODE_CLOSE = 0x8
private const val OPCODE_PING = 0x9
private const val OPCODE_PONG = 0xA
private const val MAX_WEBSOCKET_HEADER_BYTES = 16 * 1024
private const val MAX_WEBSOCKET_URL_CHARS = 4_096
private const val MAX_CDP_MESSAGE_BYTES = 512 * 1024
private const val CDP_READ_CHUNK_BYTES = 4 * 1024
private const val CDP_CONNECT_TIMEOUT_MS = 5_000L
private const val CDP_COMMAND_TIMEOUT_MS = 5_000L
private const val MAX_HANDSHAKE_BODY_SUMMARY_BYTES = 1_024
private const val MAX_RESPONSE_REASON_CHARS = 120
private val HTTP_STATUS_PATTERN = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})(?:\\s+([^\\r\\n]*))?")
private val RESPONSE_URL_PATTERN = Regex("(?i)\\b(?:https?|wss?)://[^\\s\"'<>]+")
private const val CDP_POLL_INTERVAL_MS = 20L
