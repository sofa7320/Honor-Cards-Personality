# ACCEPTANCE ROUND 24 — v51-final（round-25 门禁卡修复 + OPPO face server + 小米 guard；v0.2.13）

Artifact: `work\module\poc\card-personality-v51-final.apk`, 37,523,111 B, sha256 **2FBD1F7F06A79271A9CE197417CC0932CC97C9FD72EA876E0179B92F1A7418B1**（versionCode=1 / versionName=**0.2.13**；本地交付：不 push/tag/Release）。
Device: Honor 192.168.31.10:5555，LSPosed 2.2.1（API 102）。

## 1) 静态 — PASS
- aapt：`versionCode='1'`、`versionName='0.2.13'`、**`application-label='药做卡面'`**
- **API102-only 保持**：无 `assets/xposed_init`；`META-INF/xposed/{module.prop,java_init.list,scope.list}` 三件（module.prop min101/target102/staticScope）；**10 dex**；**0 de.robv 类 / 0 libxposed 类定义**；`hook.api` 7 类
- **scope.list 7 包**：hihonor/finshell/coloros/oplus/oneplus/nearme + **com.miui.tsmclient**
- dex 标记：`xi_record`、`xi_replace`、`xi_capture_model`(2)、`recapture_req`、`nativeFaceKey`(2)、`cardPicUrl`、`saveModelSnapshot`、`shouldCaptureModel`、`captureAccessModel`(6)、`readFieldString`、`ACTION_RECAPTURE`/`RECAPTURE_ORIGINAL`、`OppoFaceServer`(14)、`WalletFaceClient`(14)、`server-up/put-ok`、`isOppoWallet`、`push-face-skip`、`XiaomiFaceHookEngine`(90)、`XiaomiBridge`(41)、`HookBridge`、`Xposed102Entry`、`FaceHookEngine`(388)、ANR guard `getter hook re-entered`、端口 47121

## 2) 许可证检查 — PASS（无需改）
- `repo/LICENSE` = **GNU GPL v3.0**；README「许可证」→ GPL-3.0 链接 ✓
- `NOTICE.md` 中 Apache-2.0 条目均为**第三方依赖**（Miuix、AndroidX/Compose、Kotlin、libxposed、AGP/Gradle）→ 正确保留，不应改
- `.github/workflows/build.yml`：NOTICE.md 仅作为 Release 资产上传（`NOTICE.md` 行 198 + release notes 资产说明），**NOTICE 生成逻辑无 Apache/项目许可声明**；workflow 内 `--licenses` 仅 Android SDK 接受许可
- README/CHANGELOG 无残留项目级 Apache 引用 → **PASS，无需修改**

## 3) 荣耀门禁卡（本轮核心）— PASS（含一条使用前提）
| 场景 | 结果 |
|---|---|
| 门禁卡编辑器预览（钱包已渲染过该门禁卡） | **PASS** — 打开「充电桩卡」编辑器，预览显示钱包当前门禁卡卡面（截图 round25_access_editor_after_capture.png，说明行 `导出 2016 × 1248（6x）`），不再空白/旧图 |
| 「重置卡面」后 | **PASS** — log：`original capture invalidated access:F621A1F3 / carkey:…`；钱包侧恢复钱包原生门禁卡卡面（round25_access_after_reset.png 卡包/详情）；模块不覆盖钱包文件（重置仅删模块配置/快照） |
| 重置后预览重抓 | **PASS（需钱包渲染）** — 钱包打开一次门禁卡详情后，编辑器预览再次显示钱包当前卡面 |
| 经典门禁卡（faceId 0..999，无钱包自定义） | 静态确认：`nativeFaceKey` 逻辑 + `faceId` 区间判定；本机门禁卡为钱包原生自定义卡面，无第二张经典门禁卡可实测 |
| 公交/车钥匙/银行卡/校园卡回归 | **PASS** — 公交：`SUBSTITUTED`+`applied face`；车钥匙/银行卡未安装，路径未改（静态确认 hook 仅 ACCESS 走新 before hook） |

**前提/限制**：若钱包进程**从未加载过**该门禁卡（冷启动后直接开编辑器），编辑器预览会先显示「还没有卡面图片」，需钱包侧渲染一次门禁卡（打开卡包/详情）后重开编辑器即显示。属抓取依赖，非错图/旧图。

## 4) 荣耀回归 — PASS
| item | result |
|---|---|
| 卡面替换 | PASS — `nh5#c SUBSTITUTED` + `applied face … iv_traffic_card_bg` |
| 编辑/保存 | PASS（示例底图保存） |
| 组件隐藏 | PASS（hidden tv_card_label 生效） |
| 旋转/数值 | carried PASS（v48 实测；v51 未改相关代码） |
| ZIP | PASS — `岭南通·羊城通_20261117-183841.zip` 76,085 B |
| 关于页 | PASS — 标题 **药做卡面**、版本 v0.2.13、反馈、开源支持 **4 行**（Miuix / LSPosed / oplus-card-customizer / **卡面工坊**） |
| 诊断导出 | PASS — `diagnostic_20261117-183304.txt` 28,717 B；`应用版本：0.2.13`、report JSON（transit/access/carkey）、bridge 段、**小米智能卡引擎（bridge HELLO 汇总，v0.2.13+）** 段 |
| 双击电源冷启动 | PASS — `SwipeCardActivity` 自定义卡面（round25_doublepress.png） |
| 冷启动 ×2 | **PASS — 0 real ANR/FATAL** |
| 清理 | PASS — config/exports cleared、原卡面、stayon false、lspd 10/10 |

## 5) OPPO / 小米（无真机，静态）
- OPPO：`OppoFaceServer`（47121-47123）、`WalletFaceClient`、`server-up/put-ok` 标记在；荣耀设备不启动 OPPO server（walletPackage 判定）
- 小米：`XiaomiFaceHookEngine`(90)、`XiaomiBridge`(41)、`isOppoWallet/push-face-skip`（非 OPPO 不再产生 push-face-fail 噪音）静态确认；无小米真机 → 静态验证

## 6) 版本/仓库
- 0.2.13；repo 当前分支 `release/v0.2.12` HEAD `c077571 release: merge v0.2.8–v0.2.12 (rounds 12–23) into main`；本地 `main` 仍 `523ab54`（v0.2.7）；tags 仍 `v0.2.5/v0.2.7/v0.2.10/v0.2.11/v0.2.12`（**无 0.2.13 tag**）；最新 Release 仍 v0.2.12 Pre-release

## 结论
**PASS（Honor 核心门禁卡 + 全回归 + 静态 + 许可证）**；门禁卡预览在“钱包已渲染过该卡”前提下正确、重置恢复且不覆盖钱包文件；API102-only/公开 API 无回归、0 ANR/FATAL。
**待 Lead 打 tag 发 v0.2.13 Pre-release 并开 PR→main**；发布后核对 prerelease/assets/正文。

## 7) Pre-release 核对（待 Lead 发布后补充）
