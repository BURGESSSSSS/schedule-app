package com.burgess.schedule.school.sync.localadb

internal data class NamedUnixSocketReadResult(
    val successful: Boolean,
    val sockets: List<NamedUnixSocket>
)

internal fun parseNamedUnixSockets(output: String, exitCode: Int?): NamedUnixSocketReadResult {
    var headerFound = false
    val byInode = linkedMapOf<Long, NamedUnixSocket>()
    output.lineSequence().map { it.trimEnd('\r') }.forEach { line ->
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.firstOrNull().equals("Num", ignoreCase = true) &&
            columns.getOrNull(6).equals("Inode", ignoreCase = true)
        ) {
            headerFound = true
            return@forEach
        }
        if (columns.size < 8) return@forEach
        val type = columns[4].toIntOrNull(16) ?: return@forEach
        val state = columns[5].toIntOrNull(16) ?: return@forEach
        val inode = columns[6].toLongOrNull() ?: return@forEach
        val name = columns.drop(7).joinToString(" ").takeIf(String::isNotBlank) ?: return@forEach
        byInode.putIfAbsent(inode, NamedUnixSocket(inode, type, state, name, name.startsWith('@')))
    }
    val successful = exitCode == 0 && headerFound
    return NamedUnixSocketReadResult(successful, if (successful) byInode.values.toList() else emptyList())
}

internal fun parseWeWorkProcessList(output: String, exitCode: Int?): List<WeWorkProcess>? {
    var headerFound = false
    val processes = linkedMapOf<Int, WeWorkProcess>()
    output.lineSequence().map { it.trimEnd('\r') }.forEach { line ->
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size >= 2 && columns[0].equals("PID", true) && columns[1].equals("NAME", true)) {
            headerFound = true
            return@forEach
        }
        val pid = columns.firstOrNull()?.toIntOrNull() ?: return@forEach
        val name = columns.getOrNull(1).orEmpty()
        if (pid > 0 && isEnterpriseWeWorkProcess(name)) processes.putIfAbsent(pid, WeWorkProcess(pid, name))
    }
    return if (exitCode == 0 && headerFound) processes.values.toList() else null
}

private fun isEnterpriseWeWorkProcess(value: String): Boolean =
    value == WEWORK_PACKAGE || value.startsWith("$WEWORK_PACKAGE:")
