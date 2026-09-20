package com.burgess.schedule.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import com.burgess.schedule.ui.theme.*

@Composable fun FloatingNavigation(selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalAppColors.current
    val haptics = LocalHapticFeedback.current
    Box(Modifier.fillMaxWidth().navigationBarsPadding().height(AppSpace.navSpace), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = AppSpace.page).shadow(AppSpace.navElevation, AppShape.pill).clip(AppShape.pill).background(colors.card.copy(alpha = .88f)).border(AppSpace.line, colors.line, AppShape.pill).padding(AppSpace.gridGap)) {
            val itemWidth = maxWidth / 3
            val offset by animateDpAsState(itemWidth * selected, tween(Motion.TabCrossfadeMs, easing = Motion.EmphasizedDecelerate), label = "导航选中背景")
            // Read the animated offset during placement, without recomposing all tabs per frame.
            Box(Modifier.offset { IntOffset(offset.roundToPx(), 0) }.width(itemWidth).height(AppSpace.touch).background(colors.ink, AppShape.pill))
            Row(Modifier.selectableGroup()) {
                listOf("今日", "周视图", "更多").forEachIndexed { index, title ->
                    Box(Modifier.width(itemWidth).heightIn(min = AppSpace.touch).clip(AppShape.pill).selectable(selected = selected == index, role = Role.Tab, onClick = {
                        if (index != selected) { runCatching { haptics.performHapticFeedback(HapticFeedbackType.ContextClick) }; onSelect(index) }
                    }), contentAlignment = Alignment.Center) {
                        Text(title, style = AppType.caption, color = if (selected == index) colors.onInk else colors.mut)
                    }
                }
            }
        }
    }
}
@Preview @Composable private fun NavigationLight() { 课程表Theme(false) { FloatingNavigation(0, {}) } }
@Preview @Composable private fun NavigationDark() { 课程表Theme(true) { FloatingNavigation(1, {}) } }
