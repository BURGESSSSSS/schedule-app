# 上传源码与发布 APK

项目地址：<https://github.com/BURGESSSSSS/schedule-app>

源码用于保存和开发项目；Releases 中的 APK 用于在 Android 手机上安装。GitHub Pages 不能直接运行这个原生 Android App。

## 第一次上传源码：本项目实际执行的过程

1. 检查项目已有的 Git 历史、工作区和远程地址。
2. 检查上传范围，确认本机配置、签名密钥、构建产物和录屏被 `.gitignore` 排除。
3. 添加 README，保存一次本地提交：

   ```powershell
   git add -- .gitignore README.md
   git commit -m "Prepare project for GitHub publishing"
   ```

4. 使用者在 GitHub 创建公开空仓库 `schedule-app`，不额外初始化 README、.gitignore 或 License。
5. 连接该仓库，保留原开发分支并新建 `main`：

   ```powershell
   git remote add origin https://github.com/BURGESSSSSS/schedule-app.git
   git switch -c main
   ```

6. 上传源码和当前分支的提交历史：

   ```powershell
   git push -u origin main
   ```

   Git Credential Manager 负责 GitHub 登录。`-u` 让本地 `main` 跟踪远程 `main`，之后通常只需 `git push`。

7. 核对 GitHub 默认分支、仓库可见性和远程提交编号，确认上传结果。

以上初始化命令已经执行过，之后不需要重复创建远程地址和 `main` 分支。

## 以后更新源码

在项目目录打开 PowerShell，先查看改动，再提交自己确认要上传的文件：

```powershell
git status
git diff
git add -- README.md
git commit -m "Update project documentation"
git push
```

这里的 `README.md` 是示例，按实际修改替换文件路径。`git commit` 保存到本机，`git push` 才上传到 GitHub。

## 发布安装包

1. 确认 `app/build.gradle.kts` 的 `versionName` 和 `versionCode`。后续更新应使用新的版本号和递增的 `versionCode`。
2. 在本机保留原正式签名密钥及 `keystore.properties`，后续版本使用同一密钥，才能覆盖升级。密钥和签名密码不上传到源码仓库。
3. 在项目目录构建并运行现有测试：

   ```powershell
   .\gradlew.bat :app:assembleRelease :app:verifyDebugUnitTests --console=plain
   ```

4. 确认构建成功，用 Android SDK 的 `apksigner verify` 验证正式 APK 签名，并核对 APK 内的版本号。正式 APK 输出在 `app/build/outputs/apk/release/app-release.apk`。
5. 提交、上传本次源码，使发布版本对应远程已保存的提交。
6. 打开仓库的 Releases，点击 **Draft a new release**，创建版本标签（本次为 `v1.3.6`），选择对应源码提交，填写标题和说明。
7. 在附件区域上传正式 APK（本次为 `schedule-app-1.3.6.apk`）及校验文件，等待上传完成，再点击 **Publish release**。
8. 打开发布页，确认 Assets 中存在 APK，下载并核对 SHA-256 校验值。

本次由 Codex 使用 GitHub REST API 完成同样的流程：先建立草稿、上传并核对附件，再公开发布。认证复用 Git Credential Manager 已保存的 GitHub 登录，凭据不写入项目文件或发布说明。

## 官方参考

- [上传已有本地项目](https://docs.github.com/en/migrations/importing-source-code/using-the-command-line-to-import-source-code/adding-locally-hosted-code-to-github)
- [管理 Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)
