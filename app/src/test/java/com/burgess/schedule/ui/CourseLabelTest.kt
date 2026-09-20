package com.burgess.schedule.ui
import com.burgess.schedule.ui.week.*
import org.junit.Assert.*
import org.junit.Test
class CourseLabelTest {
 @Test fun abbreviations() { assertEquals("Node.js", shortName("Node.js应用开发")); assertEquals("高等数学", shortName("高等数学（下）")); assertEquals("E209", roomNumber("教学楼 E209")) }
}
