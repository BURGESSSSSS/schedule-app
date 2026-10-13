# Stage 5B：今日页详情转场接入报告

## 1. 大白话总结

今日页现在使用 Stage 5A 冻结的单一 Morph controller：普通白卡、当前/下一节黑卡和后续预览卡都能从原位展开到详情，再从遮罩、返回、编辑或删除收起。展开中的课程仍保留在根状态里，收起动画完全结束后才清理并执行编辑、删除或关闭业务，因此旧的周视图详情路径不会和今日页同时打开。

真实模拟器中验证了普通白卡和黑色下一节卡。卡片标题、课程色、时间、节次、地点使用同一份课程数据；来源卡片只隐藏内容而保留槽位，进度条继续读取同一个 entry 的绘制期值。稳定来源 key 包含今日角色、课程 ID 和上课日期，避免同课实例冲突。为满足 360dp + 1.3 字体，今日页时间轴宽度按系统字体比例增加，正常字体下保持原宽度。

## 2. 改动文件清单

- `app/src/main/java/com/burgess/schedule/MainActivity.kt`
  - 根部创建并挂载 `MorphHost`，保留旧 `CourseTransitionHost` 供周视图使用。
  - 今日页注入同一个 `MorphController`，并延后退出后的编辑、删除、清理回调。
  - `TimelineCourseCard` 接入 `MorphSource`、来源样式、详情映射、稳定 key 和共享进度 entry。
  - 今日页时间轴宽度随系统字体比例扩展，修复 360dp + 1.3 下时间字符串逐字换行。
- `docs/reviews/stage5b-report.md`

本阶段没有改 Room、DAO、Repository、周视图布局、`ui/morph` 核心动画或手势实现。QA 截图、录屏和数据库副本均留在 `app/build/qa/`，没有加入提交范围。

## 3. 自查结果

### §7.1

- 单一黑色焦点、课程色和已结束课程文字：满足。真实 Today 快照中普通白卡、黑色下一节卡和详情文字可读。
- 今日每节课只出现一次：满足。真实恢复后的 Today 显示 2 节课各一次；普通卡和黑卡各完成 10 次展开/收起循环。
- Room/DAO 业务逻辑未改：满足。删除仍是原来的 DAO 删除 + 5 秒撤销；此次撤销复测恢复了原 ID 和字段。
- 课程色对比度和状态纯函数：沿用阶段 1–4 已通过结果，本阶段未改动。
- 360dp + 1.3：满足 Today 本阶段快照。修正后时间轴四个时间均单行；标题和地点在窄屏自然换行，没有截断。周视图 Morph 尚未接入，周格不在本阶段范围内。
- 深色主题：满足 Today 范围。真实模拟器切换夜间模式后检查了基础页、普通卡详情和黑卡详情，文字、遮罩、按钮和课程色仍可读；随后已恢复浅色。三键导航、周视图 Morph 属于后续阶段边界。

### §5.3

1. **单一驱动：满足。** 今日来源和详情使用同一个 `MorphController.progress`；位置、大小、圆角、填充、描边、遮罩和内容透明度仍由该时钟推导。进度条的 `entry.value` 通过 lambda 在绘制期读取。
2. **生命周期统一：满足。** `finishTodayMorph` 只由 `onExitFinished` 调用，编辑、删除、关闭均在收起完成后处理。
3. **两层内容与固定槽位：满足。** `MorphSource` 保留原卡片布局槽位，来源内容与详情内容由 overlay 固定布局承载；标题使用已记录的 baseline/几何快照。
4. **来源元素保留布局：满足。** 选中来源透明隐藏但不移除 slot，收起结束后恢复。
5. **弹窗最上层：满足。** `MorphHost` 位于 `Scaffold` 和导航内容之外的根层。
6. **不卡：已测范围满足。** 普通卡 10 次、黑卡 10 次、中途反向和编辑/删除路径均完成；未宣称覆盖周视图、手势下拉或预测性返回。

## 4. 与意见书不一致之处及原因

- 本阶段只接入今日页，周视图仍使用旧 `CourseTransitionHost`，这是 Stage 5B 的明确范围，留给后续接入。
- 2.0×、关闭动画和真实周视图转场没有在本阶段重新测量；Stage 5A 沙盒已有对应证据，但不能替代这里的真实 Today 结论。
- 首次删除撤销尝试没有恢复课程：删除后用脚本点击中文“撤销”发生终端编码乱码，随后固定坐标点击已超过 5 秒窗口。该次结果不能归因于 App 逻辑。之后按 baseline 只恢复缺失的一行，再在撤销条显示约 0.45 秒内点击撤销，数据库恢复为 7 行，ID `1789976384766` 的 name/room UTF-8 hex、dayIndex、period、endPeriod、weekNumber 和 colorValue 与 baseline 完全相同。
- 360dp + 1.3 的初始快照暴露今日时间轴过窄导致时间逐字换行；已用字体比例扩展今日时间轴修复。该修复不改变正常字体布局、业务数据或 Morph 核心。

## 5. 请使用者在真机上检查的清单

1. 今日页点击普通白卡“信息安全产品配置与应用”，确认卡片平滑展开，详情显示课程名、周一第 3–4 节、10:00–11:40 和“未填写”；点击遮罩关闭，确认原位恢复。
2. 点击黑色下一节“路由与交换技术”，确认黑卡展开后标题、周一第 5–6 节、14:00–15:50 和地点可读；展开中立即点遮罩，确认平滑反向且没有空白或重影。
3. 在详情中点“编辑”，确认收起结束后进入同一课程编辑页；取消编辑，确认课程 ID 和字段不变。
4. 再打开同一课程点“删除”，确认收起结束后显示“已删除 · 撤销”；在 5 秒内点撤销，确认原课程恢复且没有重复课程。
5. 连续打开/关闭普通卡和黑卡各 10 次，观察来源槽位、标题、遮罩和底部导航没有残留。
6. 将系统字体设为 1.3 倍并使用约 360dp 宽屏，确认时间轴保持单行，详情标题和地点可读。周视图转场、2.0×和关闭动画留待相应阶段验证。

### 本阶段证据

- 真实基础页：`app/build/qa/stage5b-today-base-real.png`
- 普通卡展开/关闭及 10 次循环：`app/build/qa/stage5b-real-ordinary-open-latest.png`、`app/build/qa/stage5b-real-ordinary-close.png`、`app/build/qa/stage5b-real-ordinary-10cycles.png`
- 黑卡展开及 10 次循环：`app/build/qa/stage5b-real-black-open-latest.png`、`app/build/qa/stage5b-real-black-10cycles.png`
- 中途反向：`app/build/qa/stage5b-real-mid-reverse.png`
- 编辑后取消：`app/build/qa/stage5b-real-edit-open.png`、`app/build/qa/stage5b-real-edit-cancel-restored.png`
- 删除撤销恢复：`app/build/qa/stage5b-real-delete-snackbar-retry.png`、`app/build/qa/stage5b-real-delete-undo-exact.png`、`app/build/qa/stage5b-schedule-after-undo-exact.db`
- 360dp + 1.3 修复后：`app/build/qa/stage5b-real-360dp-1.3-base-fixed.png`、`app/build/qa/stage5b-real-360dp-1.3-black-open-fixed.png`
- 深色 Today：`app/build/qa/stage5b-real-dark-base.png`、`app/build/qa/stage5b-real-dark-ordinary-open.png`、`app/build/qa/stage5b-real-dark-black-open.png`
- 模拟器短录屏：`app/build/qa/stage5b-real-today.mp4`

XML 测试结果：`verifyDebugUnitTests` 共 31 项，失败 0，错误 0，跳过 0。构建命令为 `:app:assembleDebug :app:verifyDebugUnitTests --offline --no-configuration-cache --console=plain`，结果 `BUILD SUCCESSFUL`。最终 `app/build/outputs/apk/debug/app-debug.apk` SHA-256 为 `61AE0D86B2A2A2B2C00B94402491F9E2B0E6DF47DFE330F6CDD13711A84B18AA`。
