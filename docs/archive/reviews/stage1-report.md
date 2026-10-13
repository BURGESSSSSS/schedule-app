# 阶段 1：主题与基础组件

## 1. 大白话总结

这一步统一了界面的“零件”：英文和数字使用 Inter，中文继续使用系统字体；按钮、卡片和设置行按下时各有合适的反馈。返回箭头和列表图标换成统一的单色圆角图标。设置列表整组一个边框，内部只用分割线。调试版的“设计预览”现在可以实际点击按钮、打开基础弹窗和切换示例开关，示例不读写课程数据。

尚未重排今日页、周视图或更多页，也没有开始详情转场。不要把这份阶段报告理解为七阶段已经完成。

安全措施：分支 `redesign/design-brief`；改版前快照 `d01efdd`。快照保留工作区当时删除旧文档的状态，纳入新的意见书和已有的 app/.gitignore。用户的 video_review/ 文件保持原样，没有加入本阶段提交。

## 2. 改动文件清单

- `AGENTS.md`：设计依据更新为 DESIGN_BRIEF.md。
- `app/src/main/java/com/burgess/schedule/ui/theme/Type.kt`：静态字体家族，400 / 500 / 600 字重及等宽数字。
- `app/src/main/java/com/burgess/schedule/ui/theme/Theme.kt`：统一 Material 3 有边界水波纹的中性色和 8% 透明度。
- `app/src/main/java/com/burgess/schedule/ui/theme/Shape.kt`：图标尺寸常量。
- `app/src/main/java/com/burgess/schedule/ui/components/Components.kt`：按压反馈、图标按钮、整组列表、行图标、主题开关。
- `app/src/main/java/com/burgess/schedule/ui/components/DesignPreview.kt`：独立的调试预览页、假数据与可操作示例。
- `app/src/main/java/com/burgess/schedule/MainActivity.kt`：旧卡片及两种小按钮包装函数改为调用公共组件；预览改为全宽并增加返回按钮。没有改页面业务逻辑。
- `app/src/main/res/font/inter_{regular,medium,semibold}.ttf`：Inter 4.1 三个静态字重。
- `app/src/main/res/drawable/symbol_*.xml`：9 个 Material Symbols Rounded 400 图标；保留原始路径，平移坐标原点以适配 Android。
- `app/src/main/assets/licenses/`：Inter、Material Symbols 许可证及来源记录。
- `docs/reviews/stage1-report.md`：本报告。

## 3. 审查者自查

以下明确区分本阶段达标和全项目尚待后续阶段完成；未检查的页面不标成通过。

### §7.1 必须项

| 要求 | 结果与位置 |
|---|---|
| 每页一个重点大块、框架中性、关闭动态配色 | 本阶段满足：DesignPreview 仅一个 InkCard；Theme 沿用固定中性色。其他真实页面尚未完成全量验收。 |
| 无违规阴影、渐变、模糊、彩色图标、emoji | 本阶段满足：Components 和预览没有新增上述效果；唯一 shadow 仍在 FloatingNavigation。 |
| 今日课程不重复、结束课程不整体降透明度、对比度达标 | 对比度测试满足：五色×两主题及次要文字通过；今日状态原有测试通过，真实今日页留阶段 3 验收。 |
| 等宽数字、400/500/600 字重 | 本阶段满足：Type 的 tnum 与静态字体；解析字体文件确认字重分别为 400/500/600，均无可变字体表。 |
| 周视图原有功能、五列与按需周末 | 本阶段未改网格或相关功能；阶段 4 再验收，不宣称全项目已达标。 |
| 详情满足 §5.3 六条性质 | 尚不满足：旧 CourseTransition 尚未替换；详见下表，阶段 5 处理。 |
| 不改数据层业务逻辑 | 满足：Room 实体、DAO、迁移、Repository、ViewModel 均未修改。 |
| 编译通过、分支和阶段提交可回退 | 满足：Debug 与 Release 构建成功，正式包签名验证通过，阶段单独提交。 |

### §5.3 转场性质

| 性质 | 现状与位置 |
|---|---|
| 单一进度驱动 | 尚不满足：CourseTransition 仍使用原共享动画，阶段 5 替换。 |
| 收起完成才清除课程 | 尚不满足：MainActivity 的详情关闭路径待阶段 5 调整。 |
| 两层内容固定尺寸、不逐帧重排 | 尚不满足：CourseTransition 尚未建立新的固定内容层。 |
| 来源保留占位、同帧恢复 | 尚不满足完整验收：原 CourseSource 仍依赖共享转场机制。 |
| 弹窗覆盖导航 | 原实现声明根层覆盖；本阶段未对真实详情进行验收，不能判定完全满足。 |
| 性能平稳 | 尚未满足验证要求：真实详情慢放、打断和帧性能留阶段 5 检查。 |

### 本阶段验证证据

- `gradlew.bat :app:assembleDebug :app:assembleRelease :app:verifyDebugUnitTests --offline --no-configuration-cache --console=plain`：BUILD SUCCESSFUL。
- 18 项单元测试，0 失败、0 错误、0 跳过；包含课程色对比度、课程简称、今日状态和提醒逻辑。
- Release APK：apksigner verify 成功，v2 签名通过。
- 模拟器检查：浅色预览和基础弹窗；深色 360dp 宽、系统字体 1.3 倍下预览顶部及滚动后的列表/五种课程色。截图未发现文字截断，箭头、通知、外观图标正确显示。
- 模拟器设置已恢复为检查前的 420dpi、1.0 字体、浅色。真机触感、动态按压效果仍需使用者检查。
- 本地构建记录和截图：`app/build/qa/stage1-build.log`、`stage1-light-fixed.png`、`stage1-sheet.png`、`stage1-dark-360-130.png`、`stage1-dark-components.png`（构建目录，不进入版本库）。

自查修复：首次截图发现 SVG 的负 Y 原点没有转换，图标显示为空；已增加坐标平移并复查截图。旧 CompactActionButton 的 0.96 缩放也已改为公共小按钮反馈。

## 4. 与意见书不一致之处及原因

- 阶段 1 的可见样式无有意偏离。沿用已正确实现的颜色、圆角和基础动效常量，没有为文件数量重新实现一遍。
- 基础弹窗示例仍使用 Material 3 ModalBottomSheet，仅用于检查基础外观；课程详情的单进度形变严格留到阶段 5，不将本示例当作转场验收。
- 移除了预览页原来的“动效倍率”滑块：旧滑块仅调试有效且倍率方向与 §6 相反；用户动画速度设置在阶段 6 按新要求实现。
- 既有 portableAndroidRuntime 测试任务不能保存 Gradle 配置缓存，第一次完整命令在缓存存储环节报错；使用 --no-configuration-cache 后完整命令成功。没有顺带重构构建任务。
- 已有 §11 相关功能保留，没有扩展。

## 5. 真机检查清单

设计预览只在调试版可见，正式版不显示该入口；正式安装包不是七阶段最终成品。

1. 调试版打开“更多” → 滚到底部 → 点击“设计预览” → 应看到课程示例、按钮、图标和列表。
2. 按住普通卡片、重点卡片 → 应轻微缩小；按住大按钮 → 应略多缩小；按住小胶囊和圆形按钮 → 应有局部水波纹。
3. 按住“课程提醒”示例行 → 整行背景轻微变色、不缩放；点示例开关 → 应切换开关和说明文字。
4. 点击“打开弹窗” → 应出现无描边、无阴影的圆角弹窗；点击“完成” → 应返回预览。
5. 切换系统深色、将字体放大至 1.3 倍 → 重新打开预览并上下滚动 → 图标可见、文字不截断、课程色文字清楚。

下一阶段：底部悬浮导航。详情空白卡片、重影和关闭生命周期将在阶段 5 专门处理。
