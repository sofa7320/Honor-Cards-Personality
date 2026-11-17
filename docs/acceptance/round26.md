# ACCEPTANCE ROUND 26 — v55-final（0.2.15：OPPO 去重 + Fin* 自绘容器抓取）

Artifact: `work\module\poc\card-personality-v55-final.apk`, 36,779,278 B, sha256 **F7DCED13386C82B861C22F985441FF1E938363080996CC3FCBF2F7CFEDBB5335**（versionCode=1 / versionName=**0.2.15** / label **药做卡面**；本地交付：不 push/tag/Release）。
Device: Honor 192.168.31.10:5555，LSPosed 2.2.1 / API 102。

## 1) 静态 — PASS
- aapt：`versionCode='1'`、`versionName='0.2.15'`、`application-label='药做卡面'`
- API102-only：无 `assets/xposed_init`；`META-INF/xposed/{module.prop,java_init.list,scope.list}` 三件；**10 dex**；**0 de.robv / 0 libxposed 类定义**
- dex 标记：`dedupe`(3)、`isAidIdentity`(2)、`dedupeUrl`(2)、`scheduleRootCapture`(3)、`reflectBitmapField`、`capture-root-miss`、`scheduleViewFaceCapture`(6)、`capture-view-ok`、`OppoFaceServer`(14)、`WalletFaceClient`(14)、`XiaomiFaceHookEngine`(90)、`HookBridge`、`Xposed102Entry`、`FaceHookEngine`(388)、ANR guard `getter hook re-entered`

## 2) OPPO 修复（静态验证，无真机）— PASS（静态）
- **去重**：identity 优先级 `getAid→getAppCode→getCardNo→getBizId→getCardId→getRecordId→getId`（AID 优先）在 dex 标记中；`dedupe`/`isAidIdentity`/`dedupeUrl` 静态确认（wallet+type+normalizedFaceUrl 去重逻辑已编译）
- **Fin* 自绘容器抓取**：`scheduleRootCapture` + `reflectBitmapField`（反射 Bitmap 字段，否则 `view.draw(Canvas)`）+ `capture-root-miss` 限流；ImageView 路径 `scheduleViewFaceCapture` 保留
- 运行时（`oppo|dedupe keep/drop`、`oppo|capture-view-ok`/`capture-root-miss`、面板缩略图）需 OPPO 真机（OnePlus 绿城通卡）验证 — 本机无 OPPO 钱包/设备

## 3) 荣耀抽测（v55 未改 Honor 路径）— PASS
| item | result |
|---|---|
| 版本/Home | PASS — Home `v0.2.15` + 状态卡正常 |
| 卡面替换 | PASS — `nh5#c SUBSTITUTED` + `applied face … iv_traffic_card_bg` |
| 组件隐藏 | PASS（hidden tv_card_label，钱包侧 hide 执行） |
| 关于页 | PASS — 药做卡面 / v0.2.15 |
| 诊断导出 | PASS — `diagnostic_20261117-234553.txt` 28,416 B（0.2.15 + bridge 段，不崩） |
| 双击电源冷启动 | PASS — `SwipeCardActivity` 自定义卡面（round26_doublepress.png） |
| 冷启动 ×2 | **PASS — 0 real ANR/FATAL** |
| 清理 | PASS — config/exports cleared、stayon false、lspd 10/10 |

## 4) 版本/仓库
- 0.2.15（versionCode 1）；branch `feat/round25` HEAD `74ead20 fix: export-scale slider marks editor dirty and applies on save; round-25 acceptance`；tags 仍 `v0.2.5/v0.2.7/v0.2.10/v0.2.11/v0.2.12/v0.2.13/v0.2.14`（**无 0.2.15 tag**）；最新 Release 仍 v0.2.14 Pre-release

## 结论
**PASS（静态 + Honor 抽测）**：0.2.15 的 OPPO 去重（AID 优先 + URL 去重）与 Fin* 根容器抓取代码均已编译进 APK（静态确认），荣耀回归无影响、0 ANR/FATAL。
**待 OPPO 真机**（OnePlus/PKB110 等）：验证绿城通卡只出现一次（identity=A000000632…）、`oppo|dedupe keep/drop`、面板缩略图有图（`oppo|capture-view-ok` 或 faceUrl 下载，失败见 `capture-root-miss`）。

---

# v55 FINAL re-freeze（authoritative，含 ui-dev round-26 UI）— PASS

Artifact: `work\module\poc\card-personality-v55-final.apk`, 37,565,679 B, sha256 **29550391794950743B0057639A1BE596EE6782EF378AD48D4795878B25680785**（versionCode=1 / versionName=**0.2.15** / label 药做卡面）。
> 早前无 UI 版（F7DCED13…, 36,779,278 B）的模块侧 OPPO 结论同样适用本最终版；本节为最终产物补充 UI 修复项实测。

## 静态 — PASS
- aapt `versionCode=1/versionName=0.2.15/label 药做卡面`；API102-only（无 xposed_init、10 dex、0 de.robv/0 libxposed、META-INF 三件、scope 7 含 com.miui.tsmclient）
- dex：`render|timing`(2)、`retry`、`blank` 校验、`CardFaceRowThumbnail`(19)、`showKeyPoints`、`ensureOriginalFace`、`dedupe`(3)、`scheduleRootCapture`(3)、`scheduleViewFaceCapture`(6)、`OppoFaceServer`(14)、`WalletFaceClient`(14)、`XiaomiFaceHookEngine`(90)、`HookBridge`、`Xposed102Entry`、`FaceHookEngine`(388)、ANR guard

## 1) 导入背景 — PASS
- 编辑器「选择图片」→ 系统 PhotoPicker 选大图截图 → **导入后预览立即显示**（round26b_after_import.png，说明行 `导出 1008 × 624（3x）`，非空白/旧图）

## 2) 高分辨率背景 + 元素「添加图片」— PASS
- 元素 tab「添加图片」→ 再选一张大图 → 预览中贴纸清晰显示在背景上（round26b_after_element.png）
- 保存：logcat `CARD_PERSONALITY_PERF: render|timing size=1008x624 bg=true drewBg=true ms=497 stickers=1`、`jpeg bytes=58652 ms=3`；总保存耗时 ≈6.1s，无失败 Toast/无坏卡
- **背景不丢失**：导出文件 1008×624（58,652 B），像素 std=55.24（非空白），**背景（大图截图）+ 元素贴纸均在**（round26b_export.jpg）；钱包详情 `applied face`/`SUBSTITUTED` v=1（round26b_wallet_detail.png）
- 渲染失败保护：空白/纯色校验 `blank`/grid variance 已在 dex（静态）；本轮未构造失败场景

## 3) OPPO 列表缩略图 — 静态确认（无 OPPO 卡，Honor 跳过）
- `CardFaceRowThumbnail`(19) + faceUrl 下载逻辑在 dex；运行时需 OPPO 真机

## 4) 输出滑块 — PASS（无灰点 + v0.2.14 修复回归）
- 输出 tab 无关键点灰点：滑块/数值「3x · 1008 × 624」/「当前倍率 3x，预计输出 1008 × 624 px」/「重置为钱包原始卡面」显示正常（round26b_output_tab.png）
- 拖滑块即 dirty：3x→**4x** 后直接保存 → 导出 **1344×832 / 82,898 B**；`render|timing size=1344x832 bg=true drewBg=true ms=942 stickers=1`

## 5) 荣耀抽测 — PASS
| item | result |
|---|---|
| Home/v0.2.15 | PASS（Home 状态卡 + v0.2.15） |
| 卡面替换 | PASS — `applied face` + `SUBSTITUTED`（v=1） |
| 编辑/保存 | PASS（见上，含背景+元素，耗时/画质正常） |
| 关于页 | PASS — 药做卡面 / v0.2.15 |
| 诊断导出 | PASS — `diagnostic_20261117-235141.txt` 28,558 B（0.2.15，不崩） |
| 双击电源冷启动 | PASS — SwipeCardActivity（round26b_doublepress.png） |
| 冷启动 ×2 | **PASS — 0 real ANR/FATAL** |
| 清理 | PASS — config/exports cleared、stayon false、lspd 10/10 |

## 结论（最终）
**v55-final（29550391…）= PASS**：导入背景即时显示、高分辨率背景+元素保存后背景/元素均保留、渲染计时正常（497–942ms）、无空白导出、滑块无灰点且改动即 dirty 生效；荣耀抽测 0 ANR/FATAL。OPPO 去重/root capture/列表缩略图为静态验证（无 OPPO 真机）。
