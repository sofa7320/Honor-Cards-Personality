# ACCEPTANCE ROUND 24 FINAL — v52-final（v0.2.13 门禁卡冷启动兜底 + 全量回归）

Artifact（authoritative re-freeze）: `work\module\poc\card-personality-v52-final.apk`, 37,525,859 B, sha256 **5009CC1D17BD1BA5192D850843574DE6288054176C94B93E731BFBFBBAFAB83E**（versionCode=1 / versionName=**0.2.13** / label **药做卡面**；本地交付：不 push/tag/Release）。
Device: Honor 192.168.31.10:5555，LSPosed 2.2.1（API 102）。先前 v51（2FBD1F7F…）已 PASS；v52 为同版本 + ui-dev 门禁卡冷启动兜底。

## 1) 门禁卡冷启动兜底（本轮核心新增）— PASS
场景：force-stop 钱包（本 session 从未渲染门禁卡）→ 直接开面板「充电桩卡」编辑器：
- 预览显示 **钱包当前卡面**（由 `CardInfo.faceUrl`/cardPicUrl 下载作底图，车库引擎照片，说明行 `导出 2016 × 1248（6x）`），**不再空白**
- 失败分支文案已编译：「钱包尚未渲染该卡，请先打开钱包卡包页」（静态 dex 命中）
- 钱包渲染一次门禁卡后/重置后：预览正常、`original capture invalidated access:F621A1F3`、钱包原生卡面恢复且模块不覆盖钱包文件
- 证据：round25b_access_coldstart_fallback.png、round25b_access_*、ui_r25b_acc_ed.xml

## 2) 静态 — PASS
- aapt：`versionCode='1'`、`versionName='0.2.13'`、`application-label='药做卡面'`（各 locale 回退同一名）
- API102-only：无 `assets/xposed_init`；`META-INF/xposed/{module.prop,java_init.list,scope.list}` 三件；**10 dex**；**0 de.robv / 0 libxposed 类定义**；`hook.api` 7 类
- `scope.list` 7 包：hihonor/finshell/coloros/oplus/oneplus/nearme + **com.miui.tsmclient**
- dex 新增：`FaceUrlFallback`(22)、`ensureDownloaded`(8)、`faceUrlFallbackFailed`(4)、**「钱包尚未渲染该卡」**；并含 `nativeFaceKey`、`cardPicUrl`、`captureAccessModel`、`RECAPTURE_ORIGINAL`、`OppoFaceServer`(14)、`WalletFaceClient`(14)、`XiaomiFaceHookEngine`(90)、`XiaomiBridge`(41)、`HookBridge`、`Xposed102Entry`、`FaceHookEngine`(388)、ANR guard `getter hook re-entered`

## 3) 荣耀回归 — PASS
| item | result |
|---|---|
| 卡面替换 | PASS — `nh5#c SUBSTITUTED` + `applied face … iv_traffic_card_bg` |
| 编辑/保存 | PASS |
| 组件隐藏 | PASS |
| ZIP | PASS（v51 实测 76,085 B；v52 仅预览兜底改动） |
| 旋转/数值 | carried PASS（v48 实测；v52 未改） |
| 关于页 | PASS — 标题 **药做卡面**、版本 v0.2.13、反馈、开源支持 **4 行**（Miuix / LSPosed / oplus-card-customizer / **卡面工坊**） |
| 诊断导出 | PASS — `diagnostic_20261117-184550.txt` 29,022 B；`应用版本：0.2.13`、wallets JSON 含 **com.miui.tsmclient（kind=xiaomi, installed=false）**、bridge 段、**小米智能卡引擎（bridge HELLO 汇总，v0.2.13+）** 段 |
| 双击电源冷启动 | PASS — `SwipeCardActivity` 自定义卡面（round25b_doublepress.png） |
| 冷启动 ×2 | **PASS — 0 real ANR/FATAL** |
| 清理 | PASS — config/exports cleared、原卡面、stayon false、lspd 10/10 |

## 4) OPPO / 小米（无真机，静态）
- OPPO：`OppoFaceServer`（47121-47123）、`WalletFaceClient`、`put-ok/server-up` 标记在；荣耀设备不启动 OPPO server
- 小米：`XiaomiFaceHookEngine`(90)/`XiaomiBridge`(41)、wallets 列表含 com.miui.tsmclient、`push-face-skip`（非 OPPO 不产生 push-face-fail）；无小米真机 → 静态

## 5) 许可证 — PASS（无需改）
- `LICENSE` = GPL-3.0；README 许可证 → GPL-3.0；NOTICE.md 的 Apache-2.0 条目均为第三方依赖（Miuix/AndroidX/Kotlin/libxposed/AGP），正确保留；workflow build.yml 仅将 NOTICE.md 作为 Release 资产上传，无项目级 Apache 声明

## 6) 版本/仓库
- 0.2.13；branch `feat/round24` HEAD `e2ab981 feat: v0.2.13 round-24 (rename 药做卡面; Xiaomi smart card support; Honor door-card faces; OPPO wallet-side face server; GPL-3.0; concise README)`；tags 仍 `v0.2.5/v0.2.7/v0.2.10/v0.2.11/v0.2.12`（**无 0.2.13 tag**）；最新 Release 仍 v0.2.12 Pre-release

## 结论
**PASS（Honor 门禁卡冷启动兜底 + 全回归 + 静态 + 许可证）**：v52 修复了 v51 的“冷启动首次预览空白”限制，门禁卡预览可从 faceUrl 下载兜底显示；荣耀全部回归 0 ANR/FATAL；OPPO/小米为静态验证（无设备）。

## 7) Pre-release 核对（待 Lead 发布后补充）
