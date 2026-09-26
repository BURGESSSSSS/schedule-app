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
