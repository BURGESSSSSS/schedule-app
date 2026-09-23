package com.burgess.schedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.burgess.schedule.BuildConfig
import com.burgess.schedule.R
import com.burgess.schedule.ui.theme.*
import com.burgess.schedule.ui.morph.MorphSandbox
import com.burgess.schedule.ui.week.WeekGridSandbox

/** Debug entry only. Samples have local fake state and never touch course data. */
@Composable
fun DesignPreview(onClose: (() -> Unit)? = null) {
    if (!BuildConfig.DEBUG) return
    var enabled by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("点击下方组件，检查按压反馈") }
    val c = LocalAppColors.current
    val view = LocalView.current
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window
    val darkBackground = c.bg.luminance() < .5f
    DisposableEffect(dialogWindow, darkBackground) {
        if (dialogWindow == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(dialogWindow, view)
        val previousStatusBars = controller.isAppearanceLightStatusBars
        val previousNavigationBars = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = !darkBackground
        controller.isAppearanceLightNavigationBars = !darkBackground
        onDispose {
            controller.isAppearanceLightStatusBars = previousStatusBars
            controller.isAppearanceLightNavigationBars = previousNavigationBars
        }
    }
    var weekSample by remember { mutableStateOf(false) }
    var morphSample by remember { mutableStateOf(false) }
    if (weekSample) {
        Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
            PillButton("返回组件预览", { weekSample = false }, PillStyle.Ghost, small = true)
            WeekGridSandbox()
        }
        return
    }
    if (morphSample) {
        MorphSandbox(onClose = { morphSample = false })
        return
    }
    Column(
        Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(AppSpace.page),
        verticalArrangement = Arrangement.spacedBy(AppSpace.gap)
    ) {
        if (onClose != null) CircleIconButton(R.drawable.symbol_chevron_left, "关闭设计预览", onClose)
        Text("设计预览", style = AppType.title, color = c.ink)
        SectionLabel("基础组件 · 假数据，不会修改课程")
        PillButton("周视图假数据", { weekSample = true }, PillStyle.Ghost, small = true)
        PillButton("详情转场假数据", { morphSample = true }, PillStyle.Ghost, small = true)
        Text("Inter 08:00 – 11:40", style = AppType.sheetTitle, color = c.ink)
        SectionLabel("中文使用系统字体，时间数字等宽")
        AppCard(Modifier.fillMaxWidth(), onClick = { message = "普通卡片：按下缩小至 98%" }) {
            Text("普通课程卡片", style = AppType.cardTitle, color = c.ink)
            SectionLabel("第 3–4 节 · E209")
        }
        InkCard(Modifier.fillMaxWidth(), onClick = { message = "重点卡片：按下缩小至 98%" }) {
            Text("下一节 · 还有 25 分钟", style = AppType.caption)
            Text("交互设计", style = AppType.sheetTitle)
            Text("第 5–6 节 · E209", style = AppType.caption)
        }
        SectionLabel("按钮与标签")
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
            PillButton("打开弹窗", { sheet = true })
            PillButton("次要操作", { message = "次要按钮：按下缩小至 97%" }, PillStyle.Secondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
            CircleIconButton(R.drawable.symbol_chevron_left, "上一项", { message = "圆形按钮：有边界的水波纹" })
            AppChip("小胶囊", { message = "小胶囊：有边界的水波纹" })
            PillButton("文字操作", { message = "文字操作：有边界的水波纹" }, PillStyle.Ghost, small = true)
        }
        SectionLabel(message)
        Spacer(Modifier.height(AppSpace.gap))
        SectionLabel("设置列表 · 整组一个描边")
        ListGroup {
            ListRow("课程提醒", "按住查看整行反馈", { message = "列表行：整行底色改变，不缩放" }, icon = R.drawable.symbol_notifications)
            HorizontalDivider(color = c.line, thickness = AppSpace.line)
            ListRow("示例开关", if (enabled) "已开启" else "已关闭", { enabled = !enabled }, icon = R.drawable.symbol_palette,
                trailing = { AppSwitch(enabled, { enabled = it }) })
        }
        Spacer(Modifier.height(AppSpace.gap))
        SectionLabel("课程身份色 · 当前主题")
        CoursePalette.forEachIndexed { index, hue ->
            val (background, foreground) = courseTint(hue, c)
            Text("课程 ${index + 1} · Node.js · 08:00", Modifier.fillMaxWidth().background(background, AppShape.cell).padding(AppSpace.card), style = AppType.body, color = foreground)
        }
        SectionLabel("详情转场原型已在上方入口开放，数据不会写入课程表。")
    }
    if (sheet) BottomSheetContainer({ sheet = false }) {
        Text("基础弹窗", style = AppType.sheetTitle, color = c.ink)
        Text("此处只检查圆角、遮罩和按钮，课程详情转场将在阶段 5 单独实现。", style = AppType.body, color = c.mut)
        Spacer(Modifier.height(AppSpace.card))
        PillButton("完成", { sheet = false })
    }
}

@Preview @Composable private fun ComponentsLight() { 课程表Theme(false) { DesignPreview() } }
@Preview @Composable private fun ComponentsDark() { 课程表Theme(true) { DesignPreview() } }
