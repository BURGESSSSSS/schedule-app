package com.burgess.schedule.ui
import com.burgess.schedule.ui.week.*
import org.junit.Assert.*
import org.junit.Test
class CourseLabelTest {
 @Test fun abbreviations() { assertEquals("Node.js", shortName("Node.js应用开发")); assertEquals("高等数学", shortName("高等数学（下）")); assertEquals("E209", roomNumber("教学楼 E209")) }
 @Test fun roomOmitsBuildingAndPreservesLetterPrefix() {
  assertEquals("AB-209", roomNumber("第二教学楼 AB-209"))
  assertEquals("", roomNumber("未填写教室"))
 }
 @Test fun overlappingBlocksNeverHideCourses() {
  val clusters = weekClusters(listOf(WeekBlockCourse(1, 0, 5), WeekBlockCourse(2, 2, 3), WeekBlockCourse(3, 6, 7)))
  assertEquals(listOf(1L, 2L, 3L), clusters.flatMap { it.ids })
  assertEquals(2, clusters.size)
  assertEquals(2, clusters.first().lastBlock)
 }
 @Test fun nestedLongCourseKeepsWholeClusterExtentAndOrder() {
  val cluster = weekClusters(listOf(WeekBlockCourse(10, 0, 5), WeekBlockCourse(11, 2, 3))).single()
  assertEquals(0, cluster.firstBlock)
  assertEquals(2, cluster.lastBlock)
  assertEquals(listOf(10L, 11L), cluster.ids)
 }
 @Test fun separateSinglePeriodsInOneBlockBothRemainVisible() {
  assertEquals(listOf(1L, 2L), weekClusters(listOf(WeekBlockCourse(1, 0, 0), WeekBlockCourse(2, 1, 1))).single().ids)
 }
}
