# ACCEPTANCE ROUND 23 — v49-final（OPPO URL 精确匹配 + 荣耀原图重抓；v0.2.12）

Artifact: `work\module\poc\card-personality-v49-final.apk`, 36,631,799 B, sha256 **17DAC931BF10389D9CA3D92B1103195EDF8F94A49CE3202275BC21E00B756D62**（versionCode=1 / versionName=**0.2.12**；本地交付：不 push/tag/Release）。
Device: Honor 192.168.31.10:5555，LSPosed 2.2.1（API 102）。

## 1) API102-only 保持 — PASS
- 无 `assets/xposed_init`；`META-INF/xposed/{module.prop,java_init.list,scope.list}` 三件；10 dex；**0 de.robv 类、0 libxposed 类定义**；`hook.api` 7 类
- 钱包进程 log：`XPOSED102_READY pkg=com.hihonor.wallet proc=com.hihonor.wallet api=102`；hook 实际生效：`nh5#c SUBSTITUTED … uri=content://com.cardpersonality.honor.face/…`、`applied face … iv_traffic_card_bg`、`element HIDE tv_card_label`

## 2) 荣耀原图重抓/双向保存 — PASS（核心新功能）
- 编辑器「重置为钱包原始卡面」→ 确认「恢复」→ log：`original capture invalidated transit:… / access:… / carkey:…`（invalidateAll 生效）
- 钱包详情恢复为**钱包原始卡面**（黄色 岭南通·羊城通，非模块自定义）→ 模块只替换参数、不覆盖钱包文件
- 重新进入编辑器：预览显示「钱包当前卡面」（黄色原卡面）且说明行 `导出 2016 × 1248（6x）`，**预览不再空白/旧图**（重抓成功）
- 证据：round23_editor_before_reset.png、round23_after_reset_wallet_crop.png、round23_preview_after_reset.png、ui_r23_*.xml

## 3) 荣耀回归 — PASS
| item | result |
|---|---|
| 卡面替换 | PASS（SUBSTITUTED + applied face） |
| 编辑/保存 | PASS（示例底图保存；ZIP `岭南通·羊城通_20261117-124751.zip` 76,088 B） |
| 组件隐藏 | PASS（element HIDE tv_card_label） |
| 旋转/数值 | carried PASS（v48 实测 -270/270、弹窗 -270~270；本轮编辑器渲染未变） |
| 双击电源冷启动 | PASS（SwipeCardActivity 自定义卡面） |
| 冷启动 ×2 | **PASS — 0 real ANR/FATAL** |
| 关于页 | PASS（`版本 v0.2.12`、「反馈」、「开源支持」） |
| 诊断导出 | PASS（`diagnostic_20261117-124711.txt` 26,538 B；`应用版本：0.2.12` + bridge 段正常） |
| 清理 | PASS（config/exports cleared、原卡面、stayon false、lspd 10/10） |

## 4) 静态 — PASS
aapt `versionCode='1' versionName='0.2.12'`；dex 标记：`resolveFaceByUrl`、`urlAliases`(3)、`put-ok/config-ok/replace-hit/replace-miss`、`faceUrl`、`invalidateAll`、`requestCapture`、`INVALIDATE_DEBOUNCE_MS`、`refreshOriginalSnapshots`、`HookBridge`、`Xposed102Entry`、`skipPlaceholder`、`REQ_CARDS`、`127.0.0.1`、`FaceHookEngine`(278)、ANR guard。

## 5) 版本/仓库
- 0.2.12（versionCode 1）；branch `feat/round23` HEAD `e15d25d build: include compileOnly libxposed api 102 jar and drop legacy xposed-stub (fix CI)`；`main` 仍 `523ab54`（v0.2.7，未合并）；tags 仍 `v0.2.5/v0.2.7/v0.2.10/v0.2.11`（**无 0.2.12 tag**）；最新 Release 仍 v0.2.11 Pre-release

## 结论
**PASS（Honor 全量 + 静态）**：v49 的荣耀原图重抓/双向保存实测正常（reset → invalidate → 钱包原面恢复 → 预览重抓），API102-only 与荣耀回归无问题、0 ANR/FATAL。
**待 OPPO 真机**：保存 OPPO 卡面后应见 `bridge|push-face`（带 url）、`oppo|put-ok`/`replace-hit`、`replaceCount>0` 且卡面变化；若 replace-miss 导出 `oppo|replace-miss` url 与推送 url 对比（静态确认 `resolveFaceByUrl/urlAliases` 已就位）。
