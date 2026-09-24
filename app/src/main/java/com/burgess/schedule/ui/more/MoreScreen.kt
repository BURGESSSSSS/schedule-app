package com.burgess.schedule.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import com.burgess.schedule.BuildConfig
import com.burgess.schedule.Hairline
import com.burgess.schedule.Ink
import com.burgess.schedule.MutedInk
import com.burgess.schedule.R
import com.burgess.schedule.ui.components.*
import com.burgess.schedule.ui.theme.*

internal data class DevLogSection(
    val title: String,
    val body: String
)

internal data class DevLogEntry(
    val version: String,
    val date: String,
    val intro: String,
    val sections: List<DevLogSection>,
    val closing: String
)

internal val devLogEntries = listOf(
    DevLogEntry(
        version = "1.0.0",
        date = "2026年8月23日",
        intro = "针对日常使用商务课表时需要频繁打开网页、等待加载，以及课程表截图保存后查找不便等问题，我开发了这款简单、轻量的离线课程表软件。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 离线 OCR 课程表识别",
                "软件采用离线 OCR 截图识别，并针对商务课表的排版特点进行了适配与优化。\n不过，由于商务课表本身排版较为复杂，不同情况下可能存在识别偏差，因此目前无法保证 100% 的识别准确率。\n如果出现课程名称、时间、地点等信息识别错误，可以直接使用软件内的编辑功能进行修改，无需重新识别。"
            ),
            DevLogSection(
                "2. 上课通知提醒",
                "1.0.0 版本新增课程通知功能。\n在「更多」页面开启通知权限后，软件会在上课前 10 分钟发送通知，提醒下一节课程的课程时间、上课地点，帮助大家减少错过课程或临时翻找课表的情况。"
            ),
            DevLogSection(
                "📌 关于目前版本",
                "目前软件还处于初期开发阶段，功能和体验仍有进一步完善的空间。\n如果你在使用过程中遇到问题，或者有更好的功能建议，欢迎向我反馈。你的每一条意见都会成为后续版本优化的重要参考。\n也欢迎大家把这款软件推荐给身边有需要的同学，一起让它变得更好用。"
            ),
            DevLogSection(
                "🔮 后续计划",
                "目前下一版本已经有一些初步规划，其中比较希望加入的是淘宝取件码快捷查询。\n希望将日常学习、生活中常用的信息进一步整合到软件中，减少不同应用之间来回切换的麻烦，后续还会根据大家的实际使用反馈，持续完善功能。"
            )
        ),
        closing = "感谢每一位愿意尝试这款软件的同学，1.0.0 只是开始，欢迎提出建议，也期待和大家一起把它做得更好。"
    ),
    DevLogEntry(
        version = "1.0.1",
        date = "2026年8月28日",
        intro = "1.0.1 是一次集中解决流畅度的更新。之前版本的光幕背景动画每一帧都在计算全屏模糊，手机压力很大，不少同学反馈使用时卡顿、发热，这次把它彻底解决了。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 流畅度优化",
                "背景光幕改为静态绘制，去掉了逐帧计算的实时模糊。\n现在滑动课程表、切换页面应该明显更跟手，长时间挂着也不会持续发热耗电。"
            ),
            DevLogSection(
                "2. 正式版安装包",
                "从这一版开始提供开启完整优化的正式安装包，安装体积更小，运行效率更高。"
            ),
            DevLogSection(
                "📌 覆盖安装说明",
                "如果你之前安装的是调试版本，直接覆盖安装新版即可，已录入的课程数据会完整保留，不需要重新导入。"
            )
        ),
        closing = "流畅度问题解决之后，我会把精力放回功能开发上。如果你的手机上仍然感觉卡顿，欢迎告诉我你的手机型号，我会继续排查。"
    ),
    DevLogEntry(
        version = "1.0.2",
        date = "2026年8月28日",
        intro = "1.0.2 是一次界面打磨版本。核心功能稳定下来之后，这一版按照 Material 3 的设计规范把细节重新梳理了一遍，让软件看起来更清爽、用起来更舒服。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 视觉全面打磨",
                "统一了字号、间距和留白节奏，首页标题更大更醒目，卡片之间呼吸感更好，整体配色保持克制。"
            ),
            DevLogSection(
                "2. 按压弹性反馈",
                "课程卡片和功能入口在按下时会有轻微的弹性缩放，松手后回弹，操作手感更真实。"
            ),
            DevLogSection(
                "3. 新增开发日志",
                "「更多」页面新增「开发日志」入口，你正在看的这个页面就是它。以后每次更新我都会在这里记录改动和想法。"
            ),
            DevLogSection(
                "🔮 后续计划",
                "下一版本计划加入淘宝取件码快捷查询，把常用信息进一步整合到软件里。有功能建议随时反馈。"
            )
        ),
        closing = "感谢每一位愿意尝试这款软件的同学，欢迎继续提出建议，也期待和大家一起把它做得更好。"
    ),
    DevLogEntry(
        version = "1.1.0",
        date = "2026年8月28日",
        intro = "1.1.0 是一次界面全面改版。参考 OpenAI 官网的设计语言，整个软件换成了一套安静的中性视觉：白底、黑字、细线分隔，只在状态标识上保留一抹绿色。课程格子也按可读性优先的原则重新设计，课程名称完整清晰是第一位的。所有功能和使用习惯保持不变。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 全新视觉语言",
                "首页、周视图、更多页面统一为纯白卡片加 1px 细线分隔，标题更大更利落；界面固定使用浅色主题，无论系统如何设置，打开永远是最清晰的样子。"
            ),
            DevLogSection(
                "2. 课程格子重做",
                "课程用低饱和的课程色作为格子底色，课程名称加粗完整显示，教室紧随其后；正在上的课会有绿色描边和「进行中」标识，扫一眼就能找到重点。"
            ),
            DevLogSection(
                "3. 状态强调色",
                "「正在进行」和「下一节课」统一用绿色小圆点和描边标识，按下卡片和按钮时仍有轻微的弹性反馈，操作手感更踏实。"
            ),
            DevLogSection(
                "🔮 后续计划",
                "界面稳定之后，下一版本计划加入淘宝取件码快捷查询，把常用信息进一步整合进来。"
            )
        ),
        closing = "这次改版把「读得清楚」放在了「好看」前面。如果你觉得哪里还不够清楚，随时告诉我。"
    ),
    DevLogEntry(
        version = "1.2.0",
        date = "2026年8月29日",
        intro = "1.2.0 新增了「取件码」功能。快递到驿站后，可以直接在软件里查看和复制取件码，不用再打开淘宝、找入口、等加载。所有数据只保存在手机本地。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 取件码",
                "「更多」页新增「取件码」入口，可以在这里查看取件码、一键复制并标记已取，取完的包裹会自动从列表收起。"
            ),
            DevLogSection(
                "2. 包裹页直显",
                "内嵌的淘宝包裹页只需登录一次，之后从软件里一键直达。今日页也会在有包裹待取时出现一张快捷卡片，看课的同时顺手就能把码复制了。"
            ),
            DevLogSection(
                "3. 说明",
                "现阶段取件码以包裹页直显为主，自动识别快递通知、把新包裹自动收进列表的能力会在后续版本加入。"
            ),
            DevLogSection(
                "🔮 后续计划",
                "下一版本计划让取件码自动从快递通知里获取，做到零手动操作。"
            )
        ),
        closing = "功能还在早期，使用中有任何问题欢迎反馈。"
    ),
    DevLogEntry(
        version = "1.3.0",
        date = "2026年9月19日",
        intro = "1.3.0 继续完善课程表的日常使用体验：优先保证已有课程和取件码不丢失，再把日期刷新、周末课程和导入安全补齐。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 课程数据更稳",
                "导入替换改为事务写入，确认后的截图识别结果要么完整替换目标周，要么保留原数据；复制到下周会跳过重复课程。"
            ),
            DevLogSection(
                "2. 周视图和编辑",
                "当前时间会定期刷新，跨天或跨时段后今日页和进行中标识会自动更新。有周末课程时，周六日以独立网格显示，不压窄常规周一至周五列；编辑课程可直接选择周六日、教师和起止节次。"
            ),
            DevLogSection(
                "3. 视觉和反馈",
                "保留白、黑、灰和绿色状态强调，页面与周切换只使用短暂轻量过渡；课程详情继续支持展开，课程卡片和主要入口保留按压反馈。"
            )
        ),
        closing = "课程数据、取件码和包裹页仍然保存在本机，通知权限和测试结果以系统实际反馈为准。"
    ),
    DevLogEntry(
        version = "1.3.1",
        date = "2026年9月20日",
        intro = "1.3.1 面向日常打开体验做了一次视觉返工：补齐中性色角色，重做编辑表单与周视图排版，并让关键切换更有方向感。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 表单和主题",
                "编辑与截图导入面板改为白底分组表单，边线、焦点和键盘滚动状态更清楚。"
            ),
            DevLogSection(
                "2. 周视图",
                "周视图按可用高度调整格子，课程名称和地点按空间自然换行，复制入口收紧为小操作。"
            ),
            DevLogSection(
                "3. 轻量动效",
                "今日页加入层级更清晰的概览，周切换、课程详情和面板进出增加轻量滑动、缩放与按压反馈。"
            )
        ),
        closing = "课程数据、通知、取件码和包裹页行为保持不变，本版仅调整展示与交互反馈。"
    ),
    DevLogEntry(
        version = "1.3.2",
        date = "2026年9月20日",
        intro = "1.3.2 聚焦今日页、周切换和课程详情的显示稳定性，保留现有数据与系统功能。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 今日页与课程格",
                "今日页改为轻量白底头部；课程格收紧留白，地点清理 OCR 空格后自然换行，进行中的课程只保留细绿色强调。"
            ),
            DevLogSection(
                "2. 切换与详情",
                "周视图按整周快照滑动切换，课程详情改为同页底部面板，遮罩与面板同步进出。"
            )
        ),
        closing = "通知、签名、数据库和取件功能保持不变，本版仅调整四处界面展示与交互反馈。"
    )
)

@Composable
internal fun DevLogScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    SubPageScaffold("开发日志", onBack) {
        devLogEntries.asReversed().forEachIndexed { index, entry ->
            key(entry.version) {
                var expanded by remember { mutableStateOf(index == 0) }
                com.burgess.schedule.ui.components.AppCard(Modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
                    Text(entry.version, style = AppType.cardTitle, color = Ink)
                    SectionLabel(entry.date)
                    AnimatedVisibility(expanded, enter = fadeIn(tween(Motion.TabCrossfadeMs)), exit = fadeOut(tween(Motion.TabCrossfadeMs))) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                            if (entry.intro.isNotBlank()) Text(entry.intro, style = AppType.body, color = MutedInk)
                            entry.sections.forEach { section ->
                                Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                                    Box(Modifier.padding(top = AppSpace.cellVertical).size(AppSpace.gridGap).background(MutedInk, AppShape.pill))
                                    Column {
                                        Text(section.title, style = AppType.cardTitle, color = Ink)
                                        if (section.body.isNotBlank()) Text(section.body, style = AppType.body, color = MutedInk)
                                    }
                                }
                            }
                            if (entry.closing.isNotBlank()) Text(entry.closing, style = AppType.body, color = MutedInk)
                        }
                    }
                }
            }
        }
    }
}
@Preview @Composable private fun DevLogLight() { 课程表Theme(false) { DevLogScreen({}) } }

@Composable
internal fun MoreScreen(onReminderClick: () -> Unit, onDevLogClick: () -> Unit, onDeveloperClick: () -> Unit, onLocalAdbSmokeClick: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(AppSpace.page),
        verticalArrangement = Arrangement.spacedBy(AppSpace.gap)
    ) {
        Text("更多", style = AppType.title, color = Ink)
        SectionLabel("课程")
        ListGroup {
            ListRow("课程提醒", "通知权限和测试通知", onReminderClick, icon = R.drawable.symbol_notifications)
        }
        SectionLabel("关于")
        ListGroup {
            ListRow("开发日志", "查看更新记录", onDevLogClick, icon = R.drawable.symbol_history)
            HorizontalDivider(color = Hairline)
            ListRow("关于开发者", "Arihara", onDeveloperClick, icon = R.drawable.symbol_mail)
        }
        if (BuildConfig.DEBUG) {
            SectionLabel("开发调试")
            ListGroup {
                ListRow("Local ADB 最小验证", "Wireless Debugging 配对与 shell id", onLocalAdbSmokeClick)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("课程表 ${BuildConfig.VERSION_NAME} · 数据仅保存在本机", Modifier.fillMaxWidth(), style = AppType.caption, color = MutedInk, textAlign = TextAlign.Center)
    }
}
@Composable
internal fun DeveloperScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    SubPageScaffold("关于开发者", onBack) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = AppSpace.card),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpace.gap)
        ) {
            Image(
                painterResource(R.drawable.developer_avatar),
                contentDescription = "开发者头像",
                modifier = Modifier.size(112.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Text("Arihara", style = AppType.cardTitle, color = Ink)
            SelectionContainer {
                Text("suyu20651@gmail.com", style = AppType.body, color = MutedInk)
            }
            Text("为爱发电", style = AppType.caption, color = MutedInk)
        }
    }
}

@Preview @Composable private fun MoreLight() { 课程表Theme { MoreScreen({}, {}, {}) } }
