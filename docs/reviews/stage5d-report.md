# Stage 5D：详情返回行为

## 1. 大白话总结

现在从 Today 或 Week 打开课程详情后，系统返回手势会直接带动这张详情卡片收回；松手完成就按现有关闭流程清理课程详情，手势取消就从当前位置平滑回到完整详情。普通 Back 仍能关闭详情；没有打开详情时，返回交给原页面和 Activity 处理。没有新增动画时钟，也没有改变详情的视觉参数。

## 2. 改动文件

- `app/src/main/java/com/burgess/schedule/ui/morph/MorphTransition.kt`：以 `PredictiveBackHandler` 取代详情层的 `BackHandler`，收集系统返回进度；提交仍调用原有关闭路径，取消时请求控制器恢复。
- `app/src/main/java/com/burgess/schedule/ui/morph/MorphController.kt`：增加极小的 `BackGesture` 阶段和返回手势方法。手势从当前进度映射到 0；取消后复用同一个 `Animatable` 回到 1，时长沿用 400ms 打开基准值。
- `docs/reviews/stage5d-report.md`：本阶段记录。

工程已依赖 `androidx.activity:activity-compose:1.8.0`，当前版本已支持官方 Compose `PredictiveBackHandler`，并以 `Flow<BackEventCompat>` 提供手势进度。本实现采用官方 Flow 收集路线；取消异常只负责请求控制器自身作用域启动恢复动画，随后继续向上传播取消，避免在已取消的手势协程中挂起恢复。参考：[Android 官方手势进度文档](https://developer.android.com/develop/ui/compose/system/predictive-back-progress)、[Android 官方 Compose Samples 的 JetLagged 返回手势示例](https://github.com/android/compose-samples/blob/main/JetLagged/app/src/main/java/com/example/jetlagged/JetLaggedDrawer.kt)。项目当前 `targetSdk=37` 且 Manifest 已启用 `enableOnBackInvokedCallback`；本阶段没有改依赖或 Manifest。

## 3. 自查结果

| 冻结验收 | 结果与证据 |
|---|---|
| 1. Today 普通课程卡按 Back | 通过。点开普通课程详情再发送 `adb shell input keyevent 4`，详情关闭、Today 内容恢复。见 `app/build/qa/stage5d/today-ordinary-detail.png`、`today-ordinary-keyback.png`。 |
| 2. Today 黑色焦点卡按 Back | 通过。黑色焦点卡详情正常关闭，来源卡片恢复。见 `today-black-detail.png`、`today-black-keyback.png`。 |
| 3. Week 课程卡按 Back | 通过。Week 普通课程详情关闭后仍留在 Week。见 `week-card-detail.png`。 |
| 4. Predictive Back 提交 | 通过。Pixel_9 / API 37 上从左侧边缘执行 ADB swipe，详情退出并回到 Week。见 `week-before-edge-commit.png`、`week-edge-commit-after.png`。 |
| 5. Predictive Back 取消 | 通过。边缘手势移动 420px 时截到详情缩小、遮罩变浅；发送 `input motionevent CANCEL` 后详情恢复。见 `week-back-progress-420.png`、`week-back-progress-cancelled-420.png`。恢复后编辑按钮仍打开了正确的 `STAGE5D_TEMP` 课程编辑页（`temp-editor-ui.xml` 中课程名一致），证明详情交互阶段恢复。 |
| 6. 打开中途按 Back、无空卡/重影/残留遮罩 | 通过。黑色卡开始打开约 150ms 后发送 Back，Activity 仍留在前台，页面回到完整来源卡；详情关闭后的 Today/Week 截图没有空卡、重影或残留遮罩。Predictive 420px 中间帧和取消恢复帧见上一项。 |
| 7. Edit / Delete / Undo | 通过。唯一临时课 `STAGE5D_TEMP` 的 Edit 打开了名称正确的编辑页；Delete 显示“已删除 / 撤销”；在 5 秒窗口内立即点击撤销后，数据库恢复临时课程 ID `1790126804988`。最终再次通过 UI Delete 清理。`temp-editor.png`、`temp-deleted-snackbar.png`、`temp-undo-retry.png`、`temp-final-deleted.png` 留有截图。第一次撤销点击太晚，Snackbar 已过期且临时课仍处于删除状态；随后快速重试成功。 |
| 8. 连续 10 次打开/关闭 | 通过。在 Today 对同一普通课程连续执行 10 次点击与 Back；10 张关闭态截图均回到同一稳定页面，SHA-256 相同。见 `cycle-01-closed.png` 至 `cycle-10-closed.png`。 |
| 9. `assembleDebug` | 通过。执行 `:app:assembleDebug :app:verifyDebugUnitTests --offline --no-configuration-cache --console=plain`，`BUILD SUCCESSFUL`。 |
| 10. 31 项 JVM 测试 | 通过。7 份测试 XML 合计 31 项，failures=0、errors=0，位于 `app/build/test-results/verifyDebugUnitTests/`。 |
| 11. `git diff --check` | 通过。 |

模拟器初始数据库为 7 条课程。测试通过 `install -r` 保留数据，只经 UI 临时添加并删除唯一命名课程，没有清库或直接写模拟器数据库。最终只读数据库核对为原 7 个 ID：`1789976384765`–`1789976384771`，没有残留 `STAGE5D_TEMP`。无详情时按系统 Back，`dumpsys activity` 显示从 `MainActivity` 返回 `NexusLauncherActivity`，说明 Morph Handler 没有截获根页面 Back。

所有截图和只读数据库快照在忽略目录 `app/build/qa/stage5d/`；本阶段没有生成录屏，也没有新增调试日志。模拟器取消由显式 ADB `CANCEL` 事件触发，能验证系统返回回调的取消恢复；尝试用 ADB 单事件序列“移回屏幕边缘再抬手”时系统仍提交了手势，因此这不能代表真手指自然反向取消。阶段结果基于 Pixel_9 模拟器，不声称已在真机验证。

## 4. 与设计简报不一致之处及原因

§5.5 原文还建议实现详情下拉关闭。本阶段明确冻结范围排除了下拉、nested scroll、fling 和 spring，所以没有实现它们；这遵守本轮范围约束。除此之外，保留 §5.3 的同一进度和 session 收尾性质，也没有改 `MorphMath`、标题交接、颜色、baseline 或现有动画时长。

## 5. 真机验证限制与建议后续补充

本阶段已在 Pixel_9 模拟器完成 11 项验收；尚未在真机上复测。自然手指取消（手指移回屏幕后抬起）尚未实测，目前取消恢复由模拟器系统返回回调和 ADB 显式 `CANCEL` 事件验证。后续真机复核可观察：普通 Back 和边缘返回都应收起详情；取消返回应恢复完整详情且 Edit/Delete 仍可操作；打开动画中立即返回应从当前形态收回。
