package com.burgess.schedule.ui.week

import java.util.Calendar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.burgess.schedule.R
import com.burgess.schedule.formatDateRange
import com.burgess.schedule.scheduleWeekNumber
import com.burgess.schedule.Ink
import com.burgess.schedule.ui.components.*
import com.burgess.schedule.ui.detail.CourseDetailController
import com.burgess.schedule.ui.model.Course
import com.burgess.schedule.ui.model.FocusCourse
import com.burgess.schedule.ui.theme.AppSpace
import com.burgess.schedule.ui.theme.AppType

@Composable
internal fun WeekScreen(
    today: Calendar,
    selectedWeekStart: Calendar,
    weekOffset: Int,
    courses: List<Course>,
    focus: FocusCourse?,
    onImportClick: (Int) -> Unit,
    onReminderClick: () -> Unit,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
    editing: Boolean,
    onToggleEditing: () -> Unit,
    onAddCourse: (Int, Int, Int) -> Unit,
    onEditCourse: (Course) -> Unit,
    onCopyNextWeek: (Int, List<Course>) -> Unit,
    onCourseClick: (Course) -> Unit,
    detailController: CourseDetailController? = null
) {
    val weekNumber = scheduleWeekNumber(selectedWeekStart)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AppSpace.weekPage, vertical = AppSpace.page), verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("第 $weekNumber 周", style = AppType.title, color = Ink)
                SectionLabel(formatDateRange(selectedWeekStart))
            }
            com.burgess.schedule.ui.components.PillButton(if (editing) "完成" else "编辑", onToggleEditing, if (editing) com.burgess.schedule.ui.components.PillStyle.Primary else com.burgess.schedule.ui.components.PillStyle.Secondary, true)
            com.burgess.schedule.ui.components.PillButton("导入", { onImportClick(weekNumber) }, com.burgess.schedule.ui.components.PillStyle.Secondary, true)
        }
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpace.gridGap)) {
            com.burgess.schedule.ui.components.CircleIconButton(R.drawable.symbol_chevron_left, "上一周", onPrevious)
            com.burgess.schedule.ui.components.CircleIconButton(R.drawable.symbol_chevron_right, "下一周", onNext)
            if (weekOffset != 0) AppChip("回到本周", onToday)
            com.burgess.schedule.ui.components.PillButton("复制到下周", { onCopyNextWeek(weekNumber, courses) }, com.burgess.schedule.ui.components.PillStyle.Ghost, true)
        }
        if (courses.isEmpty()) SectionLabel(if (editing) "点击空白时段添加课程。" else "本周还没有课程，点击编辑或导入开始。")
        ScheduleGrid(today, courses, focus, weekOffset, editing, AppSpace.gridRow, { day, period -> onAddCourse(weekNumber, day, period) }, onEditCourse, onCourseClick, detailController)
    }
}
