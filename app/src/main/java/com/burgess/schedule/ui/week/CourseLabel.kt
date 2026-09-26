package com.burgess.schedule.ui.week
fun shortName(name: String): String {
    Regex("^[A-Za-z0-9.+#]+").find(name)?.value?.let { return it }
    return name.replace(Regex("[（(].*?[）)]"), "").trim().take(4)
}

/** Prefer the school's complete bracketed room/building description for week cells. */
fun weekLocation(location: String): String {
    val bracketed = Regex("""\[([^\[\]]*)\]""")
        .findAll(location)
        .lastOrNull()
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        .orEmpty()
    return bracketed.ifBlank { location.trim() }
}

fun hasTimeConflict(day: Int, start: Int, end: Int, week: Int, otherDay: Int, otherStart: Int, otherEnd: Int, otherWeek: Int): Boolean = day == otherDay && (week == otherWeek || week == 0 || otherWeek == 0) && start <= otherEnd && end >= otherStart
