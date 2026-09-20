@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.burgess.schedule.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.annotation.DrawableRes
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import com.burgess.schedule.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.burgess.schedule.ui.theme.*

@Composable fun Modifier.pressAction(onClick: () -> Unit, button: Boolean = false): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) { if (button) Motion.ButtonPressScale else Motion.PressScale } else 1f, tween(Motion.PressMs, easing = Motion.EmphasizedDecelerate), label = "按压")
    return graphicsLayer { scaleX = scale; scaleY = scale }.clickable(interaction, null, onClick = onClick)
}
@Composable fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, color: Color = LocalAppColors.current.card, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAppColors.current
    Column(modifier.then(if (onClick != null) Modifier.pressAction(onClick) else Modifier).clip(AppShape.card).background(color).border(AppSpace.line, c.line, AppShape.card).padding(AppSpace.card), content = content)
}
@Composable fun InkCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.then(if (onClick != null) Modifier.pressAction(onClick) else Modifier).clip(AppShape.hero).background(LocalAppColors.current.ink).padding(AppSpace.hero)) {
        CompositionLocalProvider(LocalContentColor provides LocalAppColors.current.onInk) { content() }
    }
}
enum class PillStyle { Primary, Secondary, Ghost }
@Composable fun PillButton(text: String, onClick: () -> Unit, style: PillStyle = PillStyle.Primary, small: Boolean = false, danger: Boolean = false) {
    val c = LocalAppColors.current
    Box(Modifier.heightIn(min = AppSpace.touch).clip(AppShape.pill).then(if (small) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier.pressAction(onClick, true)), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = if (small) AppSpace.smallButton else AppSpace.button).clip(AppShape.pill).background(if (style == PillStyle.Primary) c.ink else Color.Transparent).then(if (style == PillStyle.Secondary) Modifier.border(AppSpace.line, c.line, AppShape.pill) else Modifier).padding(horizontal = AppSpace.card), contentAlignment = Alignment.Center) {
            Text(text, style = AppType.caption, color = if (danger) c.danger else if (style == PillStyle.Primary) c.onInk else c.ink)
        }
    }
}
/** Icons are the upstream Material Symbols Rounded 400 outlines, tinted by the theme. */
@Composable fun AppSymbol(@DrawableRes icon: Int, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(icon), description, modifier.size(AppSpace.glyph), tint = LocalAppColors.current.ink)
}
@Composable fun CircleIconButton(label: String, onClick: () -> Unit) {
    CircleIconButton(
        if (label == "‹") R.drawable.symbol_chevron_left else R.drawable.symbol_chevron_right,
        if (label == "‹") "返回或上一项" else "下一项", onClick
    )
}
@Composable fun CircleIconButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    Box(Modifier.size(AppSpace.touch).clip(AppShape.pill).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(AppSpace.iconButton).border(AppSpace.line, LocalAppColors.current.line, AppShape.pill), contentAlignment = Alignment.Center) {
            AppSymbol(icon, description)
        }
    }
}
@Composable fun AppChip(text: String, onClick: () -> Unit = {}) = PillButton(text, onClick, PillStyle.Secondary, small = true)
@Composable fun SectionLabel(text: String) = Text(text, style = AppType.caption, color = LocalAppColors.current.mut)
@Composable fun ListGroup(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAppColors.current
    Column(Modifier.fillMaxWidth().clip(AppShape.card).background(c.card).border(AppSpace.line, c.line, AppShape.card), content = content)
}
@Composable fun ListRow(title: String, subtitle: String = "", onClick: () -> Unit = {}, @DrawableRes icon: Int? = null, trailing: @Composable () -> Unit = { AppSymbol(R.drawable.symbol_chevron_right) }) {
    val c = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = AppSpace.row)
            .drawBehind { if (pressed) drawRect(c.ink.copy(alpha = .06f)) }
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = AppSpace.card, vertical = AppSpace.gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)
    ) {
        if (icon != null) Box(Modifier.size(AppSpace.icon).background(c.ink.copy(alpha = .06f), AppShape.cell), contentAlignment = Alignment.Center) { AppSymbol(icon) }
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.body, color = c.ink)
            if (subtitle.isNotEmpty()) SectionLabel(subtitle)
        }
        trailing()
    }
}
@Composable fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val c = LocalAppColors.current
    Switch(checked, onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = c.onInk, checkedTrackColor = c.ink, uncheckedThumbColor = c.onInk, uncheckedTrackColor = c.line, uncheckedBorderColor = Color.Transparent))
}
@Composable fun BottomSheetContainer(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = AppShape.sheet, containerColor = LocalAppColors.current.card, scrimColor = LocalAppColors.current.scrim, tonalElevation = AppSpace.line * 0) {
        Column(Modifier.fillMaxWidth().padding(AppSpace.page), content = content)
    }
}
@Composable fun AppSnackbar(data: SnackbarData) { Snackbar(data, containerColor = LocalAppColors.current.ink, contentColor = LocalAppColors.current.onInk, actionColor = LocalAppColors.current.onInk) }
