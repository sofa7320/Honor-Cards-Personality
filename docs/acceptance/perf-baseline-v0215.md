# PERF BASELINE — v0.2.15（sha 29550391794950743B0057639A1BE596EE6782EF378AD48D4795878B25680785）

采集时间：2026-11-17 23:5x–2026-11-18 00:0x
设备：Honor 192.168.31.10:5555（PTP-AN10，Android 17/API 37），LSPosed 2.2.1 / API 102
测试场景：交通卡（岭南通·羊城通）自定义卡面 = **4K 大图背景**（`img_1794929704957_2a04de.jpg`，3,245,862 B）+ **1 张元素贴纸**（`stk_1794421022244_e1845b.png`）
说明：单机单轮采样，数值受设备温度/后台影响，建议与 v0.2.16 同条件对比看趋势。

## 1) 冷启动（`am force-stop` → `am start -W`，2 次）
| # | LaunchState | TotalTime | WaitTime |
|---|---|---|---|
| 1 | COLD | **534 ms** | 539 ms |
| 2 | COLD | **504 ms** | 507 ms |

## 2) 首页滚动 jank（`dumpsys gfxinfo reset` → 5s 上下滑动 → gfxinfo）
- Total frames rendered: **578**
- Janky frames: **9（1.56%）**；legacy 78（13.49%）
- 50th **6ms** / 90th **9ms** / 95th **11ms** / 99th **18ms**
- GPU 直方图：2ms=563（绝大多数）

## 3) 编辑器
- 打开耗时（Home 点卡 → 轮询 uiautomator 出现「卡面预览」，**含 dump 开销**）：**≈2,540 ms**
- 编辑器 PSS：**224,902 KB（≈220 MB）**
- 预览拖动/滑动 3s（水平来回 swipe，gfxinfo）：**16 frames / 3s**，**Janky 8（50.00%）**，50th **32ms** / 90th **38ms** / 95th **40ms** / 99th **40ms** → 严重卡顿

## 4) 保存（logcat `CARD_PERSONALITY_PERF: render|timing` + 墙钟）
| 倍率 | render|timing | jpeg | 墙钟 |
|---|---|---|---|
| 3x (1008×624) rep1 | **1,649 ms**（bg=true drewBg=true stickers=1） | 104,609 B / 4 ms | 2.14 s |
| 3x rep2 | **489 ms** | 104,609 B / 4 ms | 0.74 s |
| 10x (3360×2080) | **❌ render failed**（`FaceRenderer.render` 抛错，仅 E 日志；4K 背景 + 10x 无法完成保存） | — | — |

## 5) 内存（`dumpsys meminfo` TOTAL PSS）
| 状态 | TOTAL PSS |
|---|---|
| 首页 | **168,507 KB** |
| 编辑器（4K bg + 元素） | **224,902 KB** |
| 10x 失败尝试后 | 259,244–277,252 KB |

## 结论（v0.2.15 基线）
- 首页流畅（1.56% jank），冷启动 ~500–540ms，内存首页 ~165MB / 编辑器 ~220MB。
- **编辑器预览拖动严重卡顿（50% jank，p50 32ms）**；**10x（4K 背景）保存直接失败**；3x 保存 0.5–1.6s。
