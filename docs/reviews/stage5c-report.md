# Stage 5C 阶段报告

## 1. 大白话总结

周视图普通课程格现在与今日页共用同一个 `MorphController`、`MorphSource`、`MorphSession` 和 `MorphHost`。点击周格时，原格的浅色底、课程色文字、12dp 圆角、进行中描边和标题一起连续变成详情卡；关闭、编辑、删除都先完成收起动画，再处理业务状态。来源格保留原位置和布局，动画期间不会跳动。

课程格内部仍沿用原来的 `FittedWeekText` 拟合算法。中文标题最多两行，Latin 标题保持单行、`softWrap=false` 和省略语义。编辑模式继续直接打开编辑器，空格继续执行原来的新增课程行为。

## 2. 改动文件清单

- `app/src/main/java/com/burgess/schedule/MainActivity.kt`：接通周视图的唯一 Morph 链；保留编辑、新增、周末滚动、复制和导入行为；接入来源尺寸、颜色、圆角、描边及标题快照。
- `app/src/main/java/com/burgess/schedule/ui/morph/MorphTransition.kt`：给 `MorphSourceContentScope.Title` 和 `MorphTitleSnapshot` 增加带默认值的 `softWrap`、`overflow` 透传，保持原调用兼容；普通文字和详情文字均使用快照语义。
- `app/src/main/java/com/burgess/schedule/ui/components/CourseTransition.kt`：删除旧的转场实现。
- `docs/reviews/stage5c-report.md`：本报告。

全局引用已清除 `CourseTransitionHost`、旧 `CourseSource`、`sharedBounds`、`SharedTransition`、`morphShowingToday` 和旧 retained 状态。最终源码只有一个详情转场系统。没有修改 `MorphMath`、曲线、时长、交接参数或 baseline；`Morph.Title` 的兼容扩展只是默认参数和快照透传。

## 3. 自查结果（§7.1、§5.3）

§7.1：满足。每页仍只有一个黑色大块；课程色只用于课程；没有新增阴影、渐变、模糊、彩色图标或 emoji；周视图保留编辑、导入、复制、固定列宽和现有滚动；Room、DAO、Repository、OCR、通知和导航业务逻辑未改。构建和单元测试均通过。

§5.3 六项：

1. **单一驱动：满足。** 周格和今日页使用同一 `MorphController` 的进度推导几何、颜色、圆角、遮罩和两层内容，没有 `sharedBounds` 或每格独立动画。
2. **生命周期统一：满足。** `finishCourseMorph` 只在收起动画结束后处理关闭、编辑或删除；删除撤销复测确认状态时序正确。
3. **两层内容与固定布局：满足。** 来源层和详情层继续由 `MorphHost` 固定布局、外层裁剪；标题使用快照传递拟合后的 `TextStyle`、`softWrap` 和 `overflow`。
4. **来源元素保留布局：满足。** 来源格只隐藏内容显示，格子的大小和位置不变；打开、收起和中途打断均恢复到原位。
5. **弹窗最上层：满足。** `MorphHost` 位于页面 `Scaffold` 外层，详情覆盖周格与底部导航。
6. **不卡：满足。** 没有给每个格子挂动画机制，没有新增逐帧重测量；Pixel_9 真实周格 1.0×开关和 10 次循环未见卡顿、残留遮罩或状态错误。

冻结 16 项验收记录：

1. 左侧真实格：周一“信息安全产品配置与应用”打开详情并恢复，满足。
2. 中间真实格：周三“大学体育(三)”打开详情并恢复，满足。
3. 最右真实格：周五“习近平新时代中国特色社会主义思想概论1”打开详情并恢复，满足。
4. 中文标题：来源与详情均可读，满足。
5. 两行中文：360dp、font scale 1.3、深色组合下中文保持两行且无新破版，满足。
6. Node.js Latin：`WeekGridSandbox` 使用同一生产 `CourseSlot`、`MorphHost` 和控制器打开并恢复 Node.js 假数据，满足；这是沙盒覆盖证据，真实库没有该课程，未硬编码真实课程。
7. 彩色来源：课程底色和文字颜色从来源连续到详情，满足。
8. 原位恢复：来源格大小、位置和文字回到原位，无明显跳动，满足。
9. 十次开关：今日页真实课程完成 10 次开关，周视图真实课程完成 10 次开关，最终均无残留遮罩，满足。
10. 展开中反向：周视图真实周一格打开约 140ms 后点击外部遮罩；`stage5c-week-reverse-mid.png` 显示来源浅色卡片回缩、遮罩变浅，最终截图恢复完整周格，满足。
11. 编辑：周视图 Morph 详情编辑周五课程时，编辑器显示原课程名称、星期五、第 3-4 节和中裕楼 A204；编辑模式直接课程编辑标题也可见，满足。
12. 删除撤销：临时唯一课程 `STAGE5C_TRACE_1643` 从普通 Morph 详情删除；500ms 画面显示“已删除/撤销”，短延迟点击撤销返回 `ActionPerformed`，同一临时课程恢复，满足。随后已等待提示超时删除临时课程并确认原 7 行真实数据仍在。
13. 浅色：真实周视图、详情和 Today 回归通过，满足。
14. 深色：真实周视图及详情通过，满足。
15. 360dp + font1.3：设备宽度覆盖为 945px、density 420（360dp），同时设置 font scale 1.3，周格与详情无新明显破版，满足。
16. Today 回归：本轮真实数据覆盖 Tuesday 当前唯一焦点卡，完成打开、收起和 10 次循环后恢复；普通卡路径沿用 5B，未在本轮数据中单独出现，由 root 对代码路径审查，不能把它写成 5C 的独立实测，满足本轮实际覆盖范围。

测试设备初始物理参数为 `wm size 1080x2424`、density 420、font scale 1.0；第 15 项使用 `wm size 945x2424`。验收结束已执行 `wm size reset`、恢复 font scale 1.0 和浅色模式。系统 transition/window animation scale 为 1.0，未改变系统动画设置。

## 4. 与意见书不一致之处及原因

无功能偏差。`Morph.Title` 只增加默认的 `softWrap=true`、`overflow=Clip` 参数及快照透传，原因是周格必须保持原有中文换行和 Latin 单行省略语义；这不是重新设计转场核心。Node.js 项目使用现有 `WeekGridSandbox` 的假数据作为覆盖证据，因为真实数据没有 Node.js 课程。

早期一次延迟截图没有捕获 Snackbar，不能作为验收证据；随后用日志和 500ms/撤销复测确认同一 Morph 删除路径正常，未改核心路径。本阶段没有新增 backlog；既有技术债继续延期。除该已闭合的时序复测外没有遗留 Stage5C 阻塞项。Stage 5D/6/7 不在本阶段范围。

## 5. 请使用者在真机上检查的清单

1. 安装当前 debug 包，打开“周视图”，依次点周一、周三、周五的真实课程格；应从各自原位置连续展开到详情，返回后恢复原格。
2. 点击课程格后在展开中立即点外部遮罩；应平滑反向收起，不出现空白卡、双标题或残留遮罩。
3. 在编辑模式点击已有课程；应进入该课程的编辑器，名称、星期和节次保持对应课程。
4. 新建一个临时课程，从普通 Morph 详情删除；应看到“已删除/撤销”，在提示期点击撤销后课程回到原格。
5. 切换深色模式并把字体调到 1.3，在约 360dp 宽度查看周格；中文应保持两行，Latin 标题保持单行省略，详情内容应完整可读。
6. 打开 Today 页当前可用的焦点卡，收起并重复开关；应无空卡、重影、卡顿或遮罩残留。普通卡路径沿用 5B，需在有普通卡数据时另行检查。

构建命令：`./gradlew.bat :app:assembleDebug :app:verifyDebugUnitTests --offline --no-configuration-cache --console=plain`，结果 `BUILD SUCCESSFUL`，47 actionable tasks（5 executed，42 up-to-date）；最终 `verifyDebugUnitTests` 任务为 `UP-TO-DATE`。31 项 JVM 测试 XML 为 0 失败、0 错误、0 跳过。关键证据位于 `app/build/qa/`：`stage5c-week-10cycles-final.png`、`stage5c-week-reverse-open-140ms.png`、`stage5c-week-reverse-mid.png`、`stage5c-week-reverse-final.png`、`stage5c-delete-debug-undo-visible.png`、`stage5c-delete-debug-after-undo.png`、`stage5c-week-360-font130-dark-open.png`、`stage5c-sandbox-node-restored-final.png` 和 `stage5c-today-10cycles-final.png`。
