# 七阶段改进记录

用户已授权连续执行七个阶段，不设中途审批。基线提交保留原项目；原项目没有 Git 仓库。

## 阶段 1：主题与基础组件
- 新增 AppColors、AppType、AppSpace、AppShape、Motion、Appearance 和基础组件预览，更多页提供 debug 入口；原页面颜色引用映射到新主题。
- 验证：assembleDebug 和 verifyDebugUnitTests 通过，10 项测试（含 5 色 × 2 主题对比度）。修复现有测试类加载失败，AAR 的 classes.jar 提供给 JVM 运行器。
- 改动文件：app/build.gradle.kts、MainActivity.kt、ui/theme/{Color,Theme,Type,Shape,Motion,Appearance}.kt、ui/components/Components.kt、ui/CourseContrastTest.kt。
- 偏差：本机 DNS 无法解析 Maven 和 GitHub，DataStore 暂用隔离的 SharedPreferences 实现；Inter 下载未完成，暂用系统字体。基础组件共用浅/深组件画廊预览，尚非每个函数独立预览。图标素材与统一 ripple 待补充。课程文字按 .32 起每次 +.04 自动提高混合比例至对比度合格。新尺寸 token 仅集中命名规范已给出的尺寸；未请求中途审批，遵从用户最新授权。
- 真机：ADB 无设备，视觉及触感验收待验证。

## 阶段 2：底部导航
- 新增 FloatingNavigation，三项纯文字、移动选中背景、静态阴影、96dp 加系统导航 inset。Scaffold 将底部空间传给所有页面。Tab 改为交叉淡入淡出。
- 改动：MainActivity.kt、ui/components/FloatingNavigation.kt。assembleDebug 通过，浅深预览齐全。
- 偏差：手势/三键导航尚未真机验证；触感采用 ContextClick 并忽略不支持情况。

## 阶段 3：今日页
- 单一时间线、就地高亮当前或下一节，已结束课程用 mut，次日预览不重复首课，首次使用提供导入和添加。当前状态/倒计时为独立纯函数；前台分钟对齐刷新。
- 改动：MainActivity.kt、ui/today/TimelineState.kt、ui/TimelineStateTest.kt。assembleDebug 与 17 项测试通过。
- 偏差：页面仍是 Column，性能优化留待阶段 7；进度随分钟更新也使用进入动画时长，尚未真机对比。预览覆盖浅/深空状态。

## 阶段 4：周视图
- 固定五列、按需周末列、横向端点吸附与跳转芯片、固定时间轴、日期圆圈、课程简称与可访问描述；保留编辑/导入/复制回调。
- 数据库先导出 v5，再生成 v6 与 5→6 AutoMigration，原四条迁移保留。编辑可选简称并实时预览。
- 改动：MainActivity.kt、CourseEntity.kt、CourseDatabase.kt、app/build.gradle.kts、schemas/{5,6}.json、ui/week/CourseLabel.kt、CourseLabelTest.kt。编译及既有 17 测试通过，简称测试随下一轮运行。
- 偏差：拉丁简称按长度判定字号，未使用实际测量；无设备，布局验收以源码与预览定义为限。

## 阶段 5：共享课程详情
- 官方 SharedTransitionLayout 在 Scaffold/导航之上；来源与详情 sharedBounds，标题独立共享 key，保留来源尺寸；SeekableTransitionState 联动预测返回与下拉，编辑和删除接回现有 DAO；删除 5 秒撤销。
- 改动：MainActivity.kt、ui/components/CourseTransition.kt、AndroidManifest.xml。assembleDebug、18 项单元测试通过。未切换方案 B，没有证据认定 F1–F4。
- 验收 1 今日打开关闭十次：未执行，无设备。2 周格子十次：未执行。3 展开中点遮罩：代码支持状态反转，未实测。4 半程下拉：已实现，未实测。5 倍率 1.6：可配置，未录屏。6 导航上层：根级 overlay 已接入，未设备验证。7 最终速度：原 Motion 常量保留，等待用户真机选择。
- 偏差：最新官方 BOM 为 2026.09.00，但本机 DNS 阻断下载，沿用已有 2026.02.01；圆角/底色尚未完整插值，内容分段透明度未逐项精确实现，不能声明已解决全部帧级瑕疵。

## 阶段 6：更多与子页面
- 更多页分组列表、主题选择、复制开发者邮箱、动态版本 footer。日志完整内容改为版本折叠卡片；取件码保留查件、WebView、登出、导出，新增手动添加、待取/已取分组、左滑标记与 5 秒撤销。提醒换统一容器，保留开关/权限/测试功能。
- 改动：MainActivity.kt、ui/components/SubPageScaffold.kt。assembleDebug 与 18 项测试通过。
- 偏差：提醒暂为底部容器，提前分钟数在阶段 7 接入；仍有部分旧页面硬编码间距和非 Material 图标，主题持久化仍受 DataStore 下载阻断影响。取件码 WebView 保留原生浏览内容，不对外部页面重新配色。

## 阶段 7 与正式打包交接
- 已接入：5/10/15/30 分钟提醒、单色通知/主题图标、JSON SAF 备份导入导出（事务合并）、课程冲突提示、截图导入方式面板、周编辑底部操作栏及清空撤销。移除已替换的旧页面组件和残留弹窗阴影。
- 最近 Debug 编译和 18 项单元测试通过；Pixel_9 模拟器已安装启动，无 crash buffer 记录。
- 未完成：Glance 小组件、Baseline Profile、全量 Material Symbols 与 Inter/DataStore、全部 UI token 清理、200% 字体及全部转场逐帧验收。用户因额度要求停止增强，优先交付正式 Release APK。
- 注意：这不是七阶段完整验收结论；正式打包仅表明 release 配置编译签名通过。
