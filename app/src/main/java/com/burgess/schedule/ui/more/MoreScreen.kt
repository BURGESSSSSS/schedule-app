package com.burgess.schedule.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
                "首页、周视图和更多页面统一为白底、黑字与浅灰细线，标题、卡片和留白也重新梳理。界面固定使用浅色主题，减少不同页面之间的视觉跳变。"
            ),
            DevLogSection(
                "2. 课程格子重做",
                "周视图课程格改用低饱和课程色，名称、节次和地点层级更清楚；当前课程用细绿色描边和「进行中」提示，让一周安排更容易扫读。"
            ),
            DevLogSection(
                "3. 页面与操作反馈",
                "今日页、周视图和更多页面采用一致的卡片与按钮样式。课程卡片和常用入口保留轻微按压反馈，点击时有回应，松开后回到原位。"
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
                "「更多」页新增「取件码」入口，可以查看和复制取件码，也能标记包裹已取；处理完成的条目会从待取列表中收起。相关记录保存在本机。"
            ),
            DevLogSection(
                "2. 包裹页直显",
                "新增包裹页快捷入口，登录后可以从软件内打开查看。今日页在有待取包裹时也会显示快捷卡片，查看课表时就能顺手找到取件信息。"
            ),
            DevLogSection(
                "3. 当前使用方式",
                "这一版以包裹页查看和手动管理为主，暂不自动读取快递通知。取件码可以复制、标记状态，避免把尚未处理的包裹和已取件记录混在一起。"
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
                "1. 截图导入和课程数据",
                "确认截图识别结果后再替换目标周，写入过程中如果发生错误会回滚，避免只替换一部分课程。复制到下周时会检查重复课程，已有课程不会因为重复操作不断增加。"
            ),
            DevLogSection(
                "2. 周视图和编辑",
                "课程时间会随日期和时段刷新，今日页与「进行中」状态不会一直停留在旧时间。有周末课程时才显示周六、周日列，不压缩周一至周五；编辑课程也可以设置教师、周末日期和起止节次。"
            ),
            DevLogSection(
                "3. 提醒与日常反馈",
                "更多页面集中课程提醒入口，方便查看通知权限和测试提醒。页面切换和周切换采用轻量过渡，课程卡片与主要按钮继续保留清楚的按压反馈。"
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
                "1. 编辑和截图导入",
                "课程编辑与截图导入面板整理为分组表单，输入框的边线和焦点状态更明确；打开键盘后仍能滚动到正在编辑的内容，确认操作也更容易找到。"
            ),
            DevLogSection(
                "2. 周视图",
                "周视图按可用屏幕高度安排课程格，名称和地点可以自然换行；复制到下周等辅助操作收紧到较轻的按钮，不和课程内容抢空间。"
            ),
            DevLogSection(
                "3. 页面切换和详情",
                "今日页的信息层级重新整理，周切换、课程详情和面板进出加入轻量过渡。卡片展开为详情时保持课程内容连续，减少页面突然跳变的感觉。"
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
                "今日页改为更轻的白底头部，课程信息仍按上课时间排列。周视图收紧课程格留白，地点会先清理截图识别带来的多余空格再换行显示；当前课程保留细绿色强调。"
            ),
            DevLogSection(
                "2. 周切换与课程详情",
                "周视图按整周内容切换，避免切周时日期标题和课程格短暂对不上。课程详情改为同页底部面板，打开、关闭时遮罩和面板同步进出，也可以通过返回操作收起。"
            )
        ),
        closing = "通知、签名、数据库和取件功能保持不变，本版仅调整四处界面展示与交互反馈。"
    ),
    DevLogEntry(
        version = "1.3.3",
        date = "2026年9月26日",
        intro = "1.3.3 开始完善学校课表导入。先把整学期课表采集和导入前预览整理出来，让每周采集情况都能检查清楚。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 整学期采集",
                "可以从企业微信教务课表逐周收集课程，按学期查看已采集周次和覆盖情况。每周数据先保存在本机的采集记录中，采集完成后再统一检查，不会在过程中直接改动正式课程表。"
            ),
            DevLogSection(
                "2. 导入前预览",
                "整学期数据会先生成预览，检查课程数量、周次范围和可能的重复项。覆盖不完整时会提示继续检查，避免把只采集到一部分的学期当成完整课表导入。"
            )
        ),
        closing = "先检查清楚整学期的采集结果，再决定是否导入，让课表变更始终由你确认。"
    ),
    DevLogEntry(
        version = "1.3.4",
        date = "2026年9月26日",
        intro = "1.3.4 把学校课表从预览推进到正式导入，并补上了重复同步和课程变化的处理。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 导入前检查",
                "确认导入前会逐项列出新增、更新、无变化和学校课表中已不再出现的课程。遇到身份重复或来源冲突时会停止导入并说明冲突数量，不会自行合并或跳过。"
            ),
            DevLogSection(
                "2. 按每周课次识别学校课程",
                "同一课程的不同教学周分别识别。地点或教师变化时更新对应课次，新增加的周次单独补入；学校课程中缺少的周次会列出来供检查，不会自动删除。"
            ),
            DevLogSection(
                "3. 保留其他课程",
                "学校来源标识随课程一起保存，重复导入可以识别原有学校课次。手动添加和截图识别的课程不会被学校数据覆盖，所有确认写入都在一次数据库事务中完成。"
            )
        ),
        closing = "正式写入采用事务处理，遇到错误会保留原课程表。"
    ),
    DevLogEntry(
        version = "1.3.5",
        date = "2026年9月26日",
        intro = "1.3.5 将学校课表导入整理成日常可用的页面，并修复了同步页面打开时的闪退问题。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 同步入口和操作流程",
                "在「更多」中加入学校课表同步页面，从连接无线调试到采集、检查覆盖和预览导入，都在页面内按步骤说明。完成检查后由你确认，才会把学校课表写入正式课程表。"
            ),
            DevLogSection(
                "2. 状态与页面稳定性",
                "页面会显示连接和采集状态，出现问题时给出对应阶段提示，方便知道下一步该检查哪里。修复学校同步页面重复纵向滚动导致的闪退，页面可以正常打开和上下查看。"
            )
        ),
        closing = "学校课表仍由你主动采集并确认导入，已有课程数据会按导入计划处理。"
    ),
    DevLogEntry(
        version = "1.3.6",
        date = "2026年9月27日",
        intro = "1.3.6 是这轮迭代的收尾更新，重点补齐周视图地点显示和课程表备份恢复。",
        sections = listOf(
            DevLogSection("✨ 本次更新", ""),
            DevLogSection(
                "1. 课程名称和地点",
                "周视图会优先显示地点括号里的完整内容，保留基地名称、实训中心和教室号，不再只留下短教室号。地点会在课程格里换行显示；课程名称也可以占两行，窄屏下先保留课程信息再利用剩余空间。"
            ),
            DevLogSection(
                "2. 课程表备份与恢复",
                "新增 UTF-8 JSON 课程表文件，可通过系统文件选择器保存或打开。选择文件后先查看备份日期、版本和课程数量，再选择合并或恢复；恢复会二次确认并在事务中完成，学校课程的同步来源标识也会保留，方便以后继续识别原课次。"
            )
        ),
        closing = "从截图识别、手动编辑到学校课表导入和文件备份，常用的课程管理流程终于补齐了。数据仍由你自己选择保存位置，也感谢大家一路以来的支持和反馈。"
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
internal fun MoreScreen(
    onReminderClick: () -> Unit,
    onDevLogClick: () -> Unit,
    onDeveloperClick: () -> Unit,
    onSchoolSyncClick: () -> Unit = {},
    onSchoolSyncDiagnosticsClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    onImportTutorialClick: () -> Unit = {}
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AppSpace.page),
        verticalArrangement = Arrangement.spacedBy(AppSpace.gap)
    ) {
        Text("更多", style = AppType.title, color = Ink)
        SectionLabel("课程")
        ListGroup {
            ListRow("课程提醒", "通知权限和测试通知", onReminderClick, icon = R.drawable.symbol_notifications)
            HorizontalDivider(color = Hairline)
            ListRow("备份与恢复", "导出或恢复本地课程表", onBackupClick, icon = R.drawable.symbol_content_copy)
            HorizontalDivider(color = Hairline)
            ListRow("学校课表同步", "从企业微信教务系统导入课程", onSchoolSyncClick, icon = R.drawable.symbol_history)
            HorizontalDivider(color = Hairline)
            ListRow("课表导入教程", "首次导入的操作步骤与常见问题", onImportTutorialClick, icon = R.drawable.symbol_menu_book)
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
                ListRow("学校同步诊断", "连接与采集问题排查", onSchoolSyncDiagnosticsClick)
            }
        }
        Spacer(Modifier.height(AppSpace.group))
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
            Text(
                "软件使用中遇到问题，欢迎随时发邮件告诉我。感谢您的宝贵意见。",
                style = AppType.caption,
                color = MutedInk,
                textAlign = TextAlign.Center
            )
            SelectionContainer {
                Text("suyu20651@gmail.com", style = AppType.body, color = MutedInk)
            }
            Text("为爱发电", style = AppType.caption, color = MutedInk)
        }
    }
}

@Preview @Composable private fun MoreLight() { 课程表Theme { MoreScreen({}, {}, {}) } }
