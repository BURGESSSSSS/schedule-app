package com.burgess.schedule.school.sync.localadb

/** Minimal socket and process metadata shared by discovery and Debug diagnostics. */
internal data class NamedUnixSocket(
    val inode: Long,
    val type: Int,
    val state: Int,
    val socketName: String,
    val isAbstract: Boolean
)

internal data class WeWorkProcess(
    val pid: Int,
    val processName: String
)

private val PID_STYLE_SUFFIX = Regex("(?:^|[^0-9])([0-9]{2,})$")

/** Extracts a trailing PID-like numeric suffix from a local socket name. */
internal fun pidStyleSuffix(socketName: String): Int? =
    PID_STYLE_SUFFIX.find(socketName)?.groupValues?.getOrNull(1)?.toIntOrNull()
