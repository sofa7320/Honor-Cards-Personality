# 药做卡面

给手机钱包里的卡换一张喜欢的卡面。LSPosed 模块，Miuix（HyperOS）风格界面，本地处理、不上传图片。

> 前身 / 曾被称作「荣耀卡面工坊」。包名 `com.cardpersonality.honor` 保持不变。

## 支持的钱包

| 钱包 | 包名 | 状态 |
|---|---|---|
| 荣耀钱包 | `com.hihonor.wallet` | ✅ 卡面读取、替换、原图同步 |
| 欧加钱包（OPPO / ColorOS / 一加） | `com.finshell.wallet` | ✅ 卡面读取（loopback bridge），保存链路持续修复中 |
| 小米智能卡 | `com.miui.tsmclient` | 🚧 适配中 |

## 功能

- **卡面替换**：在模块里挑图、裁剪、加贴纸和文字，保存后钱包里对应卡面立即变化。
- **与钱包原生卡面共存**：钱包自己设置的卡面会被识别，模块只替换显示，不修改钱包数据；「重置卡面」即恢复钱包原生卡面。
- **卡片识别**：自动列出钱包里的交通卡、门禁卡、车钥匙、银行卡等（按钱包能力）。
- **多钱包**：荣耀 / 欧加 / 小米（适配中）。
- **导出与分享**：卡面打包导出、自定义卡面归档。
- **诊断**：一键导出诊断报告，便于反馈问题。

## 环境要求

- Android 8.0+（minSdk 28），已 root 并启用 **LSPosed**（libxposed API 101+，本项目仅使用 API 102 现代模块）。
- 荣耀钱包 / 欧加钱包 / 小米智能卡 对应安装在作用域内。

## 使用

1. 安装 APK，在 LSPosed 中启用「药做卡面」，作用域勾选要支持的钱包。
2. 打开钱包，把卡片列表/详情浏览一遍，让模块记录卡片。
3. 回到「药做卡面」→ 选择卡片 → 编辑卡面 → 保存。
4. 打开钱包即可看到效果；若未生效，重启钱包或点击首页「刷新」。

## 从源码构建

```bash
# Windows
scripts/build.ps1
```

需要 JDK 17、Android SDK（compileSdk 37）与 Gradle Wrapper。libxposed API 102 以 `compileOnly` 引入（`app/libs/libxposed-api-102.jar`），运行时由 LSPosed/Vector 提供。

## 版本与发布

- 版本号规则：`versionCode` 固定为 1，`versionName` 语义化递增。
- `0.2.6` ~ `0.3.0` 之前均为 **0.3.0 预发布系列**，正式 0.3.0 发布时将统一汇总为「自 0.2.5 以来的变更」。
- 发布走 GitHub **Pre-release**；代码通过 **Pull Request** 合并，不直接推 `main`。

## 开源支持

- [Miuix](https://github.com/miuix-kotlin-multiplatform/miuix) — Compose UI 组件与设计规范
- [LSPosed / Vector](https://github.com/JingMatrix/LSPosed) — 模块框架
- [oplus-card-customizer](https://github.com/Sharrn/oplus-card-customizer) — 欧加钱包适配参考
- [卡面工坊](https://github.com/MiamBao/cardface-miuix) — 小米智能卡卡面自定义支持参考

## 许可证

[GNU General Public License v3.0](LICENSE)
