# PERF PREVIEW — ROUND 30（gfxinfo framestats / atrace 定位预览拖动 jank）

设备：Honor 192.168.31.10:5555（Android 17/API 37，120Hz），v0.2.18（sha 027E889F…，debug 版）
场景：编辑器预览，**4K 背景（3.2MB jpg）+ 1 元素贴纸**，10x 画质设置；拖动注入：设备内连续 `input swipe` 双向往返（90ms/次）≈4.2s。

## 1) gfxinfo 总体（同轮拖动，两次采样）
| 采样 | frames | Janky | p50 | p90 | p95 | p99 | Slow UI | Slow issue draw | Missed Vsync | High input latency |
|---|---|---|---|---|---|---|---|---|---|---|
| A | 96 | 33（34.38%） | 10ms | 27ms | 29ms | 34ms | **32** | **30** | 0 | **102** |
| B | 108 | 36（33.33%） | 9–10ms | 24–25ms | 28–30ms | 30–34ms | — | — | — | — |
GPU 统计：50th 6ms / 90th 8ms / 99th 9ms → **GPU 不是瓶颈**。

## 2) framestats 逐帧阶段（PROFILEDATA，10 个有效帧，单位 ms，ns→ms）
| 阶段 | p50 | p90 | p99 | max |
|---|---|---|---|---|
| 总帧（IntendedVsync→FrameCompleted） | 9.8–11.0 | 19.9–24.2 | 23.5 | 34.6 |
| **UI（HandleInputStart→DrawStart）** | 1.6–1.8 | **9.7–15.0** | 12.5 | **20.1** |
| RenderThread 同步等待（SyncQueued→SyncStart） | 0.1 | 0.4 | 1.7 | 1.7 |
| RenderThread 下发（IssueDrawCommandsStart→SwapBuffers） | 1.4 | 1.9–2.0 | 2.0 | 2.2 |
| GPU（SwapBuffers→GpuCompleted） | 3.6–5.7 | 6.9–7.2 | 6.9 | 8.8 |
| 绘制→交换（DrawStart→SwapBuffers） | 2.5–2.6 | 3.6–3.8 | 3.7 | 5.6 |

## 3) atrace（`atrace -t 4 gfx view sched`，64.7MB）
- 文件：`work\device\logs\r30_atrace_drag.txt`（已拉取）
- 解析结果：trace 中 **未捕获到应用（com.cardpersonality.honor）的 gfx slice**（仅 SystemUI 的 DrawFrame/Choreographer 等 59 条 + 其它 1 条）——gfx 应用切片缺失（可能 atrace 类别/进程过滤限制）；sched 数据 31 万条可用但需 Perfetto/trace_processor 分析，本机无该工具
- 结论：atrace 本机可跑通但**应用 gfx slice 未落盘**，深度阶段归因需 ui-dev 在本地 Perfetto UI 分析（建议下一步在开发机抓 `perfetto` 全量 trace 或用 `--app` 过滤 + `--categories gfx,view`）

## 4) 定位结论（综合）
1. **UI 线程是主要 jank 源**：framestats UI 段 p90 9.7–15ms（超 16.7ms 预算的帧会掉），gfxinfo `Number Slow UI thread=32`；对应编辑器仍存在每帧 UI 工作（预览绘制/选择框/文字测量/重组等）。
2. **RenderThread 下发命令也偏慢**：`Slow issue draw commands=30`，framestats 下发 1.4–2.0ms 不算高，但整体 30 帧超阈值 → 可能受 UI 帧起始晚（输入延迟）影响连锁。
3. **GPU 不是瓶颈**（5.7–7.2ms，90% 帧 <8ms）。
4. **输入注入延迟被计入 jank**：`High input latency=102`（>frames 数），adb `input swipe` 注入的事件率低（每 90ms 一次、每帧最多 1 事件），帧产生本身受注入节奏限制（108 帧/4.2s ≈ 25.7fps）；**真实手指高频触摸下 jank 比例可能低于测量值**，需以真实触控或更高频注入复核。
5. 帧率受输入速率限制 → jank 33% 中相当部分是“帧本身稀疏 + UI 超时”，不是 GPU 渲染慢。

## 5) 建议（供 ui-dev 决策）
- **预览用 graphicsLayer 缓存**：把“背景+元素”合成结果缓存为一张预览 bitmap（1280/1024 已有纹理上限），拖动时只做 translate/scale 变换（`graphicsLayer.translationX/Y`、`scaleX/Y`），不每帧重绘大图/重算文字。
- **显示尺寸缓存**：按预览显示尺寸预缩背景（而非每帧从 4K 源缩放），配合 `ScaledFaceCache` 复用。
- 拖动期间**跳过选择框/关键点文字测量**（仅在松手/选中态显示）。
- 真机复核：用真实触控或 `adb shell input touchscreen swipe` 高频事件（如 10ms/步）重测 jank，区分注入伪影。

## 6) 数据文件
- `work\device\logs\r30_framestats.txt`（gfxinfo framestats + stats）
- `work\device\logs\r30_fs1/2/3.txt`（延长时间采样）
- `work\device\logs\r30_atrace_drag.txt`（64.7MB atrace gfx+sched，应用 gfx slice 缺失）
- 解析脚本：`work\device\logs\r30_parse.py`、`r30_parse2.py`、`r30_atrace_parse3.py`

---

## 7) 追加：v0.2.19 高频 motionevent 对照（2026-11-18 02:0x）
方法：`input motionevent DOWN 900 700` → 40×`MOVE`（每步 15px，设备端 shell 循环 ≈30Hz）→ `UP`，1.3–1.4s；4K 背景+元素。
| rep | frames | Janky | p50 | p90 | p95 | p99 | Slow UI | Slow issue draw |
|---|---|---|---|---|---|---|---|---|
| 1 | 40 | **2（5.00%）** | 6ms | 10ms | 27ms | 34ms | 2 | 0 |
| 2 | 40 | **2（5.00%）** | 6ms | 10ms | 30ms | 38ms | 2 | 1 |
| 3 | 40 | **2（5.00%）** | 6ms | 10ms | 32ms | 40ms | 2 | 1 |
→ **高频输入下 jank 5%（≤10% 目标达成）**，p50/p90 6/10ms；对比稀疏 `input swipe` 法 34%（108 帧/4.2s、HighInputLatency 102）→ **此前的 33–34% jank 主要是注入稀疏造成的测量伪影**，不是 GPU/渲染本身。
v0.2.19 framestats（稀疏法）：总帧 p50 14.4/p90 29.0；UI p50 1.4/p90 15.0/max 16.4；GPU p50 3.1/p90 7.5。
结论：预览性能已达目标；后续验收固定采用 motionevent 高频法（≈30Hz）+ framestats 作为指标。
