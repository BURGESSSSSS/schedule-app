package com.burgess.schedule.ui.week
fun shortName(name: String): String {
    Regex("^[A-Za-z0-9.+#]+").find(name)?.value?.let { return it }
    return name.replace(Regex("[（(].*?[）)]"), "").trim().take(4)
}
fun roomNumber(room: String): String = Regex("[A-Za-z]+-?[0-9]+[A-Za-z]?|[0-9]{2,}[A-Za-z]?").findAll(room).lastOrNull()?.value.orEmpty()

fun hasTimeConflict(day: Int, start: Int, end: Int, week: Int, otherDay: Int, otherStart: Int, otherEnd: Int, otherWeek: Int): Boolean = day == otherDay && (week == otherWeek || week == 0 || otherWeek == 0) && start <= otherEnd && end >= otherStart
