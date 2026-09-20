package com.burgess.schedule.ui.components
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.burgess.schedule.ui.theme.*
@Composable fun SubPageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(LocalAppColors.current.bg).verticalScroll(rememberScrollState()).padding(AppSpace.page), verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
        CircleIconButton("‹", onBack)
        Text(title, style = AppType.title, color = LocalAppColors.current.ink)
        content()
    }
}
@Preview @Composable private fun SubPageLight() { 课程表Theme(false) { SubPageScaffold("课程提醒", {}) { SectionLabel("设置与工具") } } }
@Preview @Composable private fun SubPageDark() { 课程表Theme(true) { SubPageScaffold("课程提醒", {}) { SectionLabel("设置与工具") } } }
