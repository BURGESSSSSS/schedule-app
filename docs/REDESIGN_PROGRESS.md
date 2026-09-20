# 七阶段改进记录

用户已授权连续执行七个阶段，不设中途审批。基线提交保留原项目；原项目没有 Git 仓库。

## 阶段 1：主题与基础组件
- 新增 AppColors、AppType、AppSpace、AppShape、Motion、Appearance 和基础组件预览，更多页提供 debug 入口；原页面颜色引用映射到新主题。
- 验证：assembleDebug 和 verifyDebugUnitTests 通过，10 项测试（含 5 色 × 2 主题对比度）。修复现有测试类加载失败，AAR 的 classes.jar 提供给 JVM 运行器。
- 改动文件：app/build.gradle.kts、MainActivity.kt、ui/theme/{Color,Theme,Type,Shape,Motion,Appearance}.kt、ui/components/Components.kt、ui/CourseContrastTest.kt。
- 偏差：本机 DNS 无法解析 Maven 和 GitHub，DataStore 暂用隔离的 SharedPreferences 实现；Inter 下载未完成，暂用系统字体。基础组件共用浅/深组件画廊预览，尚非每个函数独立预览。图标素材与统一 ripple 待补充。课程文字按 .32 起每次 +.04 自动提高混合比例至对比度合格。新尺寸 token 仅集中命名规范已给出的尺寸；未请求中途审批，遵从用户最新授权。
- 真机：ADB 无设备，视觉及触感验收待验证。
