# 发布与签名（维护者）

本项目发版使用 **release 构建 + 固定签名**，由 GitHub Actions 自动完成；Release 说明自动取自 `CHANGELOG.md` 中对应版本的内容。

## 1. 签名密钥

发布签名使用一个 PKCS12 keystore（**不要提交到仓库**，`.gitignore` 已忽略 `*.keystore` / `work/`）。

本机生成（示例）：

```bash
keytool -genkeypair -keystore release.keystore -storetype PKCS12 \
  -alias cardpersonality -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "<密码>" -keypass "<密码>" \
  -dname "CN=Card Personality, O=Open Source, C=CN"
```

> 当前维护者的 keystore 位于本机 `work/signing/release.keystore`（不随仓库分发，请备份；
> 丢失后无法再以相同签名发布更新，用户升级需要卸载重装）。

## 2. GitHub Actions Secrets

在仓库 **Settings → Secrets and variables → Actions** 添加以下 4 个 secret：

| Secret | 内容 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | keystore 文件的 base64（`base64 -w0 release.keystore`） |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | key alias（如 `cardpersonality`） |
| `RELEASE_KEY_PASSWORD` | key 密码（与 store 密码相同即可） |

未配置这些 secret 时，`release` job 会自动回退到 **debug 签名**（保证 fork/PR 也能出包），并在日志里给出 warning。

## 3. 本地 release 构建

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export RELEASE_STORE_FILE=/absolute/path/release.keystore
export RELEASE_STORE_PASSWORD="<密码>"
export RELEASE_KEY_ALIAS=cardpersonality
export RELEASE_KEY_PASSWORD="<密码>"
gradle :app:assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

Windows 用 `pwsh -File scripts/build.ps1 -Tasks ":app:assembleRelease"`（同样读取上述环境变量）。

## 4. 发版流程

1. 修改 `versionName`（`versionCode` 固定 1）并更新 `CHANGELOG.md`。
2. 提交并推送 `main`。
3. 打 tag 并推送：
   ```bash
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```
4. Actions 的 `release` job 会：
   - 用 secrets 解码 keystore → `:app:assembleRelease`（release 签名）；
   - 从 `CHANGELOG.md` 提取 `## [X.Y.Z]` 小节生成 **Release 正文**（含本次更新内容 + 安装说明 + 资产列表）；
   - 发布 `CardPersonality-vX.Y.Z.apk`、`.sha256`、`NOTICE.md`。
5. 发布后核对 Release 正文与 APK 版本号。

## 5. 升级注意

- debug 签名 → release 签名的首次切换需要**先卸载旧版**（签名不同 Android 不允许覆盖安装）。
- 之后同签名可直接覆盖升级。
- `versionCode` 恒为 1（本项目策略），升级依赖覆盖安装而非应用商店。
