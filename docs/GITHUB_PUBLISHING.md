# 上传源码与发布 APK

项目地址：<https://github.com/BURGESSSSSS/schedule-app>

默认分支为 main，当前正式版为 1.3.7。源码存放在仓库，Android 安装包通过 GitHub Releases 提供，GitHub Pages 不能运行原生 Android App。

## 维护源码

修改后先检查 git status 和 git diff，提交必要的源码与文档，再将提交推送到 main。保留现有历史，不强制覆盖远程提交。

仓库保留 app、Gradle Wrapper、数据库 schema、当前文档和历史档案。local、dist、构建目录、本机 SDK 路径、日志、设备凭据和签名密钥均由 .gitignore 排除。

## 发布流程

1. 更新 app/build.gradle.kts 的 versionName，并递增 versionCode；当前为 1.3.7 / 16。
2. 同步应用内开发日志、docs/DEVLOG.md 和 docs/releases 下的对应版本说明。
3. 使用原正式签名密钥构建，运行现有业务测试：

   .\gradlew.bat :app:assembleRelease :app:verifyDebugUnitTests --no-configuration-cache

4. 用 Android SDK 的 apksigner verify 校验签名，确认 APK 版本与源码一致。
5. 将 APK 复制到 dist/schedule-app-对应版本.apk，生成 SHA256SUMS.txt。
6. 提交并推送源码，创建指向对应提交的版本标签。
7. 在 GitHub Releases 先建立草稿，上传 APK 与校验文件，核对附件后发布。
8. 核对公开发布页、远程提交、版本标签和下载附件的 SHA-256。

当前正式附件为 schedule-app-1.3.7.apk 和 SHA256SUMS.txt。后续升级必须继续保留原 keystore.properties 与签名密钥，不能把它们上传到仓库。

本次发布通过 GitHub REST API 复用 Git Credential Manager 保存的登录，凭据仅留在进程内，不写入项目或发布说明。

## 项目整理

当前说明放在 docs，旧报告、规范和截图放在 docs/archive，最终包放在 dist。本机旧日志、备份和分析素材已经使用者确认删除；dist 不随源码上传。

## 官方参考

- [上传已有本地项目](https://docs.github.com/en/migrations/importing-source-code/using-the-command-line-to-import-source-code/adding-locally-hosted-code-to-github)
- [管理 Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)
