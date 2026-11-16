# ACCEPTANCE ROUND 22 — v48-final（API102-only 迁移 + OPPO 作用域/占位卡/原图重抓；v0.2.11）

Artifact（authoritative，重新冻结版）: `work\module\poc\card-personality-v48-final.apk`, 37,378,799 B, sha256 **6A2F191E2294B703699E0C1AE037274A646015AA5AF49C46643C2DFC81E756BC**（versionCode=1 / versionName=**0.2.11**；本地交付：不 push/tag/Release）。
> 早期无 UI 版 v48（2F2AED93…，36,615,415 B）已被本版取代，仅用于 API102 快速冒烟（亦 PASS）。

Device: Honor 192.168.31.10:5555，LSPosed 2.2.1（API 102）。

## 1) API102-only 加载（本轮最高风险）— PASS
- `CARD_PERSONALITY XPOSED102_ALIVE process=com.hihonor.wallet system=false api=102 framework=LSPosed 2.2.1`
- `CARD_PERSONALITY XPOSED102_READY pkg=com.hihonor.wallet proc=com.hihonor.wallet api=102`
- 无 legacy 入口（无 assets/xposed_init，引擎仍打印 CARD_PERSONALITY_ALIVE 仅为内部日志）：**Honor 完全走 API102 内部 HookBridge，卡面替换实际生效**
  - `applied face … to 1 view(s): iv_traffic_card_bg`
  - `nh5#c SUBSTITUTED … uri=content://com.cardpersonality.honor.face/…`
  - `element HIDE tv_card_label (HwTextView, was=0)`

## 2) 荣耀全量回归 — PASS
| item | result | evidence |
|---|---|---|
| 卡面替换 | PASS — applied face + SUBSTITUTED | round22_detail.png, logcat |
| 编辑/保存 | PASS — 示例底图+文字贴纸保存 | round22_saved.png |
| 组件隐藏 | PASS — element HIDE tv_card_label | logcat |
| 旋转/数值 | PASS — 两端 -270°/270°；弹窗“范围：-270° ~ 270°” | ui_r22_rotL/R/num.xml |
| 自适应渲染 | PASS — 顶部说明 `导出 1344 × 832（4x）`（按底图自适应） | ui_r22_ed.xml |
| ZIP | PASS — `岭南通·羊城通_20261117-015018.zip` 76,339 B | Download |
| 关于页 | PASS — `版本 v0.2.11`、「反馈」标题、「开源支持」区块 | round22_about.png, ui_r22_about.xml |
| 诊断导出 | PASS — `diagnostic_20261117-015102.txt` 25,671 B；含 `应用版本：0.2.11` + bridge 段 | round22_diag_toast.png, r22_diagnostic.txt |
| 双击电源冷启动 | PASS — `SwipeCardActivity` 自定义卡面 | round22_doublepress.png |
| 冷启动 ×2 | PASS — **0 real ANR/FATAL** | logcat |
| 清理 | PASS — config/exports cleared、原卡面、stayon false、lspd 10/10 | round22_final_original.png |

## 3) 诊断/UI — PASS
`bridge 状态：isConnected=false port=47131 lastHelloAt=0 lastHelloProcess=（无）`；raw JSON 含 `reportVersion/bridgeConnected/bridgePort/bridgePorts[47131..47135]`；Honor 未连接属预期；无崩溃。

## 4) 静态 — PASS
- aapt `versionCode='1' versionName='0.2.11'`
- **无 `assets/xposed_init`**、**manifest 无 xposed* meta**；`META-INF/xposed/{module.prop,java_init.list,scope.list}` 在（module.prop min101/target102/staticScope；java_init.list=Xposed102Entry；scope.list 6 包）
- **0 个 de.robv 类定义、0 个 io.github.libxposed 类定义**（compileOnly）；`com.cardpersonality.honor.hook.api` 7 类；10 dex
- dex 标记：`HookBridge/HookHelpers/MethodHook/Xposed102Entry`、`refreshOriginal/refreshOriginalSnapshots/DEL_ORIGINAL`、`skipPlaceholder`、`local-config-hit/miss`、`push-config/push-face`、`REQ_CARDS`、`127.0.0.1`、荣耀 `FaceHookEngine`(278)、ANR guard `getter hook re-entered`、provider authority `com.cardpersonality.honor.face`
- **无 `IXposedHookLoadPackage`**（legacy 接口已移除）

## 5) 版本/仓库
- `0.2.11`（versionCode 1）；repo branch `feat/round22` HEAD `900101c feat: v0.2.11 round-22 …`（clean）；`main` 仍 `523ab54`（v0.2.7，**未合并**，ahead 8）；tags 仍 `v0.2.5/v0.2.7/v0.2.10`（**无 0.2.11 tag**）；GitHub 最新 Release 仍 v0.2.10 Pre-release → 本轮尚未发布（Lead 将打 tag 发 Pre-release）

## 6) Pre-release 核对（待 Lead 发布后补充）
- 待核对：prerelease=true、assets（APK/sha256/NOTICE）、正文含 0.2.11 changelog、APK digest 与 `6A2F191E…` 对应（release 签名版体积会不同）。

## 结论
**PASS（Honor 全量 + 静态 + 诊断）**：API102-only 迁移在 LSPosed 2.2.1 上成功加载并正常 hook，荣耀全部功能无回归、0 ANR/FATAL；legacy 资产/metadata 已彻底移除且静态确认。OPPO 侧（作用域 com.finshell.wallet 推送、占位卡过滤、重置重抓、首页 auto-refresh）为静态验证 + 待用户真机。
