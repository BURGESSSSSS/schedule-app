# 阶段 5A · 详情转场假数据原型复核（冻结报告）

日期：2026-09-21  
范围：仅阶段 5A 设计预览沙盒。实现没有接入 Today/Week 真实页面、Room、DAO、Repository 或 ViewModel，也没有在本次报告整理中重新构建或重跑测试。后续阶段再处理真实页面、手势和旧转场实现的整合。

## 1. 大白话总结

点击沙盒里的白卡、黑卡、彩色窄格后，当前元素会从原来的位置、尺寸、圆角和颜色连续变成详情卡；关闭、系统返回、编辑和删除都会先播放收起，再在动画结束的同一生命周期点清理来源和详情状态。转场由一条 `Animatable<Float>` 线性时钟驱动，来源内容和详情内容保持固定测量尺寸，由外层形变 surface 统一裁剪。

标题交接按首行 baseline 做等比变形：来源标题首行承接到详情标题，来源正文/尾行更早退场；静态的明/暗前景副本负责在黑、白和彩色背景之间保持文字可读。详情正文和标题使用同一个滚动状态。关闭完成后才触发一次 `Close`、`Edit` 或 `Delete` 回调。

本阶段已经冻结 5A 沙盒验收。最新 MP4 和联系表仅作为 0.5× 慢放打开/收起与开关证据；各项专项检查使用下方分别列出的 QA 截图，旧版录屏不作为证据：

- `app/build/qa/stage5a-final-latest.mp4`：2026-09-21 15:32:57，SHA-256 `2DE96D662C9CA2BBB7B8709326B1A78B60DCC8502B4A0B7ABC5357AE319D6E05`。
- `app/build/qa/stage5a-final-latest-contact.png`：2026-09-21 15:33:51，SHA-256 `3F22D36698CBB45CF79BB8B51CC00E754BDBCA7E9C98D02CFCCFE6889BCD89A7`。

## 2. 改动文件清单

- `app/src/main/java/com/burgess/schedule/ui/morph/MorphMath.kt`：纯数学曲线、同一进度值的内容窗口、标题几何交接、对比度回退和生命周期 gate。
- `app/src/main/java/com/burgess/schedule/ui/morph/MorphTransition.kt`：根层 overlay、来源坐标记录、固定尺寸双层内容、标题首行交接、统一滚动、返回/遮罩/编辑/删除收起路径和完成回调。
- `app/src/main/java/com/burgess/schedule/ui/morph/MorphSandbox.kt`：白卡、黑卡、彩色窄格和调试控制的假数据沙盒。
- `app/src/main/java/com/burgess/schedule/ui/components/DesignPreview.kt`：仅 Debug 可见的“详情转场假数据”入口。
- `app/src/test/java/com/burgess/schedule/ui/morph/MorphTransitionTest.kt`：纯函数端点/单调性/矩形插值/标题互补透明度、对比度回退和退出 gate 测试。
- `docs/reviews/stage5-sandbox-review.md`：本阶段报告。
- `docs/reviews/redesign-backlog.md`：用户明确延期的非阻塞事项。

## 3. 自查结果（§7.1 与 §5.3）

### §7.1 必须坚持

1. 每页最多一个黑色大块、课程色只属于课程、界面框架保持中性：**满足（5A 沙盒样本）**。位置：`MorphSandbox.kt` 的假数据和 `MorphTransition.kt` 的 surface 绘制；本阶段不代表真实页面全量验收。
2. 不使用阴影、渐变、模糊、彩色图标或 emoji 图标：**满足（5A 代码检查）**。位置：`MorphTransition.kt` overlay 绘制路径。
3. 今日页每节课只出现一次、已结束课程文字对比度：**未测**。5A 没有 Today 真实页面。
4. 时间和数字使用等宽数字，字重保持 400/500/600：**满足（沙盒沿用现有主题）**；真实页面全量检查留给整合阶段。
5. 周视图编辑/导入/复制到下周、固定列宽、周末横向滚动：**未测**。5A 只使用窄格假数据记录来源坐标；真实周格继续沿用已验收的 `FittedWeekText`，没有为沙盒窄格改动核心布局。
6. 详情转场满足 §5.3 六条性质：**满足（沙盒范围）**，逐条见下。
7. 不修改 Room 实体、DAO、Repository、ViewModel 业务逻辑：**满足**。本阶段改动只在 morph、Debug 预览入口和测试。
8. 阶段可编译、可回退：**满足（现有构建证据）**。最新 `app/build/outputs/apk/debug/app-debug.apk` 于 2026-09-21 15:31:52 生成，大小 60,982,020 bytes，SHA-256 `D6ADF56C4DBC21E03C571CB45F43E05FCDF46951F961DE8A0B53B32421FAA909`。本次报告没有再次构建。

### §5.3 六条性质

1. **单一驱动：满足。** `MorphController.progress` 是唯一动画进度；打开和收起使用 `animateTo` 的 `LinearEasing`。位置、尺寸、圆角、底色、描边、遮罩、内容透明度、标题位移和颜色前景选择都从同一个 `p` 推导。几何只对同一 `p` 使用 `geometryProgress` 的强调减速曲线，透明度仍使用线性时钟窗口。
2. **生命周期统一：满足。** `requestDismiss` 先把进度收回 0，随后调用 `finishExit()`，再清空 session/source，并只触发一次完成回调；重复退出会被 `MorphSessionGate` 拒绝。关闭动画为 0ms 时仍经过同一路径。
3. **两层内容与固定布局：满足。** `MorphOverlayLayout` 对来源、来源暗色前景副本、详情正文和标题层分别使用 `Constraints.fixed`；动画期间只移动/裁剪固定测量层，不因进度重新测量或重新换行。
4. **来源元素保留布局：满足。** 被选来源只隐藏渲染内容，列表 slot 保持原位置；退出完成清理的同一生命周期点恢复来源。
5. **最上层：满足。** `MorphHost` 将 overlay 置于 `zIndex(20f)`，覆盖预览内容和工具栏以下区域；系统返回由同一 `requestDismiss` 路径处理。
6. **不卡/连续自然：满足（沙盒肉眼验收）；正式帧率 profiling 未测。** 代码把动画值放在 `graphicsLayer`、绘制和固定布局 lambda 中读取，普通来源只记录轻量 bounds/style，不为每个来源启动独立动画。

## 4. 与 DESIGN_BRIEF 不一致之处及原因

以下是相对 §5.4 参考实现的有意调整，均以当前沙盒的可读性和连续交接为依据：

- 原 brief 的来源 `faceAlpha` 建议窗口是 `0.15–0.45`；当前实现把来源正文窗口收紧为 `sourceBodyAlpha = 0.05–0.14`，让源尾行更早退场，给标题首行交接留出清晰空间。
- 当前实现新增 `titleGeometryProgress = geometryProgress(smoothStep(.14, .32, t))`，标题按 baseline 统一等比变形；标题透明度交接为 `titleHandoff = smoothStep(.32, .52, t)`，几何先稳定，再交接文字，避免双标题重影。
- 原 brief 建议详情逐项使用 `0.40 + 0.05*i` 到 `0.70 + 0.05*i` 的窗口；5A 假数据采用固定详情正文窗口 `detailBodyAlpha = smoothStep(.45, .70, t)`，并将标题交接单独处理。这样适配沙盒长标题、黑卡和窄格的统一清理路径。
- 原 brief 允许详情项按项上移 10dp；当前 5A 没有为各详情项配置独立时钟或独立窗口，改用固定布局、统一滚动和标题 baseline 交接，以保持单一驱动。
- 为避免背景过渡中出现黑底黑字/白底白字，来源和详情各保留静态明暗前景副本，按当前 fill 的对比度选择显示副本。这是同一内容层的静态渲染版本，不是第二个动画时钟。
- 源标题只承接已测得的首行文字；多行源标题的尾行按来源正文窗口早退。字体 1.3 倍下，沙盒 64dp 窄格可能换行或截取节次，这是假数据容器的已知边界；真实周格沿用 `FittedWeekText`，不为该沙盒现象修改核心周视图。

## 5. 请使用者在真机上检查的清单

这些检查属于 5A 之后的真实页面整合范围，不能由当前沙盒结论代替：

1. Debug 设计预览 → “详情转场假数据”：分别打开白卡、黑卡、彩色窄格，调到 0.5×，反复打开/关闭；应看到同一元素连续变形，没有空白卡、双标题重影或源卡恢复残留，黑/白/彩色文字都清楚。
2. 在打开过程中点击遮罩、按系统返回，再分别点详情“编辑”“删除”；应都先完整收起，结束后来源恢复，事件只完成一次。
3. 设为 2× 和“关闭动画”各打开、关闭一次；应能进入/退出详情，不残留遮罩，关闭动画也应执行清理和回调。
4. 在字体 1.3 倍下打开长标题详情，滚动正文后关闭；标题与正文应使用同一滚动位置，收起后来源位置和列表布局应恢复。
5. Today 真实卡片与 Week 真实周格接入后，重复以上检查：至少今日卡片 10 次循环、周视图两个不同位置、展开中途反向和下拉/预测性返回；这些真实页面与手势场景尚未在 5A 测试。

### 5A 已有证据与测试边界

- 专项 QA 截图分别记录：[最新 30 次循环](../../app/build/qa/stage5a-30-cycles-latest.png)；[2×打开](../../app/build/qa/stage5a-2x-open-latest.png)和[2×关闭](../../app/build/qa/stage5a-2x-close-latest.png)；[关闭动画打开](../../app/build/qa/stage5a-zero-open-final.png)和[关闭动画收起](../../app/build/qa/stage5a-zero-close-final.png)；[编辑回调](../../app/build/qa/stage5a-edit-callback-latest.png)与[删除回调](../../app/build/qa/stage5a-delete-callback-latest.png)；[中途遮罩反向](../../app/build/qa/stage5a-mid-mask-reverse-latest.png)；字体 1.3 倍长详情的[打开](../../app/build/qa/stage5a-font130-current-long-open.png)、[滚动](../../app/build/qa/stage5a-font130-current-long-scroll.png)和[关闭后恢复](../../app/build/qa/stage5a-font130-current-scroll-close-restored.png)。
- `stage5a-final-latest.mp4` 与 `stage5a-final-latest-contact.png` 仅用于 0.5× 慢放的打开/收起和关闭动画开关证据，不宣称覆盖上述每一项专项场景。
- 冻结六项肉眼验收：无明显空白、无双标题重影、无源恢复残留、黑白彩色字可读、关闭清理正确、整体观感自然。主控已复核关键帧和最新联系表，5A 沙盒方向通过。
- 测试 XML：`app/build/test-results/verifyDebugUnitTests/` 七个 suite 共 31 项，失败 0、错误 0、跳过 0；其中 `MorphMathTest` 3 项、`MorphControllerTest` 3 项。XML 时间为 2026-09-21 15:31:54。本报告按 XML 核实为 31 项，不沿用旧报告的 28 项。
- 未测：真实 Today/Week 接入、下拉手势、预测性返回、真实列表滚动与周末横向滚动、正式帧率 profiling、设置持久化、Room 迁移、多窗口/横屏，以及全量真机 §5.7 场景。
