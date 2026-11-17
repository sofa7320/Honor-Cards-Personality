# ACCEPTANCE ROUND 23 FINAL — v49-final（re-freeze，含 ui-dev round-23 UI；v0.2.12）

Artifact（authoritative re-freeze）: `work\module\poc\card-personality-v49-final.apk`, 37,400,339 B, sha256 **64231676D413302BCEBBFBD79C13C68BBCF12B400BC47CE36FD0D7651C3A36C8**（versionCode=1 / versionName=**0.2.12**；本地交付：不 push/tag/Release）。
> 早期无 UI 版 v49（17DAC931…，36,631,799 B）已被本版取代（其 Honor 原图重抓链路已单独实测 PASS，仅作参考）。
Device: Honor 192.168.31.10:5555，LSPosed 2.2.1（API 102）。

## 1) 静态 — PASS
- aapt `versionCode='1' versionName='0.2.12'`
- **API102-only**：无 `assets/xposed_init`；`META-INF/xposed/{module.prop,java_init.list,scope.list}` 三件；10 dex；**0 de.robv 类 / 0 libxposed 类定义**；`hook.api` 7 类
- dex 标记：`faceUrl`、`resolveFaceByUrl`、`put-ok/config-ok/replace-hit/replace-miss`、`invalidateAll`、`requestCapture`、`INVALIDATE_DEBOUNCE_MS`、`refreshOriginalSnapshots`、**`pushFace`**、**`OriginalSnapshotRefresh`**、**`OppoFaceUrlPush`**、`HookBridge`、`Xposed102Entry`、`skipPlaceholder`、`REQ_CARDS`、`127.0.0.1`、荣耀 `FaceHookEngine`(278)、ANR guard `getter hook re-entered`；无 `IXposedHookLoadPackage`

## 2) 荣耀：编辑器预览 / 重置重抓 / 钱包文件不被覆盖 — PASS
- 打开编辑器 → 预览显示当前卡面（自定义卡面紫色样例，非空白；`导出 1344×832（4x）`）—— `OriginalSnapshotRefresh.refreshIfNeeded` 打开/onResume 去抖链路正常
- 「重置为钱包原始卡面」→ 恢复 → log：`original capture invalidated access:… / carkey:…`（invalidateAll / refresh 生效）
- 钱包详情随后显示 **钱包原始黄色卡面**（岭南通·羊城通）→ 模块未覆盖钱包文件，重置即恢复原生
- 证据：round23b_editor_open.png、round23b_after_reset_wallet_crop.png

## 3) 荣耀回归 — PASS
| item | result |
|---|---|
| 卡面替换 | PASS — `applied face … iv_traffic_card_bg`、`nh5#c SUBSTITUTED … content://com.cardpersonality.honor.face/…` |
| 组件隐藏 | PASS — `element HIDE tv_card_label` |
| 编辑/保存 | PASS |
| ZIP | PASS — `岭南通·羊城通_20261117-125651.zip` 76,087 B |
| 关于页 | PASS — `v0.2.12` / 「反馈」/「开源支持」 |
| 诊断导出 | PASS — `diagnostic_20261117-125722.txt` 28,140 B；`应用版本：0.2.12` + bridge 段（isConnected=false/port=47131/bridgePorts） |
| 双击电源冷启动 | PASS — 聚焦 `SwipeCardActivity` 自定义卡面（round23b_doublepress4.png） |
| 冷启动 ×2 | **PASS — 0 real ANR/FATAL** |
| 清理 | PASS — config/exports cleared、原卡面、stayon false、lspd 10/10 |

## 4) 版本/仓库
- 0.2.12（versionCode 1）；branch `feat/round23` HEAD `e15d25d build: include compileOnly libxposed api 102 jar and drop legacy xposed-stub (fix CI)`；`main` 仍 `523ab54`（v0.2.7，未合并）；tags 仍 `v0.2.5/v0.2.7/v0.2.10/v0.2.11`（**无 0.2.12 tag**）；最新 Release 仍 v0.2.11 Pre-release

## 结论
**PASS（Honor 全量 + 静态）**：re-freeze 版 v49 的荣耀原图/预览重抓（打开/返回去抖 + 重置）实测正常，钱包原始卡面恢复且文件未被覆盖；API102-only 与全部荣耀回归无问题、0 ANR/FATAL。
**待 OPPO 真机**：保存后看 `bridge|push-face`(含 faceUrl)、`oppo|put-ok`/`replace-hit`、`replaceCount>0`；若 replace-miss 导出 url 对比（静态 `resolveFaceByUrl/urlAliases/faceUrl` 已就位）。

## 5) Pre-release 核对（待 Lead 发布后补充）
