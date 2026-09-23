package com.burgess.schedule.school.sync.debug

internal enum class BindingStage {
    IDLE,
    BIND_REQUESTED,
    SERVICE_CONNECTED,
    BINDER_READY,
    UID_VERIFIED,
    SERVICE_DISCONNECTED,
    BIND_TIMEOUT,
    BIND_EXCEPTION
}

internal data class BindingDiagnostics(
    val applicationId: String,
    val namespace: String,
    val serviceClass: String,
    val componentName: String,
    val shizukuBinderAlive: Boolean? = null,
    val permissionGranted: Boolean? = null,
    val bindRequested: Boolean = false,
    val serviceConnected: Boolean = false,
    val binderValid: Boolean? = null,
    val uidQuerySucceeded: Boolean = false,
    val uid: Int? = null,
    val stage: BindingStage = BindingStage.IDLE,
    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
    val released: Boolean = false
) {
    val verified: Boolean get() = uidQuerySucceeded && uid == 2000

    fun afterUid(actualUid: Int): BindingDiagnostics = copy(
        uidQuerySucceeded = true,
        uid = actualUid,
        stage = if (actualUid == 2000) BindingStage.UID_VERIFIED else BindingStage.BIND_EXCEPTION,
        exceptionType = if (actualUid == 2000) null else "UnexpectedUid",
        exceptionMessage = if (actualUid == 2000) null else "Expected shell UID 2000; received $actualUid"
    )
}

/** Bind-stage errors never need raw URLs or credential values in the UI or Logcat. */
internal fun safeDiagnosticMessage(raw: String?): String? = raw?.let { message ->
    message
        .replace(Regex("(?i)https?://[^\\s]+"), "[URL redacted]")
        .replace(Regex("\\?[^\\s]+=[^\\s]+"), "?[query redacted]")
        .replace(Regex("(?i)\\bAuthorization\\s*[:=]\\s*(?:Bearer|Basic)\\s+[^\\s,;]+"), "Authorization=[redacted]")
        .replace(Regex("(?i)\\b(cookie|token|session|jsessionid|authorization|code|state)\\s*[:=]\\s*[^\\s,;]+"), "$1=[redacted]")
        .replace(Regex("(?i)\\bBearer\\s+[^\\s]+"), "Bearer [redacted]")
        .replace(Regex("[\\r\\n\\t]"), " ")
        .take(240)
}
