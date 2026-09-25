package com.burgess.schedule.school.sync.localadb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal enum class CdpNetworkObserverState {
    NETWORK_ENABLE_START,
    NETWORK_ENABLED,
    WAITING_FOR_COURSE_REQUEST,
    COURSE_REQUEST_DETECTED,
    COURSE_RESPONSE_DETECTED,
    COURSE_BODY_FETCH_START,
    COURSE_BODY_FETCH_SUCCESS,
    COURSE_JSON_CONFIRMED,
    COURSE_BODY_FETCH_FAILED,
    CDP_DISCONNECTED
}

internal sealed interface CdpNetworkObserverResult {
    data object Stopped : CdpNetworkObserverResult
    data class Disconnected(val failure: DevToolsCdpFailure) : CdpNetworkObserverResult
    data class NetworkEnableFailed(
        val failure: DevToolsCdpFailure,
        val protocolCode: Int? = null,
        val protocolMessage: String? = null
    ) : CdpNetworkObserverResult
}

internal class CdpNetworkObserver(
    private val client: DevToolsCdpNetworkSession,
    private val capture: CourseResponseCapture = CourseResponseCapture(),
    private val onState: (CdpNetworkObserverState) -> Unit,
    private val onSnapshot: (CourseCaptureSnapshot) -> Unit
) {
    private var networkEnabled = false

    suspend fun observe(): CdpNetworkObserverResult {
        onState(CdpNetworkObserverState.NETWORK_ENABLE_START)
        try {
            when (val enabled = client.sendCommand(DevToolsCdpMethod.NETWORK_ENABLE)) {
                is DevToolsCdpCommandResult.Failed -> {
                    return CdpNetworkObserverResult.NetworkEnableFailed(
                        enabled.failure,
                        enabled.protocolCode,
                        enabled.protocolMessage
                    )
                }
                is DevToolsCdpCommandResult.Success -> Unit
            }
            networkEnabled = true
            onState(CdpNetworkObserverState.NETWORK_ENABLED)
            onState(CdpNetworkObserverState.WAITING_FOR_COURSE_REQUEST)
            onSnapshot(capture.snapshot())

            while (true) {
                when (val received = client.receiveNetworkEvent()) {
                    is CdpNetworkEventReadResult.Closed -> {
                        onState(CdpNetworkObserverState.CDP_DISCONNECTED)
                        return CdpNetworkObserverResult.Disconnected(received.failure)
                    }
                    is CdpNetworkEventReadResult.Event -> handleEvent(received.event)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            disableNetworkBestEffort()
        }
    }

    private suspend fun handleEvent(event: CdpNetworkEvent) {
        when (event) {
            is CdpNetworkEvent.RequestWillBeSent -> {
                if (CourseRequestMatcher.isSchoolHost(event.url)) {
                    capture.onEvent(event)
                    if (CourseRequestMatcher.isCourseSchedule(event.url)) {
                        onState(CdpNetworkObserverState.COURSE_REQUEST_DETECTED)
                    }
                    onSnapshot(capture.snapshot())
                }
            }
            is CdpNetworkEvent.ResponseReceived -> {
                capture.onEvent(event)
                if (capture.hasTrackedRequest(event.requestId)) {
                    onState(CdpNetworkObserverState.COURSE_RESPONSE_DETECTED)
                    onSnapshot(capture.snapshot())
                }
            }
            is CdpNetworkEvent.LoadingFailed -> {
                capture.onEvent(event)
                onSnapshot(capture.snapshot())
            }
            is CdpNetworkEvent.LoadingFinished -> {
                when (val action = capture.onEvent(event)) {
                    CourseCaptureAction.None -> Unit
                    is CourseCaptureAction.FetchBody -> fetchBody(action.requestId)
                }
                onSnapshot(capture.snapshot())
            }
        }
    }

    private suspend fun fetchBody(requestId: String) {
        onState(CdpNetworkObserverState.COURSE_BODY_FETCH_START)
        val response = client.sendCommand(
            DevToolsCdpMethod.NETWORK_GET_RESPONSE_BODY,
            JSONObject().put("requestId", requestId)
        )
        when (response) {
            is DevToolsCdpCommandResult.Failed -> {
                val message = response.protocolMessage.orEmpty()
                val state = classifyBodyFetchFailure(response.protocolCode, message)
                capture.recordBodyFailure(
                    requestId = requestId,
                    state = state,
                    code = response.protocolCode,
                    message = message
                )
                onState(CdpNetworkObserverState.COURSE_BODY_FETCH_FAILED)
            }
            is DevToolsCdpCommandResult.Success -> {
                onState(CdpNetworkObserverState.COURSE_BODY_FETCH_SUCCESS)
                val parsed = withContext(Dispatchers.Default) {
                    parseCdpResponseBody(response.result)
                }
                capture.recordBodyResult(requestId, parsed)
                if (parsed is CourseBodyParseResult.Confirmed) {
                    onState(CdpNetworkObserverState.COURSE_JSON_CONFIRMED)
                }
            }
        }
    }

    private suspend fun disableNetworkBestEffort() {
        if (!networkEnabled) return
        networkEnabled = false
        withContext(NonCancellable) {
            withTimeoutOrNull(NETWORK_DISABLE_TIMEOUT_MS) {
                client.sendCommand(DevToolsCdpMethod.NETWORK_DISABLE)
            }
        }
    }

    private companion object {
        const val NETWORK_DISABLE_TIMEOUT_MS = 1_000L
    }
}

internal fun classifyBodyFetchFailure(code: Int?, message: String): CourseBodyState {
    val normalized = message.lowercase()
    return when {
        "no resource with given identifier" in normalized || "resource not found" in normalized ->
            CourseBodyState.BODY_NOT_AVAILABLE
        "requestid" in normalized && listOf("invalid", "unknown", "not found", "not valid").any(normalized::contains) ->
            CourseBodyState.BODY_REQUEST_ID_INVALID
        code == -32602 && "identifier" in normalized -> CourseBodyState.BODY_REQUEST_ID_INVALID
        else -> CourseBodyState.BODY_CDP_ERROR
    }
}

internal enum class CdpDisconnectContext {
    FOREGROUND,
    BACKGROUND
}

internal fun classifyCdpDisconnect(isAppForeground: Boolean): CdpDisconnectContext =
    if (isAppForeground) CdpDisconnectContext.FOREGROUND else CdpDisconnectContext.BACKGROUND
