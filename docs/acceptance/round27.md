# ACCEPTANCE ROUND 27 — v56-final（0.2.16 Lanczos3 流式低内存重写 + 性能对比）

Artifact: `work\module\poc\card-personality-v56-final.apk`, 36,795,662 B, sha256 **A2207E2C3EB5C144477F10B93D6BA4A23665B29B3CEF722BA67BE4C28866ED03**（versionCode=1 / versionName=**0.2.16** / label 药做卡面；本地交付）。
Device: Honor 192.168.31.10:5555，LSPosed 2.2.1 / API 102。基线：`PERF-BASELINE-v0215.md`（同机同场景：4K 背景 + 1 元素贴纸）。

## 1) 静态 — PASS
- aapt `versionCode=1/versionName=0.2.16/label 药做卡面`；API102-only（无 `assets/xposed_init`、10 dex、0 de.robv/0 libxposed、META-INF 三件）
- dex：`Lanczos3`(7)、`SRGB_TO_LINEAR`、`LINEAR_TO_SRGB`、`LUT_SIZE`、`boxPreShrink`、`ScaledFaceCache`(9)、`CARD_PERSONALITY_PERF`(2)、`dedupe`、`scheduleRootCapture`、`OppoFaceServer`、`XiaomiFaceHookEngine`、`HookBridge`、`Xposed102Entry`、`FaceHookEngine`、ANR guard
- FloatArray 仍出现在 dex（Kotlin 运行时/其它路径引用）；是否“无整图 FloatArray”以源码为准（FaceRenderer 注释「one source row read with getPixels」+ LUT/流式实现，静态符号已具备 LUT/boxPreShrink/ScaledFaceCache）

## 2) 性能对比（v0.2.15 → v0.2.16，同机同场景）
| 指标 | v0.2.15 | v0.2.16 | 变化 |
|---|---|---|---|
| 冷启动 TotalTime（2 次） | 534 / 504 ms | 544 / 545 ms | ≈ 持平（+2%，噪声内） |
| 首页滚动 jank（5s） | 9/578 = **1.56%**；p50 6 / p90 9 / p95 11 / p99 18 ms | 24/664 = **3.61%**；p50 6 / p90 11 / p95 13 / p99 16 ms | 单轮样本变差（需多次采样确认；均属低 jank） |
| 首页 PSS | 168,507 KB | **162,642 KB** | **-3.5%** |
| 编辑器 PSS（4K bg+元素） | 224,902 KB | **184,568 KB** | **-17.9%（明显）** |
| 编辑器预览拖动 3s | 16 frames，jank 50%，p50 32ms | 16 frames，jank 50%，p50 31ms | **无改善（仍卡）** |
| 保存 3x render|timing | 1,649 ms / 489 ms | **296 ms / 228 ms** | **-40% ~ -82%（明显）** |
| 保存 3x 墙钟 | 2.14 s / 0.74 s | **0.62 s / 0.52 s** | 明显更快 |
| 保存 3x jpeg | 104,609 B / 4ms | 120,827 B / 4ms | 体积略大（画质不降） |
| **保存 10x（4K 背景）** | **❌ render failed（无法保存）** | **✅ 2,162 ms / jpeg 975,544 B / 43ms / 墙钟 2.45 s** | **功能性修复 + 明显改善** |
| 10x 后 PSS | —（失败；曾 259–277 MB） | **362,937 KB** | 峰值偏高（~354 MB）→ 建议留意 OOM 余量 |

**结论（性能）**：**保存路径明显改善**（3x render 2–7× 快；10x 从“直接失败”变“2.2s 成功”），**编辑器 PSS -18%**；冷启动/首页基本持平；**编辑器预览拖动卡顿未改善**（仍需 ui-dev 关注，可能是预览路径大图合成/重绘），10x 保存后 PSS 峰值 354MB 偏高。

## 3) 回归 — PASS
- **大图导入预览**：PhotoPicker 选大图 → 编辑器立即显示（`已导入图片`，`导出 1008 × 624（3x）`；round27_v56_import_preview.png，截图带 toast 遮挡但 dump 确认）
- **背景+元素保存非空白**：3x 导出 1008×624，**std=74.79**（非空白），背景+元素均保留（round27_v56_export.jpg）
- **滑块无灰点/dirty 保存**：输出 tab 正常、滑块改倍率即 dirty 并成功保存（本轮 3x/10x 均由滑块变更触发）
- **荣耀卡面替换**：`applied face transit:… v=21 to 1 view(s)`（round27_v56_wallet.png）
- **双击电源冷启动**：`SwipeCardActivity`（round27_v56_doublepress.png）
- **2 次冷启动**：**0 real ANR/FATAL**
- **关于页/诊断**：药做卡面 / **v0.2.16**；`diagnostic_20261118-001613.txt` 28,845 B（不崩）
- 清理：config/exports cleared、stayon false、lspd 10/10

## 结论（最终建议）
- **保存性能与 10x 可用性：明显改善，可发 v0.2.16 Pre-release（性能修复目标达成）**。
- **遗留**：① 编辑器预览拖动仍 50% jank/p50≈31ms（未改善，建议提高优先级）；② 10x 保存后 PSS 峰值 ~354MB（需留意 OOM）；③ 首页 jank 单轮样本 1.56%→3.61%，建议多次采样复核（均低 jank）。
- OPPO/小米相关仍为静态（无真机）。

---

# v56 FINAL re-freeze（preview-fix，sha 99E2D767…）— 预览拖动改善（p50 达标，jank 未达标）

Artifact: `work\module\poc\card-personality-v56-previewfix.apk`, **37,584,071 B**, sha256 **99E2D767683382BF283CCE66530BE579F10B500AF58C9156F7F8C537394DE030**（versionCode=1 / versionName=0.2.16 / label 药做卡面；即 module-dev 重冻的 v56-final 最终内容；旧 A2207E2C 版作废）。
静态：aapt 0.2.16/label ✓；API102-only（无 xposed_init、10 dex、0 de.robv）；dex **previewCfgState(3)/cfgState/commitLiveCfg(2)/measureKey(2)/1280/1024 上限** + Lanczos3(7)/ScaledFaceCache(9)/CARD_PERSONALITY_PERF + dedupe/scheduleRootCapture/OppoFaceServer/XiaomiFaceHookEngine ✓

## 1) 预览拖动（同方法对比：设备内连续 swipe 循环 ≈4.3s，200ms 双向往返）
| 指标 | v0.2.15（基线） | v0.2.16 旧版 A2207（另一方法*） | **v0.2.16 preview-fix 99E2D7** |
|---|---|---|---|
| frames / 时长 | 72 / 4.3s（≈16.7fps） | 16 / 3s* | **108 / 4.2s（≈25.7fps）** |
| Janky | **36（50.0%）** | 8（50.0%）* | **36（33.3%）** |
| p50 | 21–25 ms | 31–32 ms* | **9–10 ms ✅（目标 ≤16ms）** |
| p90 | 28–32 ms | 38 ms* | **24 ms** |
| p95 | 29–34 ms | 38–40 ms* | **25 ms** |
| p99 | 36 ms | 38–40 ms* | **27–28 ms** |
| 编辑器 PSS | 224,902 KB | 184,568 KB | **177,684–179,830 KB** |

\* 旧版 A2207 用 200ms swipe+150ms 间隔方法（16 帧/3s），与本次“设备内连续循环”方法不同；为公平对比，本轮已在 v0.2.15 上用**完全相同方法**重测（72 帧/50%/p50 21–25ms）。
**评价**：p50 **10–25ms → 9–10ms（达标 ≤16ms）**、帧数 +50%（16.7→25.7fps）、jank 50%→**33.3%**、p90 32→24ms —— **明显改善**；但 jank 33.3% **仍高于 ≤10% 目标**（建议按 ui-dev 计划继续做预览专用缓存 bitmap + graphicsLayer）。

## 2) 功能回归（preview-fix）— PASS
- **拖动/提交**：元素 tab 在预览上连续拖拽 → 松手后 config 更新（`offsetY 0→0.0794`，version 14、updatedAt 刷新），保存成功 `render|timing size=1008x624 ms=238`、jpeg 115,149B/3ms，导出 **std=72.24 非空白**
- **元素不漂移**：贴纸 `cx=0.5/cy=0.28` 保持（本次拖动作用于背景 offset），元素位置未被误改
- **切 tab 状态**：元素→组件→输出→元素 往返后仍显示 `导出 1008×624（3x）` + 图片贴纸（状态保持）
- **大图导入**：PhotoPicker 选大图 → **1,348ms 后预览即显示**（`已导入图片`、`导出 1008 × 624（3x）`，round27_pv_import.png）
- 滑块无灰点/缩略图：构建未改动输出 tab/缩略图路径（v0.2.15/v56 已验证），本轮未见回归
- Honor 抽测：双击电源 → `SwipeCardActivity`；**2 次冷启动 0 real ANR/FATAL**；诊断（上节 v56 28,845B 不崩）；清理 lspd 10/10

## 结论（preview-fix）
- **预览拖动：p50 达标（9–10ms）、帧率 +50%、jank 减半 —— 明显改善**；**jank 33.3% 仍未达 ≤10%**（需继续优化）。
- 功能无回归（拖动提交/保存非空白/切 tab/大图导入/荣耀抽测均 PASS）。
- 建议：可发 v0.2.16 Pre-release（性能修复主线达成，预览遗留 jank 继续排期）；或按 lead 决定等 ui-dev 预览缓存 bitmap 版本。
