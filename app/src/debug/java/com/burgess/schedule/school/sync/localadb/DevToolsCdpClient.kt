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
    PAGE_GET_FRAME_TREE("Page.getFrameTree")
}

internal enum class DevToolsCdpFailure {
    INVALID_WEBSOCKET_URL,
    UNSUPPORTED_WEBSOCKET_SCHEME,
    HANDSHAKE_INVALID,
    CDP_HANDSHAKE_ACCEPTED,
    CDP_HANDSHAKE_HTTP_STATUS,
    CDP_REJECTED_ORIGIN,
    CDP_REJECTED_HOST,
    CDP_REJECTED_OTHER,
    CDP_INVALID_ACCEPT,
    CDP_UPGRADE_HEADER_INVALID,
    CDP_HANDSHAKE_EOF,
    CDP_HANDSHAKE_TIMEOUT,
    CDP_HANDSHAKE_OTHER,
    TIMEOUT,
    STREAM_CLOSED,
    IO_ERROR,
    INVALID_FRAME,
    MESSAGE_TOO_LARGE,
    INVALID_JSON,
    PROTOCOL_ERROR,
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
) {
    val classificationLabel: String
        get() = when (classification) {
            DevToolsCdpFailure.CDP_HANDSHAKE_HTTP_STATUS ->
                responseStatus?.let { "CDP_HANDSHAKE_HTTP_STATUS_$it" } ?: "CDP_HANDSHAKE_OTHER"
            DevToolsCdpFailure.CDP_REJECTED_ORIGIN -> "CDP_REJECTED_ORIGIN"
            DevToolsCdpFailure.CDP_REJECTED_HOST -> "CDP_REJECTED_HOST"
            DevToolsCdpFailure.CDP_INVALID_ACCEPT -> "CDP_INVALID_ACCEPT"
            DevToolsCdpFailure.CDP_UPGRADE_HEADER_INVALID -> "CDP_UPGRADE_HEADER_INVALID"
            DevToolsCdpFailure.CDP_HANDSHAKE_EOF -> "CDP_HANDSHAKE_EOF"
            DevToolsCdpFailure.CDP_HANDSHAKE_TIMEOUT -> "CDP_HANDSHAKE_TIMEOUT"
            DevToolsCdpFailure.CDP_HANDSHAKE_OTHER -> "CDP_HANDSHAKE_OTHER"
            else -> classification.name
        }
}

internal sealed interface DevToolsCdpCommandResult {
    class Success(internal val result: JSONObject) : DevToolsCdpCommandResult {
        override fun toString(): String = "DevToolsCdpCommandResult.Success(result=<redacted>)"
    }

    data class Failed(
        val failure: DevToolsCdpFailure,
        val protocolCode: Int? = null,
        val protocolMessage: String? = null
    ) :
        DevToolsCdpCommandResult
}

internal sealed interface CdpResponseParseResult {
    data object Ignore : CdpResponseParseResult
    data class Success(val result: JSONObject) : CdpResponseParseResult
    data class Error(val code: Int?, val message: String?) : CdpResponseParseResult
    data object Invalid : CdpResponseParseResult
}

internal fun parseCdpResponseMessage(message: String, expectedId: Int): CdpResponseParseResult {
    val json = try {
        JSONObject(message)
    } catch (_: JSONException) {
        return CdpResponseParseResult.Invalid
    } catch (_: RuntimeException) {
        return CdpResponseParseResult.Invalid
    }
    val responseId = json.opt("id") as? Number ?: return CdpResponseParseResult.Ignore
    if (responseId.toInt() != expectedId) return CdpResponseParseResult.Ignore
    json.optJSONObject("error")?.let { error ->
        return CdpResponseParseResult.Error(
            error.optInt("code").takeIf { error.has("code") },
            sanitizeCdpProtocolMessage(error.optString("message", ""))
        )
    }
    val result = json.optJSONObject("result") ?: return CdpResponseParseResult.Invalid
    return CdpResponseParseResult.Success(result)
}

internal interface DevToolsCdpCommandSession : Closeable {
    suspend fun sendCommand(method: DevToolsCdpMethod): DevToolsCdpCommandResult
}

internal sealed interface CdpFrameTreeProbeResult {
    data class Success(val mainFrameUrl: SanitizedTargetUrl) : CdpFrameTreeProbeResult
    data class CommandFailed(
        val failure: DevToolsCdpFailure,
        val protocolCode: Int? = null,
        val protocolMessage: String? = null
    ) : CdpFrameTreeProbeResult
    data object MainFrameUrlMissing : CdpFrameTreeProbeResult
}

/** Phase 4 deliberately sends one command and never falls back to another CDP method. */
internal suspend fun requestMainFrameUrl(session: DevToolsCdpCommandSession): CdpFrameTreeProbeResult =
    when (val response = session.sendCommand(DevToolsCdpMethod.PAGE_GET_FRAME_TREE)) {
        is DevToolsCdpCommandResult.Failed -> CdpFrameTreeProbeResult.CommandFailed(
            response.failure,
            response.protocolCode,
            response.protocolMessage
        )
        is DevToolsCdpCommandResult.Success -> {
            val url = extractFrameTreeUrl(response.result)
                ?: return CdpFrameTreeProbeResult.MainFrameUrlMissing
            CdpFrameTreeProbeResult.Success(url)
        }
    }

internal fun compareHttpAndCdpTargetUrls(
    httpTargetUrl: String,
    cdpMainFrameUrl: SanitizedTargetUrl
): String = if (httpTargetUrl == cdpMainFrameUrl.displayUrl) "MATCH" else "MISMATCH"

internal fun isSchoolMainFrameUrl(url: SanitizedTargetUrl): Boolean =
    url.scheme.equals("https", ignoreCase = true) &&
        url.host.equals(SCHOOL_HOST, ignoreCase = true) &&
        url.path == SCHOOL_PAGE_PATH

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
                DevToolsCdpCommandResult.Failed(
                    exception.failure,
                    exception.protocolCode,
                    exception.protocolMessage
                )
            } catch (_: IOException) {
                DevToolsCdpCommandResult.Failed(DevToolsCdpFailure.IO_ERROR)
            } catch (_: JSONException) {
                DevToolsCdpCommandResult.Failed(DevToolsCdpFailure.INVALID_JSON)
            }
        }

    private suspend fun awaitResponse(commandId: Int): DevToolsCdpCommandResult {
        while (true) {
            val message = readTextMessage()
            when (val parsed = parseCdpResponseMessage(message, commandId)) {
                CdpResponseParseResult.Ignore -> continue
                CdpResponseParseResult.Invalid -> throw CdpWireException(DevToolsCdpFailure.INVALID_JSON)
                is CdpResponseParseResult.Error -> throw CdpWireException(
                    failure = DevToolsCdpFailure.PROTOCOL_ERROR,
                    protocolCode = parsed.code,
                    protocolMessage = parsed.message
                )
                is CdpResponseParseResult.Success -> return DevToolsCdpCommandResult.Success(parsed.result)
            }
        }
    }

    private suspend fun readTextMessage(): String {
        val assembler = CdpWebSocketMessageAssembler()
        while (true) {
            val frame = nextFrame()
            when (val action = assembler.accept(frame)) {
                CdpIncomingFrameAction.Continue,
                CdpIncomingFrameAction.PongReceived -> Unit
                is CdpIncomingFrameAction.PongRequired -> sendClientFrame(OPCODE_PONG, action.payload)
                is CdpIncomingFrameAction.CloseReceived -> {
                    runCatching { sendClientFrame(OPCODE_CLOSE, action.payload) }
                    throw CdpWireException(DevToolsCdpFailure.STREAM_CLOSED)
                }
                is CdpIncomingFrameAction.TextMessage -> return decodeMessage(action.payload)
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
        output.write(buildClientWebSocketFrame(opcode, payload, mask))
        output.flush()
    }

    override fun close() {
        runCatching { sendClientFrame(OPCODE_CLOSE, ByteArray(0)) }
        runCatching { stream.close() }
    }

    override fun toString(): String = "DevToolsCdpClient(targetSocket=<redacted>)"

    internal companion object Connector {
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

            val requestDiagnostic = websocketHandshakeRequestDiagnostic(uri)
            try {
                val input = stream.openInputStream()
                val output = stream.openOutputStream()
                val keyBytes = ByteArray(16).also(SecureRandom()::nextBytes)
                val key = encodeBase64(keyBytes)
                val request = buildWebSocketHandshakeRequest(uri, key)
                output.write(request)
                output.flush()
                val handshake = withTimeoutOrNull(timeoutMs) {
                    readWebSocketHandshake(input, stream)
                } ?: return@withContext closeAndFail(
                    stream,
                    DevToolsCdpFailure.CDP_HANDSHAKE_TIMEOUT,
                    handshakeFailureDiagnostic(requestDiagnostic, DevToolsCdpFailure.CDP_HANDSHAKE_TIMEOUT)
                )
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
                val failure = if (exception.failure == DevToolsCdpFailure.STREAM_CLOSED) {
                    DevToolsCdpFailure.CDP_HANDSHAKE_EOF
                } else {
                    DevToolsCdpFailure.CDP_HANDSHAKE_OTHER
                }
                closeAndFail(stream, failure, handshakeFailureDiagnostic(requestDiagnostic, failure))
            } catch (_: IOException) {
                closeAndFail(
                    stream,
                    DevToolsCdpFailure.CDP_HANDSHAKE_OTHER,
                    handshakeFailureDiagnostic(requestDiagnostic, DevToolsCdpFailure.CDP_HANDSHAKE_OTHER)
                )
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

internal fun buildCdpCommandPayload(commandId: Int, method: DevToolsCdpMethod): ByteArray {
    val payload = JSONObject()
        .put("id", commandId)
        .put("method", method.protocolName)
    return payload.toString().toByteArray(StandardCharsets.UTF_8)
}

internal fun extractFrameTreeUrl(result: JSONObject): SanitizedTargetUrl? {
    val frame = result.optJSONObject("frameTree")
        ?.optJSONObject("frame")
        ?: return null
    return sanitizeDevToolsTargetUrl(frame.optString("url", ""))
}

internal fun buildWebSocketHandshakeRequest(uri: URI, key: String): ByteArray {
    val path = uri.rawPath.orEmpty().ifBlank { "/" }
    require(path.startsWith('/') && path.none(Char::isISOControl) && '?' !in path && '#' !in path)
    require(key.isNotBlank() && key.none(Char::isISOControl))
    return (
        "GET $path HTTP/1.1\r\n" +
            "Host: $DEVTOOLS_HOST_HEADER\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
        ).toByteArray(StandardCharsets.US_ASCII)
}

internal fun websocketHandshakeRequestDiagnostic(uri: URI): DevToolsHandshakeRequestDiagnostic =
    DevToolsHandshakeRequestDiagnostic(
        headerNames = listOf(
            "Host",
            "Upgrade",
            "Connection",
            "Sec-WebSocket-Key",
            "Sec-WebSocket-Version"
        ),
        hostHeaderValue = DEVTOOLS_HOST_HEADER,
        originHeaderSent = false,
        originHeaderValue = null,
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
    val isSwitchingProtocols = statusCode == 101
    if (!isSwitchingProtocols) {
        val rejection = classifyHandshakeRejection(statusCode, bodyBytes)
        return HandshakeValidation.Rejected(
            rejection,
            diagnostic(statusLine, bodyBytes, request, rejection)
        )
    }

    val upgrade = headers["upgrade"].orEmpty()
    val connection = headers["connection"].orEmpty()
    val upgradeValid = upgrade.equals("websocket", ignoreCase = true) &&
        connection.split(',').any { it.trim().equals("upgrade", ignoreCase = true) }
    if (!upgradeValid) {
        return HandshakeValidation.Rejected(
            DevToolsCdpFailure.CDP_UPGRADE_HEADER_INVALID,
            diagnostic(
                statusLine,
                bodyBytes,
                request,
                DevToolsCdpFailure.CDP_UPGRADE_HEADER_INVALID
            )
        )
    }

    val expectedAccept = websocketAcceptValue(key)
    return if (headers["sec-websocket-accept"] == expectedAccept) {
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
            DevToolsCdpFailure.CDP_INVALID_ACCEPT,
            diagnostic(
                statusLine,
                bodyBytes,
                request,
                DevToolsCdpFailure.CDP_INVALID_ACCEPT
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
        statusCode != null -> DevToolsCdpFailure.CDP_HANDSHAKE_HTTP_STATUS
        else -> DevToolsCdpFailure.CDP_HANDSHAKE_OTHER
    }
}

private fun handshakeFailureDiagnostic(
    request: DevToolsHandshakeRequestDiagnostic,
    failure: DevToolsCdpFailure
): DevToolsHandshakeDiagnostic = diagnostic(
    statusLine = "",
    bodyBytes = ByteArray(0),
    request = request,
    classification = failure
)

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

internal fun websocketAcceptValue(key: String): String = encodeBase64(
    MessageDigest.getInstance("SHA-1")
        .digest((key + WEBSOCKET_GUID).toByteArray(StandardCharsets.US_ASCII))
)

private fun sanitizeCdpProtocolMessage(value: String): String {
    val withoutUrls = RESPONSE_URL_PATTERN.replace(value) { match ->
        TargetUrlNormalizer.normalize(match.value.trimEnd('.', ',', ')', ']')).url?.displayUrl ?: "<URL>"
    }
    val withoutCredentialValues = SENSITIVE_VALUE_PATTERN.replace(withoutUrls) { match ->
        "${match.groupValues[1]}=[已隐藏]"
    }
    return withoutCredentialValues
        .filterNot(Char::isISOControl)
        .take(MAX_PROTOCOL_ERROR_MESSAGE_CHARS)
        .ifBlank { "协议返回错误（消息已隐藏）" }
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

internal sealed interface CdpIncomingFrameAction {
    data object Continue : CdpIncomingFrameAction
    data object PongReceived : CdpIncomingFrameAction
    data class PongRequired(val payload: ByteArray) : CdpIncomingFrameAction
    data class CloseReceived(val payload: ByteArray) : CdpIncomingFrameAction
    data class TextMessage(val payload: ByteArray) : CdpIncomingFrameAction
}

internal class CdpWebSocketMessageAssembler {
    private val fragments = ByteArrayOutputStream()
    private var collectingFragments = false

    fun accept(frame: CdpWebSocketFrame): CdpIncomingFrameAction = when (frame.opcode) {
        OPCODE_CLOSE -> CdpIncomingFrameAction.CloseReceived(frame.payload)
        OPCODE_PING -> CdpIncomingFrameAction.PongRequired(frame.payload)
        OPCODE_PONG -> CdpIncomingFrameAction.PongReceived
        OPCODE_TEXT -> {
            if (collectingFragments) throw CdpWireException(DevToolsCdpFailure.INVALID_FRAME)
            if (frame.fin) {
                CdpIncomingFrameAction.TextMessage(frame.payload)
            } else {
                fragments.reset()
                appendBounded(fragments, frame.payload)
                collectingFragments = true
                CdpIncomingFrameAction.Continue
            }
        }
        OPCODE_CONTINUATION -> {
            if (!collectingFragments) throw CdpWireException(DevToolsCdpFailure.INVALID_FRAME)
            appendBounded(fragments, frame.payload)
            if (frame.fin) {
                collectingFragments = false
                CdpIncomingFrameAction.TextMessage(fragments.toByteArray())
            } else {
                CdpIncomingFrameAction.Continue
            }
        }
        else -> throw CdpWireException(DevToolsCdpFailure.INVALID_FRAME)
    }

    private fun appendBounded(output: ByteArrayOutputStream, bytes: ByteArray) {
        if (output.size() + bytes.size > MAX_CDP_MESSAGE_BYTES) {
            throw CdpWireException(DevToolsCdpFailure.MESSAGE_TOO_LARGE)
        }
        output.write(bytes)
    }
}

internal fun buildClientWebSocketFrame(
    opcode: Int,
    payload: ByteArray,
    maskingKey: ByteArray
): ByteArray {
    require(maskingKey.size == 4)
    require(opcode in setOf(OPCODE_CONTINUATION, OPCODE_TEXT, OPCODE_CLOSE, OPCODE_PING, OPCODE_PONG))
    require(opcode < OPCODE_CLOSE || payload.size <= 125)
    require(payload.size <= MAX_CDP_MESSAGE_BYTES)
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
    frame.write(maskingKey)
    payload.forEachIndexed { index, byte ->
        frame.write((byte.toInt() xor (maskingKey[index % 4].toInt() and 0xFF)) and 0xFF)
    }
    return frame.toByteArray()
}

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

private suspend fun readWebSocketHandshake(
    input: InputStream,
    stream: AdbStream
): WebSocketHandshakeBytes {
    val accumulator = WebSocketHandshakeAccumulator()
    val chunk = ByteArray(CDP_READ_CHUNK_BYTES)
    while (true) {
        val available = input.available()
        if (available > 0) {
            val count = input.read(chunk, 0, minOf(available, chunk.size))
            if (count < 0) throw CdpWireException(DevToolsCdpFailure.STREAM_CLOSED)
            accumulator.append(chunk, length = count)?.let { return it }
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

internal data class WebSocketHandshakeBytes(
    val headerBytes: ByteArray,
    val remainingBytes: ByteArray
)

internal class WebSocketHandshakeAccumulator {
    private val accumulated = ByteArrayOutputStream()

    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): WebSocketHandshakeBytes? {
        if (length > 0) accumulated.write(bytes, offset, length)
        val all = accumulated.toByteArray()
        val headerEnd = findHandshakeHeaderEnd(all)
        if (headerEnd == null) {
            if (all.size > MAX_WEBSOCKET_HEADER_BYTES) {
                throw CdpWireException(DevToolsCdpFailure.MESSAGE_TOO_LARGE)
            }
            return null
        }
        if (headerEnd > MAX_WEBSOCKET_HEADER_BYTES) {
            throw CdpWireException(DevToolsCdpFailure.MESSAGE_TOO_LARGE)
        }
        return WebSocketHandshakeBytes(
            headerBytes = all.copyOfRange(0, headerEnd),
            remainingBytes = all.copyOfRange(headerEnd, all.size)
        )
    }
}

private class CdpWireException(
    val failure: DevToolsCdpFailure,
    val protocolCode: Int? = null,
    val protocolMessage: String? = null
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
private const val MAX_PROTOCOL_ERROR_MESSAGE_CHARS = 160
private const val DEVTOOLS_HOST_HEADER = "localhost"
private val HTTP_STATUS_PATTERN = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})(?:\\s+([^\\r\\n]*))?")
private val RESPONSE_URL_PATTERN = Regex("(?i)\\b(?:https?|wss?)://[^\\s\"'<>]+")
private val SENSITIVE_VALUE_PATTERN = Regex(
    "(?i)\\b(cookie|set-cookie|authorization|token|session|jsessionid|oauth[_ -]?(?:code|state))\\b[^\\r\\n]*"
)
private const val CDP_POLL_INTERVAL_MS = 20L
