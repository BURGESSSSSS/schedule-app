package com.burgess.schedule.ui.week

data class WeekBlockCourse(val id: Long, val startPeriod: Int, val endPeriod: Int)
data class WeekCluster(val firstBlock: Int, val lastBlock: Int, val ids: List<Long>)

/** Merge intersecting two-period blocks so a long class cannot swallow another class. */
fun weekClusters(courses: List<WeekBlockCourse>): List<WeekCluster> {
    val result = mutableListOf<WeekCluster>()
    courses.sortedWith(compareBy<WeekBlockCourse> { it.startPeriod }.thenBy { it.id }).forEach { course ->
        val first = course.startPeriod / 2
        val last = maxOf(first, course.endPeriod / 2)
        val previous = result.lastOrNull()
        if (previous != null && first <= previous.lastBlock) {
            result[result.lastIndex] = previous.copy(lastBlock = maxOf(last, previous.lastBlock), ids = previous.ids + course.id)
        } else result += WeekCluster(first, last, listOf(course.id))
    }
    return result
}
