package com.burgess.schedule.ui.morph

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.burgess.schedule.ui.components.CircleIconButton
import com.burgess.schedule.ui.components.PillButton
import com.burgess.schedule.ui.components.PillStyle
import com.burgess.schedule.ui.components.SectionLabel
import com.burgess.schedule.ui.theme.AppSpace
import com.burgess.schedule.ui.theme.AppType
import com.burgess.schedule.ui.theme.LocalAppColors
import com.burgess.schedule.ui.theme.courseTint
import com.burgess.schedule.R

/** 5A only: fake courses exercise the root overlay without Room or real pages. */
@Composable
fun MorphSandbox(onClose: (() -> Unit)? = null) {
    val colors = LocalAppColors.current
    val controller = rememberMorphController()
    var message by remember { mutableStateOf("点击一张假课程卡片开始转场") }
    var closeEvents by remember { mutableStateOf(0) }
    var editEvents by remember { mutableStateOf(0) }
    var deleteEvents by remember { mutableStateOf(0) }
    val color = courseTint(com.burgess.schedule.ui.theme.CoursePalette[1], colors)
    val sourceRows = remember(colors) {
        listOf(
            DemoCourse(101, "white-101", "高等数学", "第 1–2 节 · E209", "08:00 – 09:40", "E209", colors.card, colors.ink, 20.dp, hue = color.second),
            DemoCourse(102, "black-102", "交互设计", "第 5–6 节 · A301", "14:00 – 15:40", "A301", colors.ink, colors.onInk, 24.dp, hue = color.second),
            DemoCourse(105, "long-title-105", "习近平新时代中国特色社会主义思想概论", "第 7–8 节 · B204", "16:00 – 17:40", "本校区二号教学楼[二号教学楼B204]", colors.card, colors.ink, 20.dp, hue = color.second),
            DemoCourse(106, "sport-106", "大学体育(三)", "第 9–10 节 · 体育场", "18:00 – 19:40", "", colors.card, colors.ink, 20.dp, hue = color.second)
        )
    }
    Column(Modifier.fillMaxSize().background(colors.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AppSpace.page, vertical = AppSpace.card),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onClose != null) CircleIconButton(R.drawable.symbol_chevron_left, "返回设计预览", onClose)
            Spacer(Modifier.width(AppSpace.gap))
            Column(Modifier.weight(1f)) {
                Text("详情转场假数据", style = AppType.cardTitle, color = colors.ink)
                Text("5A · 仅用于检查来源连续变形", style = AppType.caption, color = colors.mut)
            }
        }
        MorphHost(
            controller = controller,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onExitFinished = { action ->
                message = when (action) {
                    MorphExitAction.Close -> {
                        closeEvents++
                        "已完成收起，来源卡片恢复"
                    }
                    MorphExitAction.Edit -> {
                        editEvents++
                        "已完成收起，编辑回调在此结束"
                    }
                    MorphExitAction.Delete -> {
                        deleteEvents++
                        "已完成收起，删除回调在此结束"
                    }
                }
            }
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AppSpace.page),
                verticalArrangement = Arrangement.spacedBy(AppSpace.gap)
            ) {
                SectionLabel(message)
                sourceRows.forEach { item ->
                    DemoSourceCard(controller, item)
                }
                SectionLabel("周视图窄格假数据 · 两个位置")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DemoSourceCard(controller, DemoCourse(103, "color-103-a", "Node.js", "第 3–4 节", "10:00 – 11:40", "本校区实训中心E区[数智基地29-实训中心E209]", color.first, color.second, 12.dp, compact = true, hue = color.second, detailTitle = "Node.js应用开发"))
                    DemoSourceCard(controller, DemoCourse(104, "color-104-b", "习近平新", "第 7–8 节", "16:00 – 17:40", "本校区二号教学楼[二号教学楼B204]", color.first, color.second, 12.dp, compact = true, hue = color.second, detailTitle = "习近平新时代中国特色社会主义思想概论"))
                    DemoSourceCard(controller, DemoCourse(107, "color-107-c", "产品原型", "第 11–12 节", "20:00 – 21:40", "A302", color.first, color.second, 12.dp, compact = true, hue = color.second, detailTitle = "产品原型设计", compactWidth = 55.dp))
                }
                Spacer(Modifier.height(AppSpace.card))
                Text("Debug 控制（仅原型）", style = AppType.cardTitle, color = colors.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                    PillButton("0.5×", { controller.updateSpeed(.5f) }, if (controller.speed == .5f) PillStyle.Primary else PillStyle.Secondary, small = true)
                    PillButton("1×", { controller.updateSpeed(1f) }, if (controller.speed == 1f) PillStyle.Primary else PillStyle.Secondary, small = true)
                    PillButton("2×", { controller.updateSpeed(2f) }, if (controller.speed == 2f) PillStyle.Primary else PillStyle.Secondary, small = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("关闭动画", style = AppType.body, color = colors.ink)
                        Text("仍执行同一清理路径", style = AppType.caption, color = colors.mut)
                    }
                    Switch(checked = !controller.animationsEnabled, onCheckedChange = { controller.updateAnimationsEnabled(!it) })
                }
                SectionLabel("完成事件 · 关闭 $closeEvents · 编辑 $editEvents · 删除 $deleteEvents")
                MorphProgressDebug(controller)
                Spacer(Modifier.height(AppSpace.navSpace))
            }
        }
    }
}

private data class DemoCourse(
    val id: Long,
    val key: String,
    val title: String,
    val periods: String,
    val time: String,
    val room: String,
    val fill: Color,
    val foreground: Color,
    val radius: androidx.compose.ui.unit.Dp,
    val compact: Boolean = false,
    val hue: Color = foreground,
    val detailTitle: String = title,
    val compactWidth: androidx.compose.ui.unit.Dp = 64.dp
)

@Composable
private fun DemoSourceCard(controller: MorphController, item: DemoCourse) {
    val detail = MorphDetails(item.id, item.detailTitle, item.periods, item.time, item.room, item.hue)
    val style = MorphSourceStyle(
        fill = item.fill,
        cornerRadius = item.radius,
        foreground = item.foreground,
        outline = LocalAppColors.current.line,
        outlineWidth = if (item.key.startsWith("white")) 1.dp else 0.dp
    )
    MorphSource(
        controller = controller,
        instanceKey = item.key,
        details = detail,
        style = style,
        modifier = if (item.compact) Modifier.width(item.compactWidth).height(104.dp) else Modifier.fillMaxWidth().height(94.dp),
        onOpen = {}
    ) { foregroundOverride ->
        val foreground = foregroundOverride ?: item.foreground
        Column(Modifier.fillMaxSize().padding(if (item.compact) 6.dp else AppSpace.card), verticalArrangement = Arrangement.Center) {
            Title(
                item.title,
                style = if (item.compact) AppType.cell else AppType.cardTitle,
                color = foreground,
                maxLines = if (item.compact) 2 else 1
            )
            Spacer(Modifier.height(if (item.compact) 2.dp else 4.dp))
            Text(item.periods, style = if (item.compact) AppType.micro else AppType.caption, color = foreground.copy(alpha = .78f), maxLines = 2)
        }
    }
}

@Composable
private fun MorphProgressDebug(controller: MorphController) {
    val colors = LocalAppColors.current
    Text("逐帧起点 ${(controller.debugProgress * 100).toInt()}%（打开前拖动，点卡片即停在该帧）", style = AppType.caption, color = colors.mut)
    Slider(value = controller.debugProgress, onValueChange = controller::seekDebug, valueRange = 0f..1f)
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
        PillButton("t=.20", { controller.seekDebug(.20f) }, PillStyle.Ghost, small = true)
        PillButton("t=.35", { controller.seekDebug(.35f) }, PillStyle.Ghost, small = true)
        PillButton("t=.45", { controller.seekDebug(.45f) }, PillStyle.Ghost, small = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
        PillButton("t=.55", { controller.seekDebug(.55f) }, PillStyle.Ghost, small = true)
        PillButton("t=.75", { controller.seekDebug(.75f) }, PillStyle.Ghost, small = true)
        PillButton("t=.46", { controller.seekDebug(.46f) }, PillStyle.Ghost, small = true)
        PillButton("t=.47", { controller.seekDebug(.47f) }, PillStyle.Ghost, small = true)
        PillButton("t=.48", { controller.seekDebug(.48f) }, PillStyle.Ghost, small = true)
    }
}
