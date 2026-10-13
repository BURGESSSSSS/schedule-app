# Stage 5.5 最终 QA 与结构报告

## 范围与结论

本报告记录 Stage 5.5 A-F 结构整理和 Pixel_9 模拟器冻结回归。Stage 5C 基线提交为 `8b08e6e`（`ui: stage 5C migrate week details to shared Morph`）。`video_review/` 未纳入 Stage 5.5 变更。

Stage 5.5 的目标是整理页面、详情和动画的边界，保持现有用户行为、Morph 算法和视觉参数不变。CURRENT_SCOPE.md 是本阶段新增的根目录范围事实文档；它记录了当前产品定位、今日/周视图/OCR/课程编辑/提醒等核心功能，未来系统日历同步计划，正式删除且禁止重加的范围，固定浅色模式，开发者邮箱直接显示，旧功能代码暂留，Prior-Art Check 的规则、六级来源优先级和四个必答问题，以及本阶段不做新功能和用户可见改变。

## 十四、完成后汇报

1. **大白话说明这次整理了什么**：把 Today、Week、More、详情外观层、组合根和 Morph 内部实现拆到各自文件，页面通过详情外观层使用详情能力。
2. **Stage 5C commit hash**：`8b08e6e`（`ui: stage 5C migrate week details to shared Morph`）。
3. **Stage 5.5 修改文件**：`MainActivity.kt`、`ui/app/ScheduleApp.kt`、`ui/detail/CourseDetailHost.kt`、`ui/model/ScheduleUiModels.kt`、`ui/today/TodayScreen.kt`、`ui/week/WeekScreen.kt`、`ui/week/ScheduleGrid.kt`、`ui/more/MoreScreen.kt`、`ui/morph/MorphModels.kt`、`MorphController.kt`、`MorphSource.kt`、`MorphTransition.kt`、必要的 Components/DesignPreview 调整、`CURRENT_SCOPE.md` 和本报告。
4. **MainActivity 整理前/后的职责变化**：从约 2743 行、同时承载页面和业务组合，缩减为 56 行，只负责 Activity 生命周期、固定浅色系统栏、Intent 解析和 `setContent`；组合和页面状态由 `ScheduleApp` 承载。
5. **Today 的新边界**：`ui/today/TodayScreen.kt` 承载 Today 时间线、焦点卡和时间线课程卡，主要依赖 `ui.model`、详情外观层、主题和组件，同时保留对 root 日期函数、取件码兼容入口和 `data.PickupCodeEntity` 的实际依赖。
6. **Week 的新边界**：`ui/week/WeekScreen.kt` 承载周页头部和页面回调，`ScheduleGrid.kt` 承载网格、课程格和拟合文本；只依赖 model、detail、theme/components 及必要的 root 日期函数。
7. **Detail / Motion 的新边界**：`CourseDetailHost.kt` 暴露 `CourseDetailController`、`CourseDetailAction`、详情数据和来源外观；Morph controller、source、session、snapshot、几何和 overlay 留在 `ui/morph` 内部，DAO 回调留在根层。
8. **是否修改任何用户可见行为**：没有。Today、Week、详情、编辑、删除撤销、OCR、提醒、复制和编辑模式按冻结回归保持可用。
9. **是否修改 Morph 核心算法**：没有。`MorphMath.kt` 未改，曲线、时长、baseline、title handoff、颜色插值、source snapshot、绘制顺序和视觉参数未改。
10. **JVM 测试结果**：31/31 通过，failures=0、errors=0。
11. **assembleDebug 结果**：`./gradlew.bat :app:assembleDebug :app:verifyDebugUnitTests --offline --no-configuration-cache --console=plain` 成功。
12. **模拟器回归结果**：Pixel_9 / emulator-5554 使用 `install -r` 保留 DB；Today 焦点卡、普通 Week 详情、黑色焦点卡详情、Week 详情、正确编辑、删除撤销、OCR 入口、提醒入口、复制本周到下周和 Week 编辑模式均已验证。复制前第 5 周为空，复制后新增 7 条与第 4 周字段相同的课程，随后使用目标周“清空本周”回滚，最终 DB 精确恢复原 7 个 ID。before/after 截图已保存；证据来自模拟器，不声称真机。
13. **仍然存在的结构技术债**：`ScheduleApp.kt` 仍约 1756 行，继续承载组合根、DAO/Room 接线、编辑器、OCR、提醒、历史取件码兼容入口和导航状态；MorphTransition 仍约 410 行，负责 host、overlay、title layer 和 detail visual/action content。
14. **不建议现在继续拆的部分**：不建议继续拆 `ScheduleApp` 的历史业务或继续细拆 Morph。继续拆分会扩大可见性、循环依赖和状态接线风险；后续应由主控单独评估。

## 十一、冻结验收标准逐项结果

1. **项目能正常 assembleDebug**：通过。`assembleDebug` 成功。
2. **当前 JVM 测试全部继续通过**：通过。31 项 XML 测试全部通过，0 failures、0 errors。
3. **今日页视觉与行为无变化**：通过。Today 页保留当前焦点卡、时间和底部导航；after Today 无明显破版。before Today 实际为 9 月 22 日详情画面，和 9 月 23 日普通 Today 画面状态不同，未作像素级结论。
4. **周视图视觉与行为无变化**：通过。五列、时间轴、课程卡、周次切换、复制和编辑入口正常。
5. **普通课程详情正常打开关闭**：通过。Week 普通课程详情打开后显示标题/节次/时间/地点，点击外部遮罩关闭。
6. **今日黑色焦点课程详情正常**：通过。`大学体育(三)` 黑色焦点卡打开详情并点击外部遮罩关闭，详情动作层和 Today 卡均恢复。
7. **周视图课程详情正常**：通过。周一普通课程详情打开、关闭，导航仍停留在 Week。
8. **编辑仍打开正确课程**：通过。`信息安全产品配置与应用` 进入编辑器，名称、周一、第 3-4 节保持对应课程；取消后数据未变。
9. **删除仍删除正确课程**：通过。仅使用唯一 `STAGE55_TEMP`，删除后其 ID 从 DB 消失，原 7 个课程 ID 未变。
10. **撤销仍恢复正确课程**：通过。最终无日志 APK 删除后等待约 1.1 秒点撤销，恢复 ID `1790123978055`；随后再次删除清理，DB 回到原 7 条。时间证据在 `app/build/qa/stage55-final-no-log-undo-timing.txt`。
11. **OCR 原入口仍可用**：通过。Week“导入”进入课程截图导入，再进入系统 Photo Picker；未选择文件。
12. **提醒原入口仍可用**：通过。More“课程提醒”打开原提醒设置对话框。
13. **复制本周到下周仍可用**：通过。复制前 DB 为第 4 周 7 条、第 5 周 0 条；点击“复制到下周”后 DB 为 14 条，第 5 周新增 7 条，字段与第 4 周对应课程一致、ID 新生成；进入第 5 周编辑模式后点击“清空本周”，最终 DB 精确恢复原 7 个 ID/原字段。证据为 `stage55-copy-before.db`、`stage55-copy-after.db`、`stage55-copy-after-clear.db`。
14. **周视图编辑模式仍可用**：通过。进入后显示“完成”“清空本周”“添加课程”，清空回滚路径也已使用，完成后可回到普通视图。
15. **MainActivity 职责明显减少**：通过。约 2743 行降至 56 行，Activity 只保留生命周期、系统栏、Intent 和组合根调用。
16. **Today / Week 不直接依赖 Morph 内部实现细节**：通过。源码检索未发现 Today/Week/MainActivity 对 `ui.morph`/`Morph*` 的依赖；页面通过 detail facade 使用详情能力。
17. **Morph 不直接执行 DAO 业务操作**：通过。`ui/morph` 无 DAO/Room 引用，删除/撤销由 `ScheduleApp` 根回调执行。
18. **没有新旧两套页面或两套状态流并存**：通过。当前页面仅保留 Today/Week/More 新文件和唯一 detail facade；旧页面定义已从 MainActivity 删除。
19. **无明显新增重复代码**：通过。共享课程模型、日期/格式工具和详情外观集中复用；Morph 拆分仅移动原实现，未复制第二套动画逻辑。
20. **git diff --check 通过**：通过。暂存 diff 检查无 whitespace error；仅 Git 报告 LF/CRLF 转换提示。

以上 20 项是附件 §十一 的完整验收项；其余截图前后状态差异、基线 APK 对照和调试链路属于补充证据，不另列为验收标准。

## 结构债与边界记录

`ScheduleApp.kt` 仍约 1756 行，继续承载组合根和历史功能接线；More/Today/Week 已分出，但 root 兼容 token、编辑器、导入、提醒和历史取件码入口仍集中。`MorphTransition.kt` 约 410 行，负责 host、overlay、title layer 和 detail visual/action content。继续拆分会扩大可见性和循环风险，本阶段不建议继续拆分。旧功能代码按 CURRENT_SCOPE 保留，正式删除留给后续对应页面阶段。

删除/撤销的当前与 Stage5C 原始实现源码语义一致：两者均为 `courseDao.delete` 后等待 `SnackbarResult.ActionPerformed`，再 `courseDao.upsert`。隔离构建的 `8b08e6e` 基线 APK 和最终当前 APK 均在 Snackbar 已呈现后等待约 1 秒点击时成功恢复。临时日志确认详情 facade、MorphHost、根回调和 DAO upsert 链路完整；调试日志已移除。本轮保留了 `stage55-undo-final-timing.txt`、`stage55-undo-timing*.xml`、`stage55-baseline-undo-timing.txt` 和只读数据库导出。

## 基线判定证据

在 `C:\Users\Burgess\Desktop\stage55-baseline-8b08e6e` 隔离 worktree 从 `8b08e6e` 构建 APK，首次构建因缺少 SDK 路径失败，补充仅限隔离目录的 `local.properties`（`D:/AndroidSdk`）后 `assembleDebug` 成功。安装前备份模拟器 DB，使用同签名 `install -r` 安装基线；创建 `STAGE55_TEMP`、详情删除、等待约 1 秒点击撤销后，基线 DB 为 8 条且包含临时 ID `1790123043115`。再用 UI 删除临时课程，基线 DB 恢复原 7 条；重新安装当前 Stage 5.5 APK 后用相同链路和临时 ID `1790123978055` 验证恢复，再次清理后 DB 仍为原 7 条。没有 uninstall、clear、wipe 或直接写数据库。

`video_review/` 保持用户未跟踪状态，未纳入 Stage 5.5 变更。
