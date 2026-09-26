package com.burgess.schedule.school.sync.localadb

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal enum class TargetUrlNormalizationMode {
    STRICT,
    LENIENT_BASE
}

internal data class TargetUrlNormalizationResult(
    val url: SanitizedTargetUrl?,
    val mode: TargetUrlNormalizationMode?,
    val strictParseFailureType: String? = null,
    val strictParseFailureIndex: Int? = null
)

/** Normalizes display and matching metadata without retaining query or fragment values. */
internal object TargetUrlNormalizer {
    fun normalize(rawUrl: String): TargetUrlNormalizationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return TargetUrlNormalizationResult(url = null, mode = null)
        if (trimmed.length > MAX_TARGET_URL_CHARS) {
            return TargetUrlNormalizationResult(
                url = null,
                mode = null,
                strictParseFailureType = "URL_TOO_LONG"
            )
        }

        val strict = parseUri(trimmed)
        if (strict.uri != null) {
            val sanitized = sanitizeUri(strict.uri)
            if (sanitized != null) {
                return TargetUrlNormalizationResult(
                    url = sanitized,
                    mode = TargetUrlNormalizationMode.STRICT
                )
            }
        }

        val baseUrl = trimmed.substringBefore('#').substringBefore('?').trim()
        if (baseUrl.isNotEmpty()) {
            val base = parseUri(baseUrl)
            val sanitizedBase = base.uri?.let(::sanitizeUri)
            if (sanitizedBase != null) {
                return TargetUrlNormalizationResult(
                    url = sanitizedBase,
                    mode = TargetUrlNormalizationMode.LENIENT_BASE,
                    strictParseFailureType = strict.failureType ?: "InvalidUrl",
                    strictParseFailureIndex = strict.failureIndex
                )
            }
        }

        return TargetUrlNormalizationResult(
            url = null,
            mode = null,
            strictParseFailureType = strict.failureType ?: "InvalidUrl",
            strictParseFailureIndex = strict.failureIndex
        )
    }

    private fun parseUri(value: String): UriParseAttempt = try {
        UriParseAttempt(uri = URI(value))
    } catch (exception: URISyntaxException) {
        UriParseAttempt(
            uri = null,
            failureType = exception.javaClass.simpleName,
            failureIndex = exception.index.takeIf { it >= 0 }
        )
    } catch (exception: IllegalArgumentException) {
        UriParseAttempt(uri = null, failureType = exception.javaClass.simpleName)
    }

    private fun sanitizeUri(uri: URI): SanitizedTargetUrl? {
        val scheme = uri.scheme?.takeIf(String::isNotBlank)?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host != null) {
            val path = uri.rawPath.orEmpty().ifBlank { "/" }
            return SanitizedTargetUrl("$scheme://$host$path", scheme, host, path)
        }
        if (uri.isOpaque) {
            val safePart = uri.rawSchemeSpecificPart.orEmpty()
                .substringBefore('?')
                .substringBefore('#')
            val display = if (
                scheme.equals("about", ignoreCase = true) &&
                safePart.lowercase(Locale.ROOT) in setOf("blank", "srcdoc")
            ) {
                "$scheme:${safePart.lowercase(Locale.ROOT)}"
            } else {
                "$scheme:"
            }
            return SanitizedTargetUrl(display, scheme, null, null)
        }
        val path = uri.rawPath.orEmpty()
        return SanitizedTargetUrl("$scheme:$path", scheme, null, path)
    }

    private data class UriParseAttempt(
        val uri: URI?,
        val failureType: String? = null,
        val failureIndex: Int? = null
    )

    private const val MAX_TARGET_URL_CHARS = 8_192
}
