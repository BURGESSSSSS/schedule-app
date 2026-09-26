package com.burgess.schedule.school.sync.localadb

import android.os.Build
import android.util.Base64
import com.burgess.schedule.school.sync.importpreview.SchoolScheduleSnapshot
import com.burgess.schedule.school.sync.importpreview.SdbiScheduleParser
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64 as JavaBase64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class CoursePayloadSummary(
    val academicYear: String?,
    val term: String?,
    val currentWeek: String?,
    val maximumWeek: String?,
    val startDate: String?,
    val endDate: String?,
    val maximumPeriod: String?,
    val weekItemCounts: Map<Int, Int>,
    val isCourseSchedulePayload: Boolean
)

internal sealed interface CourseBodyParseResult {
    data class Confirmed(
        val summary: CoursePayloadSummary,
        val scheduleSnapshot: SchoolScheduleSnapshot
    ) : CourseBodyParseResult
    data class JsonUnconfirmed(val summary: CoursePayloadSummary) : CourseBodyParseResult
    data object NotJson : CourseBodyParseResult
    data object DecodeError : CourseBodyParseResult
    data object TooLarge : CourseBodyParseResult
}

/** The raw body is parsed and discarded; only this bounded summary is retained. */
internal fun parseCdpResponseBody(
    result: JSONObject,
    maxBodyBytes: Int = MAX_CAPTURED_COURSE_BODY_BYTES,
    base64Decoder: (String) -> ByteArray = ::decodeBase64Compat
): CourseBodyParseResult {
    val body = result.opt("body") as? String ?: return CourseBodyParseResult.DecodeError
    val encoded = result.optBoolean("base64Encoded", false)
    val bytes = try {
        if (encoded) {
            val maxEncodedChars = ((maxBodyBytes.toLong() + 2L) / 3L * 4L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (body.length > maxEncodedChars) return CourseBodyParseResult.TooLarge
            base64Decoder(body)
        } else {
            body.toByteArray(StandardCharsets.UTF_8)
        }
    } catch (_: IllegalArgumentException) {
        return CourseBodyParseResult.DecodeError
    } catch (_: RuntimeException) {
        return CourseBodyParseResult.DecodeError
    }
    if (bytes.size > maxBodyBytes) return CourseBodyParseResult.TooLarge

    val text = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        return CourseBodyParseResult.DecodeError
    }
    val json = try {
        JSONObject(text)
    } catch (_: JSONException) {
        return CourseBodyParseResult.NotJson
    } catch (_: RuntimeException) {
        return CourseBodyParseResult.NotJson
    }
    val weeks = (1..7).mapNotNull { week ->
        (json.opt("week$week") as? JSONArray)?.let { week to it.length() }
    }.toMap()
    val summary = CoursePayloadSummary(
        academicYear = json.scalarString("xn"),
        term = json.scalarString("xq"),
        currentWeek = json.scalarString("zc"),
        maximumWeek = json.scalarString("maxzc"),
        startDate = json.scalarString("qssj"),
        endDate = json.scalarString("jssj"),
        maximumPeriod = json.scalarString("maxjc"),
        weekItemCounts = weeks,
        isCourseSchedulePayload = !json.scalarString("xn").isNullOrBlank() &&
            !json.scalarString("zc").isNullOrBlank() && weeks.isNotEmpty()
    )
    return if (summary.isCourseSchedulePayload) {
        CourseBodyParseResult.Confirmed(summary, SdbiScheduleParser.parse(json, summary))
    } else {
        CourseBodyParseResult.JsonUnconfirmed(summary)
    }
}

private fun JSONObject.scalarString(name: String): String? = when (val value = opt(name)) {
    is String -> value.takeIf(String::isNotBlank)
    is Number -> value.toString()
    else -> null
}

private fun decodeBase64Compat(value: String): ByteArray =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        JavaBase64.getDecoder().decode(value)
    } else {
        Base64.decode(value, Base64.DEFAULT)
    }

private const val MAX_CAPTURED_COURSE_BODY_BYTES = 2 * 1024 * 1024
