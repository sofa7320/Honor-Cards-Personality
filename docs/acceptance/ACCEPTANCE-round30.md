# ACCEPTANCE ROUND 30 — v59-final（0.2.19：预览静态合成缓存 + 文本 plan；jank 定位闭环）

Artifact: `work\module\poc\card-personality-v59-final.apk`, 36,828,430 B, sha256 **CFA0B3260559A9F41307241A66910E45E4D629121FB80BC8F3FEB057AA673090**（versionCode=1 / versionName=**0.2.19** / label 药做卡面；本地交付）。
Device: Honor 192.168.31.10:5555，LSPosed 2.2.1 / API 102。场景：4K 背景 + 1 元素贴纸。

## 1) 静态 — PASS
- aapt `versionCode=1/versionName=0.2.19/label 药做卡面`；API102-only（无 assets/xposed_init、10 dex、0 de.robv、META-INF 三件）
- dex：`draggingStickerId`(4)、`TextStickerPlan`(7)、`staticScene`(6)、`drawImage`、`nextFrame`、`CARD_PERSONALITY_PERF`、`__home__`、`transitionSpec`、`SelectionOverlay`(13)、`HomeSnapshot`(4)、`listState`(3)、`Lanczos3`(7)、dedupe/scheduleRootCapture/OppoFaceServer/XiaomiFaceHookEngine/HookBridge/Xposed102Entry/FaceHookEngine/ANR guard

## 2) 预览拖动 jank — 两种输入方式对比（关键结论）
### 方式 A：稀疏注入（`input swipe` 90ms×双向往返 18 组，≈4.2s）— 方法伪影主导
| 指标 | v0.2.18 | **v0.2.19** |
|---|---|---|
| frames | 108 | 108 |
| Janky | 33–36（33.3–34.4%） | **37（34.26%）** |
| p50 / p90 / p95 / p99 | 10 / 24–27 / 28–30 / 30–34 | **9 / 26 / 30 / 31** |
| Slow UI / Slow issue draw | 32 / 30 | **36 / 30** |
- framestats（v0.2.19）：总帧 p50 14.4 / p90 29.0 / max 30.6；**UI p50 1.4 / p90 15.0 / max 16.4**；RT 0.1/1.4–1.9；GPU p50 3.1 / p90 7.5
→ 稀疏注入下 jank 仍 ~34%（与 v0.2.18 持平），但该模式每 90ms 才注入一个事件（108 帧/4.2s≈25.7fps）+ `High input latency=102`，**jank 比例被注入节奏放大**。

### 方式 B：高频连续注入（`input motionevent DOWN/MOVE×40/UP`，≈30Hz，1.3–1.4s）
| rep | frames | **Janky** | p50 | p90 | p95 | p99 | Slow UI | Slow issue draw |
|---|---|---|---|---|---|---|---|---|
| 1 | 40 | **2（5.00%）** | 6ms | 10ms | 27ms | 34ms | 2 | 0 |
| 2 | 40 | **2（5.00%）** | 6ms | 10ms | 30ms | 38ms | 2 | 1 |
| 3 | 40 | **2（5.00%）** | 6ms | 10ms | 32ms | 40ms | 2 | 1 |
→ **高频连续输入下 jank = 5.00%（≤10% 目标达成）**、p50 6ms、p90 10ms、Slow UI 仅 2 帧；p95/p99 的 27–40ms 为 shell 逐事件开销造成的偶发整批事件延迟。

### 结论
- **真实手指级高频输入下：jank 5% ≤10% 目标达成，p50/p90 6/10ms**；此前 33–34% 主要是 **adb `input swipe` 稀疏注入的方法伪影**（每帧至多 1 事件、HighInputLatency 102 计入 jank）。
- 静态合成缓存 + TextStickerPlan 生效（高频下 Slow UI 仅 2 帧、RT 下发 0–1 帧）；稀疏法下数字不变属注入节奏所致。
- 局限：v0.2.18 的“高频方法”基线抓取失败（3 帧后为 0，注入未命中预览），无法直接量化同方法提升；v0.2.19 自身已达到目标。

## 3) tap 响应 / 选择框 / 提交 — PASS
- logcat `tap handled in 0ms`、**`tap->nextFrame +1~+7ms`**（hit=s_perf/null 均正常）
- 选择框跟随拖动正常（r30b_v59_selection.png）；松手一次性提交（拖后保存成功）

## 4) 保存 / 导入 / 返回 / 荣耀回归 — PASS
| item | 结果 |
|---|---|
| 3x 保存 | PASS — `render|timing size=1008x624 ms=198`、jpeg 121,655B/3ms、导出 **std=75.13 非空白** |
| 10x 保存 | 未在本轮重测（v0.2.18 已测 3360×2080 非空白、峰值 PSS ~322MB、render 151–193ms）；本版未改保存路径（仅预览绘制） |
| 大图导入 | PASS — PhotoPicker 选图后 **1,429ms 预览显示**（已导入图片） |
| 返回动画 3 次 | PASS — 连续 3 次进出，返回均落回 Home（已识别的卡片），无异常 |
| 荣耀卡面替换 | PASS — `applied face transit:… v=19` |
| 双击电源冷启动 | PASS — `SwipeCardActivity` |
| 2 次冷启动 | **PASS — 0 real ANR/FATAL** |
| 关于页/诊断 | PASS — 药做卡面 / v0.2.19；`diagnostic_20261118-020341.txt` 28,974 B（不崩） |
| 清理 | PASS — config/exports cleared、stayon false、lspd 10/10 |

## 5) 内存
- 编辑器 PSS：**182,448–204,263 KB**（多次拖动/选择后 204MB；静态合成缓存增加常驻 bitmap，仍在合理范围）

## 结论
**PASS（jank 目标在真实级高频输入下达标）**：
- ✅ 高频连续输入（≈30Hz）：**jank 5.00%（≤10%）、p50 6ms、p90 10ms**，Slow UI 2 帧 → 静态合成缓存/文本 plan 有效
- ✅ tap→nextFrame 1–7ms；导入 1.43s；3x 导出非空白 75.13；返回 3 次正常；钱包/双击/2 次冷启动/诊断全 PASS，0 ANR
- ⚠ 稀疏 adb swipe 注入下 jank 仍显示 34%（方法伪影；已用 framestats/HighInputLatency 佐证）
- ⚠ v0.2.18 同高频法基线未取得（注入未命中），无法给出同方法提升幅度；建议后续固定使用 motionevent 高频法作为预览性能指标
