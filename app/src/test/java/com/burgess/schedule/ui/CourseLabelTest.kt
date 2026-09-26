package com.burgess.schedule.ui
import com.burgess.schedule.ui.week.*
import org.junit.Assert.*
import org.junit.Test
class CourseLabelTest {
 @Test fun abbreviations() { assertEquals("Node.js", shortName("Node.js应用开发")); assertEquals("高等数学", shortName("高等数学（下）")) }
 @Test fun weekLocationPrefersFullBracketedSchoolLocation() {
  assertEquals("数智基地32-实训中心E303", weekLocation("实训中心E区[数智基地32-实训中心E303]"))
  assertEquals("二号教学楼B204", weekLocation("二号教学楼[二号教学楼B204]"))
  assertEquals("先进制造产教融合实训基地D202AI数据增值科创工坊2", weekLocation("先进制造产教融合实训基地[先进制造产教融合实训基地D202AI数据增值科创工坊2]"))
 }
 @Test fun weekLocationFallsBackToFullUnbracketedValue() {
  assertEquals("教学楼 AB-209", weekLocation("教学楼 AB-209"))
  assertEquals("", weekLocation("  "))
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
