# ACCEPTANCE ROUND 29 — v58-final（0.2.18：返回转场单层 + 拖动 draw phase + 内存缓存收敛）

Artifact: `work\module\poc\card-personality-v58-final.apk`, 36,812,046 B, sha256 **027E889F8D49BF334504EEDA9C4201D37B9A96D97A42E4340AE054D738D5FE4C**（versionCode=1 / versionName=**0.2.18** / label 药做卡面；本地交付）。
Device: Honor 192.168.31.10:5555，LSPosed 2.2.1 / API 102。场景：4K 背景 + 1 元素贴纸。基线：v0.2.17（7CA110A8…）。

## 1) 静态 — PASS
- aapt `versionCode=1/versionName=0.2.18/label 药做卡面`；API102-only（无 assets/xposed_init、10 dex、0 de.robv、META-INF 三件）
- dex：`transitionSpec`(14+4)、`__home__`、`HomeSnapshot`(4)、`listState`(3)、`SelectionOverlay`(13)、`ScaledFaceCache`(10)、`decodeCap`、`3072`、`Lanczos3`(7)、`CARD_PERSONALITY_PERF`、dedupe/scheduleRootCapture/OppoFaceServer/XiaomiFaceHookEngine/HookBridge/Xposed102Entry/FaceHookEngine/ANR guard

## 2) 返回动画单层 — PASS（静态强证据 + 功能 3 次；逐帧未取到）
- 静态：`AppRoot` 的 `AnimatedContent(targetState = screen.key)` 使用稳定字符串 key（`editor:<key>` / `__about__` / `__home__`，dex 命中 `__home__`），`transitionSpec` 依赖 key → Home snapshot/reload 更新不再重触发转场（对应修复“双滑”）
- 功能：Force-stop→Home→进编辑器→X 返回，**连续 3 次**：返回全部成功（返回并出现 Home `已识别的卡片`），耗时 2,508 / 2,530 / 2,591 ms（含 200ms 轮询 + uiautomator dump ≈1s 开销）；未观察到二次滑动/闪烁（截图序列一致）
- 限制：该机 `screenrecord` 输出为静态帧（多次复现，疑似厂商录制限制），`screencap` 快速序列也未捕捉到转场中间帧 → **逐帧滑动层数未验证**，以静态 key/transitionSpec 证据 + 功能 3 次返回为准

## 3) 预览拖动性能 — **未达标（数据记录）**
同方法（设备内连续 swipe 循环 ≈4.2s，4K 背景+元素）：
| 指标 | v0.2.17 基线 | **v0.2.18** | 目标 |
|---|---|---|---|
| frames | 108 | 108 | — |
| Janky | 36（33.33%） | **36（33.33%）×2 rep** | ≤10% ❌ |
| p50 | 10 ms | **9 ms** | ≤16ms ✅ |
| p90 / p95 / p99 | 25 / 27 / 28–29 ms | **24–25 / 28–30 / 30–34 ms** | — |
→ p50 略好（9ms），**jank 仍 33%**，与上一版持平；draw-phase 优化未改变 jank 比例（帧调度/其它绘制开销仍存在）。

## 4) 10x 保存 PSS 与耗时
| 指标 | v0.2.17 | **v0.2.18 rep1** | **v0.2.18 rep2** |
|---|---|---|---|
| 保存前 PSS | 171,961 | 177,824 | 177,582 |
| **峰值 PSS** | **315,072 KB** | 332,223 KB | 311,762 KB（均值 322,000） |
| 保存后 PSS | 221,104 | **185,201** | **151,698**（均值 168,450，**-24%**） |
| render\|timing | 197 ms | 193 ms | 151 ms |
| jpeg | 1,238,074 B | 1,234,007 B | 1,190,515 B |
| 导出校验 | 3360×2080 | **std=76.33 非空白** | — |
- **峰值 PSS 未继续下降**（均值 ~322MB vs 315MB，±噪声内，且均低于 v0.2.16 的 356,988KB）；**保存后 PSS 明显下降（-24%）**；render 151–193ms；导出非空白、背景+元素在。

## 5) 回归 — PASS（import 未复测）
| item | 结果 |
|---|---|
| 栏目切换（tab） | PASS — 100ms vs 1s 截图 **diff=0.00**（即时，无动画） |
| 主页返回（3 次） | PASS — 均成功返回 Home |
| 缩略图 | PASS — Home 截图正常（r29_v58_home_thumbs.png） |
| 滑块/输出 tab | PASS — 可切到 10x（`10x · 3360 × 2080`），保存按倍率生效 |
| 荣耀卡面替换 | PASS — `applied face transit:… v=18` |
| 双击电源冷启动 | PASS — `SwipeCardActivity` |
| 2 次冷启动 | **PASS — 0 real ANR/FATAL** |
| 关于页/诊断 | PASS — 药做卡面/v0.2.18；`diagnostic_20261118-013929.txt` 30,004 B（不崩） |
| 大图导入 | **未复测**（本轮 picker 定位两次失败；v0.2.17 同类路径实测 1,330ms 预览通过，代码路径未变） |
| 清理 | PASS — config/exports cleared、stayon false、lspd 10/10 |

## 结论
**PASS（含 2 项未达标/未复测）**：
- ✅ 返回转场单层（稳定 screen key + transitionSpec 依赖 key；功能 3 次返回正常，无二次滑动；逐帧受设备录制限制未验证）
- ✅ 保存后 PSS 221,104 → ~168,450 KB（-24%）、render 151–193ms、导出非空白（std 76.33）
- ✅ 回归无异常（tab 即时、钱包替换、双击电源、2 次冷启动 0 ANR、诊断不崩、清理）
- ⚠ **预览拖动 jank 33% 未达 ≤10%**（p50 9ms 达标）→ 下轮继续（graphicsLayer/显示尺寸缓存）
- ⚠ **10x 保存峰值 PSS 未下降**（~322MB vs 315MB）→ 仅保存后下降；建议继续优化中间 Bitmap
- ⚠ 大图导入本轮未复测（自动化失败），建议下轮补测
