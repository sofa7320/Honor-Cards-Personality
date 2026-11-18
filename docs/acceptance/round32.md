# ACCEPTANCE ROUND 32 — v61-final（0.2.21：文字发光/描边 + 门禁卡原图可用性）

Artifact: `work\module\poc\card-personality-v61-final.apk`, 36,861,198 B, sha256 **A564DE80AEE26B7B3F3E4671DEC1DDA84F890A218682D5E83F96194CFC96C918**（versionCode=1 / versionName=**0.2.21** / label 药做卡面；本地交付）。
Device: Honor 192.168.31.10:5555，LSPosed 2.2.1 / API 102。基线：v0.2.20（52FE974F…）。

## 1) 静态 — PASS
- aapt `versionCode=1/versionName=0.2.21/label 药做卡面`；API102-only（无 assets/xposed_init、10 dex、0 de.robv、META-INF 三件）
- ui dex：`measureKey`(2)、`renderTextOffscreen`(6)、`PlaceholderFace`(1/16)、glow/stroke、retry
- module dex：`accessPendingCapture`、`accessViews`、`findWalletCachedFace`(3)、`readImageBytes`、`original-ok`、`capture-pending`、`RECAPTURE_ORIGINAL`、`nativeFaceKey`(2)、`cardPicUrl`、`source=`(classes4/9/10 共 7 处)
- 保留：`NavEntry`(9)、`HomeSnapshot`(6)、`Lanczos3`(7)、`CARD_PERSONALITY_PERF`、dedupe/scheduleRootCapture/OppoFaceServer/XiaomiFaceHookEngine/HookBridge/Xposed102Entry/FaceHookEngine/ANR guard

## 2) 文字发光/描边 — PASS（预览即时 + 导出像素级）
配置注入文字贴纸（GLOW，44dp，发光=红 glowRadius 24，描边=蓝 strokeWidth 6，白字）：
- **预览**：截图可见蓝色描边 + 红色光晕（`r32_text_glow_preview.png`）
- **即时性**：`发光` 开启→关闭 预览区域 diff **16.55**，关闭→开启 **16.55**，两次开启态像素 diff **0.00**（可重复、无滞后）；`描边` 开关 diff **2.80**（开启生效）
- **3x 保存**：`render|timing size=1008x624 ms=313`、jpeg 117,354B
- **导出像素级比对**（同文字/同位置/同底色，仅发光+描边开/关两版）：
 | 指标（文字区域） | 开启 | 关闭 | 差值 |
 |---|---|---|---|
 | 红主导像素（发光光晕） | 66,567 | 44,783 | **+21,784** |
 | 蓝主导像素（描边） | **16,323** | **0** | **+16,323** |
 → 导出 JPEG 中**文字周围确有发光色、边缘确有描边色**，且关闭时描边像素为 0（严格区分）
- 切换/拖动文字时效果保持（拖动后保存导出仍含发光/描边）

## 3) 门禁卡「钱包尚未渲染该卡」— PASS（占位/重试/ON_RESUME/原图落盘）
冷启动态（wallet force-stop、面板冷启、清除 originals）进入门禁卡编辑器（充电桩卡）：
- **中性占位**：预览显示「**正在向钱包获取该卡卡面…（可直接选择图片）**」+「已显示中性占位底图，可直接编辑/选择图片；获取到钱包卡面后会自动替换」（`r32_acc_placeholder.png`）
- **自动重试**：oplog 每 ~2s `config|recapture-broadcast|access:F621A1F3` + `bridge|refresh-original`；logcat `original capture invalidated access:F621A1F3`（15s 内多次）；wallet 未渲染时 fallback 走 cardPicUrl 下载（期间记录 `original face download failed url=.../access_pic/...` → 旧签名过期）
- **ON_RESUME / 钱包渲染后取到原图**：打开钱包卡包渲染该门禁卡 → 返回面板：**占位消失**，显示「钱包当前卡面」；oplog：
 `bridge|original-ok|access:F621A1F3 source=wallet bytes=193613`
 `bridge|original-ok|access:F621A1F3 source=url bytes=193613`
 且冷态路径已记录 `access|capture-pending|access:F621A1F3 view-save`（失败态不标记已抓、可重复）
- **原图落盘**：`files/originals/…jpg` 193,613 B（12:42 新增）
- **保存不丢背景**：取到原图后编辑器正常保存（3x render 日志见 §4）；占位态下的单独保存未单独用例（渲染同一路径）
- 限制：Toast「已获取钱包卡面」弹窗未在日志/截图捕获（瞬时 UI toast，未取到）；source 类型本次实测到 **wallet / url** 两种，resource/file/view 未在本机分别触发（保留静态存在）

## 4) 回归 — PASS（大图导入本轮未复测）
| item | 结果 |
|---|---|
| 预览拖动（motionevent ~30Hz，40 步） | **PASS** — 3 次：39/39/40 帧、jank **2（5.00–5.13%）**、p50 6ms、p90 9–10ms（v0.2.20 同法 5%/6–8/10–11，无回归） |
| 返回转场 3 次 | PASS — 3 次均返回 Home；51–52 帧、jank 5.77–5.88%、p90 12–14ms |
| 3x 保存 | PASS — `render|timing 324ms`、jpeg 121,062B、导出 **1008×624 std=74.33 非空白** |
| 大图导入 | **未复测** — 保存后编辑器已关闭，`更换图片` 点击落在 Home（未打开 picker）；该路径本轮 ui 未改（图片导入），上轮 v0.2.20 实测 1,297ms |
| 缩略图 | PASS（Home 缩略图/已自定义标记正常） |
| 荣耀卡面替换 | PASS — `applied face transit:… v=19` |
| 双击电源冷启动 | PASS — `SwipeCardActivity` |
| 2 次冷启动 | **PASS — 0 real ANR/FATAL** |
| 关于页/诊断 | PASS — 药做卡面 / v0.2.21；`diagnostic_20261118-125352.txt` 31,127 B（不崩） |
| 清理 | PASS — config/exports cleared、stayon false、lspd 10/10；已删除 /data/local/tmp/drag.sh 与临时配置 |

## 5) 附加观察
- 门禁卡冷态 URL fallback 下载失败（旧签名过期）后，钱包渲染完成即通过 view/模型捕获成功（source=wallet），说明「recapture + 视图兜底」链路有效；后续若能在 URL 层加签名失效后自动刷新 report 可进一步减少占位等待。
- 编辑器 PSS：179,688–203,050 KB 区间（含文字/贴纸/拖动后），与上轮同区间。

## 结论
**PASS**：
- ✅ 文字发光/描边：预览即时生效（开关 diff 16.55/2.80 可重复）、**导出 JPEG 像素级可见**（发光 +21,784 红像素、描边 +16,323 蓝像素，关闭时描边 0）
- ✅ 门禁卡占位：冷态显示中性占位可编辑文案、2s 级自动重试、钱包渲染后/ON_RESUME 自动替换为原图（`original-ok source=wallet/url`，193,613B 落盘）、失败不标记已抓
- ✅ 回归无异常：拖动 jank 5%、返回 p90 12–14ms、3x 导出非空白、钱包替换/双击电源/2 次冷启动 0 ANR、诊断不崩
- ⚠ 未覆盖：Toast 文案捕获、占位态单独保存、resource/file/view 三种 source 的本机分别触发、大图导入复测（均已在文中标注）
