# 项目收尾与发布

日期：2026年10月13日。最终版本：1.3.7（versionCode 16）。

## 整理结果

- 保留 app 源码、业务测试、数据库 schema、Gradle Wrapper、当前设计依据及签名配置。
- 历史规范、计划、报告和截图统一移到 docs/archive。
- 删除未调用的 MorphSandbox 演示页及两个 Android 模板示例测试；这些文件仍可从 Git 历史恢复。
- 本机日志、旧备份、动画分析资料与临时设备配置共 50 个旧文件（6.5 MB）。自动审批曾拒绝批量永久删除，先归档并列出清单，经使用者明确授权后已永久删除该归档。
- 最终安装包与 SHA-256 校验文件放在 dist；验证结束后已清理 .gradle、.kotlin、build、app/build 等可重建缓存。
- README、当前范围和发布说明更新到 1.3.7，应用内日志与文档同步。

## 设计与范围自查

本次没有改动正式页面布局、配色或交互。DESIGN_BRIEF §7.1 的黑色焦点、中性框架、装饰限制、字体规则、今日/周视图及数据层约束均保持原实现；仅调整日志文字、版本与无调用的开发残留。

§5.3 的单一驱动、统一生命周期、两层固定布局、来源布局占位、顶层展示与性能路径全部保持原课程详情实现。无新增设计偏差，未扩展 §11。

## 验证与交付

- clean 后 Debug、正式 Release 与 AndroidTest 包构建通过。
- verifyDebugUnitTests：205 项通过；移除的第 206 项是模板 2+2 示例，业务测试未删。
- apksigner verify 通过；APK 包名 com.burgess.schedule，版本 1.3.7 / 16，继续使用原正式签名。
- 最终文件：dist/schedule-app-1.3.7.apk 与 dist/SHA256SUMS.txt。
- APK SHA-256：22696ba5603894f28379481ca2e18d9eb34349f3e2f002794de20cadb1cf49f5。
- 当前 README 的本地链接已检查，无断链；签名密钥、本机配置、缓存与安装包不会进入源码仓库。
- GitHub 源码目标为 main，发布标签为 v1.3.7；发布前核对附件，发布后复核远程主分支、标签与附件校验值。

[源码仓库](https://github.com/BURGESSSSSS/schedule-app) · [正式发布页](https://github.com/BURGESSSSSS/schedule-app/releases/tag/v1.3.7)

## 验收

GitHub 发布页 → 下载正式 APK → 应能覆盖升级；更多 → 开发日志 → 应看到 1.3.7；首次安装 → 欢迎介绍 → 查看教程 → 可以阅读导入步骤。实际企业微信采集沿用原实现，本次未重复登录学校教务系统采集。
