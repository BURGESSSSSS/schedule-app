@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.burgess.schedule.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.burgess.schedule.ui.theme.*

@Composable fun Modifier.pressAction(onClick: () -> Unit, button: Boolean = false): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) { if (button) Motion.ButtonPressScale else Motion.PressScale } else 1f, tween(Motion.PressMs), label = "按压")
    return graphicsLayer { scaleX = scale; scaleY = scale }.clickable(interaction, null, onClick = onClick)
}
@Composable fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAppColors.current
    Column(modifier.then(if (onClick != null) Modifier.pressAction(onClick) else Modifier).clip(AppShape.card).background(c.card).border(AppSpace.line, c.line, AppShape.card).padding(AppSpace.card), content = content)
}
@Composable fun InkCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.then(if (onClick != null) Modifier.pressAction(onClick) else Modifier).clip(AppShape.hero).background(LocalAppColors.current.ink).padding(AppSpace.hero)) {
        CompositionLocalProvider(LocalContentColor provides LocalAppColors.current.onInk) { content() }
    }
}
enum class PillStyle { Primary, Secondary, Ghost }
@Composable fun PillButton(text: String, onClick: () -> Unit, style: PillStyle = PillStyle.Primary, small: Boolean = false, danger: Boolean = false) {
    val c = LocalAppColors.current
    Box(Modifier.heightIn(min = AppSpace.touch).clip(AppShape.pill).then(if (small) Modifier.clickable(onClick = onClick) else Modifier.pressAction(onClick, true)), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = if (small) AppSpace.smallButton else AppSpace.button).clip(AppShape.pill).background(if (style == PillStyle.Primary) c.ink else Color.Transparent).then(if (style == PillStyle.Secondary) Modifier.border(AppSpace.line, c.line, AppShape.pill) else Modifier).padding(horizontal = AppSpace.card), contentAlignment = Alignment.Center) {
            Text(text, style = AppType.caption, color = if (danger) c.danger else if (style == PillStyle.Primary) c.onInk else c.ink)
        }
    }
}
@Composable fun CircleIconButton(label: String, onClick: () -> Unit) {
    Box(Modifier.size(AppSpace.touch).clip(AppShape.pill).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(AppSpace.iconButton).border(AppSpace.line, LocalAppColors.current.line, AppShape.pill), contentAlignment = Alignment.Center) { Text(label, color = LocalAppColors.current.ink) }
    }
}
@Composable fun AppChip(text: String, onClick: () -> Unit = {}) = PillButton(text, onClick, PillStyle.Secondary, small = true)
@Composable fun SectionLabel(text: String) = Text(text, style = AppType.caption, color = LocalAppColors.current.mut)
@Composable fun ListGroup(content: @Composable ColumnScope.() -> Unit) = AppCard(Modifier.fillMaxWidth(), content = content)
@Composable fun ListRow(title: String, subtitle: String = "", onClick: () -> Unit = {}, trailing: @Composable () -> Unit = { Text("›") }) {
    Row(Modifier.fillMaxWidth().heightIn(min = AppSpace.row).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.cardTitle, color = LocalAppColors.current.ink)
            if (subtitle.isNotEmpty()) SectionLabel(subtitle)
        }
        trailing()
    }
}
@Composable fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val c = LocalAppColors.current
    Switch(checked, onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = c.card, checkedTrackColor = c.ink, uncheckedThumbColor = c.card, uncheckedTrackColor = c.line, uncheckedBorderColor = Color.Transparent))
}
@Composable fun BottomSheetContainer(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = AppShape.sheet, containerColor = LocalAppColors.current.card, scrimColor = LocalAppColors.current.scrim, tonalElevation = AppSpace.line * 0) {
        Column(Modifier.fillMaxWidth().padding(AppSpace.page), content = content)
    }
}
@Composable fun AppSnackbar(data: SnackbarData) { Snackbar(data, containerColor = LocalAppColors.current.ink, contentColor = LocalAppColors.current.onInk, actionColor = LocalAppColors.current.onInk) }
@Composable fun DesignPreview() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(LocalAppColors.current.bg).verticalScroll(rememberScrollState()).padding(AppSpace.page), verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
        Text("设计预览", style = AppType.title, color = LocalAppColors.current.ink)
        AppCard { Text("普通卡片", color = LocalAppColors.current.ink) }
        InkCard { Text("下一节 · 课程") }
        PillStyle.entries.forEach { PillButton(it.name, {}, it) }
        CircleIconButton("‹", {})
        AppChip("课程标签")
        ListGroup { ListRow("列表行", "说明文字"); HorizontalDivider(); ListRow("开关", trailing = { AppSwitch(true, {}) }) }
        listOf(LightAppColors, DarkAppColors).forEach { colors ->
            CoursePalette.forEachIndexed { index, hue ->
                val (bg, fg) = courseTint(hue, colors)
                Text("课程色 ${index + 1} · 08:00", Modifier.fillMaxWidth().background(bg, AppShape.cell).padding(AppSpace.card), color = fg)
            }
        }
        if (com.burgess.schedule.BuildConfig.DEBUG) {
            SectionLabel("动效倍率 ${"%.1f".format(Motion.Scale)}×")
            Slider(value = Motion.Scale, onValueChange = { value -> Motion.Scale = value; scope.launch { Appearance.setScale(context, value) } }, valueRange = .6f..1.6f)
        }
    }
}
@Preview @Composable private fun ComponentsLight() { 课程表Theme(false) { DesignPreview() } }
@Preview @Composable private fun ComponentsDark() { 课程表Theme(true) { DesignPreview() } }
