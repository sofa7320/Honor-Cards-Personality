# 贡献指南 / Contributing

感谢你愿意为「荣耀卡面工坊」做贡献！本文说明开发环境、代码约定、提交流程与测试要求。
提交 PR 即表示你同意你的贡献以本项目的 [Apache License 2.0](LICENSE) 许可发布。

## 目录

- [开发环境](#开发环境)
- [构建](#构建)
- [代码约定](#代码约定)
- [项目结构](#项目结构)
- [测试要求](#测试要求)
- [提交规范](#提交规范)
- [Pull Request 流程](#pull-request-流程)
- [报告问题](#报告问题)
- [安全与隐私](#安全与隐私)

## 开发环境

| 工具 | 版本 |
|---|---|
| JDK | **17** |
| Android SDK Platform | **37**（compileSdk 37），Build-Tools 36+ |
| Gradle | **8.13**（未附带 wrapper，可自行 `gradle wrapper --gradle-version 8.13`） |
| AGP / Kotlin | 8.11.2 / 2.3.x |
| 真机（可选） | Android 12+，已 root + LSPosed（Zygisk），荣耀钱包用于联调 |

## 构建

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
gradle :app:assembleDebug
```

Windows：

```powershell
$env:JAVA_HOME    = "C:\path\to\jdk-17"
$env:ANDROID_HOME = "C:\path\to\android-sdk"
pwsh -File scripts/build.ps1
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 请勿在仓库中提交 `local.properties`、`build/`、`.gradle/`、APK、签名文件（已在 `.gitignore` 中忽略）。
- 修改 `hook/` 或 `FaceProvider` 后，务必做一次真机验证或至少确认钱包冷启动无 ANR（见下）。
- **`versionCode` 固定为 1，不要修改**；`versionName` 由维护者在发布时统一更新（当前 0.2.4）。

## 代码约定

- 语言：Kotlin；UI 使用 Jetpack Compose + Miuix（保持 Miuix 设计规范与动效一致性）。
- 缩进 4 空格；遵循 `kotlin.code.style=official`；避免无意义的大范围重排。
- **Hook 必须 fail-open**：任何 hook 异常都不能影响钱包主流程；无配置时严格 no-op。
- **禁止在钱包主线程做跨进程调用**；Provider 访问必须走工作线程 + 缓存（参考 `ProviderClient`）。
- 不修改钱包 APK、数据目录、数据库或系统分区；只做显示层替换。
- 新逻辑优先加在 `data/`、`ui/`、`FaceRenderer.kt` 等层，避免在 hook 中散落业务逻辑。
- 新增用户可见文案请同时提供中文；如涉及界面布局，请在 1280×2800 等常见分辨率下自测。

## 项目结构

```
app/src/main/java/com/cardpersonality/honor/
├─ hook/             # Xposed 显示层 hook、Provider 客户端（跨进程异步）
├─ ui/               # Miuix 编辑面板（预览、滑杆、贴纸、字体、ZIP 入口）
├─ data/             # 配置模型（JSON）、FaceRepository、ZIP 导入导出
├─ FaceRenderer.kt   # 卡面合成（预览与导出共用同一渲染管线）
└─ FaceProvider.kt   # 只读 ContentProvider（content://com.cardpersonality.honor.face）
```

架构、hook 清单、Provider 契约、线程/版本策略详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 测试要求

提交 PR 前请至少完成：

1. `gradle :app:assembleDebug` 构建成功。
2. 若是 UI 改动：在真机（或模拟器）打开编辑面板，验证所改功能 + 相邻功能不回归。
3. 若是 hook/Provider 改动：
   - 钱包冷启动 ≥ 2 次，无 ANR/FATAL；
   - 有配置 / 无配置两种状态均正常；
   - 停用模块后钱包恢复正常。
4. 若涉及新增卡类型或钱包版本适配，请附上钱包版本号与截图证据。

可选：运行 `docs/acceptance/` 中对应轮次的检查项作为回归清单。

## 提交规范

提交信息建议使用 [Conventional Commits](https://www.conventionalcommits.org/)：

```
feat: 新增 xxx
fix: 修复 xxx
docs: 更新 xxx 文档
refactor: 重构 xxx
perf: 优化 xxx 性能
chore: 构建/依赖/杂项
```

一次提交只做一件事；说明"为什么"而不只是"改了什么"。

## Pull Request 流程

1. Fork 仓库并从 `main` 拉分支：`git checkout -b feat/my-feature`。
2. 开发 + 本地构建 + 按[测试要求](#测试要求)自测。
3. 同步更新 [CHANGELOG.md](CHANGELOG.md) 的 `[Unreleased]` 部分（修复/新增分类）。
4. 提交 PR，描述里写清：改动内容、动机、测试方式（设备型号/系统/钱包版本）、截图或录屏。
5. CI（`.github/workflows/build.yml`）必须通过；维护者 Review 后合并。

**不要**在 PR 中提交：`build/`、APK、签名文件、`local.properties`、与改动无关的格式化、钱包的二进制/资源文件。

## 报告问题

提 Issue 时请附：

- 设备型号、系统版本（MagicOS/Android）、荣耀钱包版本、LSPosed 版本；
- 模块版本（面板首页显示 `vX.Y.Z`）；
- 复现步骤、期望结果、实际结果；
- 截图/录屏、logcat / LSPosed 日志（涉及 hook 时）；
- 是否已按「新装后先打开一次面板」的说明操作。

## 安全与隐私

- 不要提交任何用户数据、卡号、身份信息、设备序列号、token 或未脱敏日志。
- 不引入会收集/上传用户数据的依赖；本项目坚持本地处理。
- 发现安全问题请通过 Issue 私密渠道或维护者邮箱联系，不要在公开 Issue 中贴出可被利用的细节。

## 发版（维护者）

- `versionCode` 永远为 1；只有 `versionName` 随版本递增。
- 每次 `versionName` 变更时：更新 `CHANGELOG.md` → 提交推送 `main` → 打 tag 并推送：
  ```bash
  git tag vX.Y.Z
  git push origin vX.Y.Z
  ```
- 推送 `v*` 标签后，Actions 的 `release` job 会：
  - 使用仓库 secrets 解码 keystore，执行 `:app:assembleRelease`（**release 签名**）；
  - 从 `CHANGELOG.md` 提取该版本小节生成 **Release 正文**（本次更新内容 + 安装说明 + 资产列表）；
  - 发布 `CardPersonality-vX.Y.Z.apk`、`.sha256`、`NOTICE.md`。
- 签名密钥与 secrets 配置见 [docs/RELEASE.md](docs/RELEASE.md)。
- 发版后核对 Release 正文与 APK 版本号（面板首页显示 `vX.Y.Z`）。

## 许可证

本项目以 [Apache License 2.0](LICENSE) 发布；第三方组件声明见 [NOTICE.md](NOTICE.md)。
