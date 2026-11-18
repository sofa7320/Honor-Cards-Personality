# ACCEPTANCE ROUND 33 — v62-final（0.2.22：文字离屏发光/描边 baseline 定位修复）

Artifact: `work\module\poc\card-personality-v62-final.apk`, 36,861,198 B, sha256 **053C33DAD001EF5AF5EBBC1B571C9CF701E7EC05E6FD34123D414D298ED995C6**（versionCode=1 / versionName=**0.2.22** / label 药做卡面；本地交付）。
Device: Honor 192.168.31.10:5555，LSPosed 2.2.1 / API 102。基线：v0.2.21（A564DE80…）。

## 1) 静态 — PASS
- aapt `versionCode=1/versionName=0.2.22/label 药做卡面`；API102-only（无 assets/xposed_init、10 dex、0 de.robv、META-INF 三件）
- dex（classes8/9）：**`renderTextOffscreen`(6)、`padX`(1)、`padY`(1)、`baseline`(1)、`offscreen`(1)、`measureKey`(2)**、glow(7)/stroke(9)；保留 round-32：`PlaceholderFace`(16)、retry、`accessPendingCapture`、`accessViews`、`findWalletCachedFace`、`original-ok`、`RECAPTURE_ORIGINAL`、`NavEntry`(9)、`HomeSnapshot`(6)、`Lanczos3`(7)、`CARD_PERSONALITY_PERF`、dedupe/scheduleRootCapture/OppoFaceServer/XiaomiFaceHookEngine/HookBridge/Xposed102Entry/FaceHookEngine/ANR guard

## 2) 文字对齐（关发光 vs 开发光/描边，不位移）— PASS
同一文字元素（GLOW、44dp、白色、居中），分别以「关发光关描边 / 开发光 r24 / 开发光+描边」导出 3x（1008×624），并抓预览截图：
- **预览白色文字 bbox 完全一致**：off / on24 / on24+stroke 均为 `(235,418)-(1097,618)`（截图坐标，区域 y≥380），dx0=0、dy0=0
- **导出白色文字 bbox 一致**：off `(328,258,682,354)`、on24 `(328,258,682,353)`、on24+stroke `(328,258,682,353)`；质心差 ≤0.9px（抗锯齿/光晕计入白阈值造成的亚像素抖动）
→ 开启发光/描边**不再导致文字上移/错位**（修复点：首行 baseline 改为 `padY - ascent`，padding 计入 bitmap）

## 3) 截断（radius 1/8/40、斜体、多行、小字号）— PASS
| 用例 | 导出白字 bbox | 结果 |
|---|---|---|
| r1 + stroke | (324,249,692,364) | 完整，无截断 |
| r8 + stroke | (328,258,682,353) | 与 r40 完全一致 |
| r40 + stroke | (328,258,682,353) | 光晕增大但文字 bbox 不变 → **未截断** |
| 斜体 + stroke + r24 | (335,258,685,353) | 斜体正常右倾，无截断 |
| 多行「GLOW\nTEST」+stroke | (344,190,667,423) | 两行完整、无裁切 |
| 小字号 20 + stroke | (424,288,584,330) | 完整 |
- r40 导出视觉复核（`r33_v62_r40.jpg`）：GLOW 蓝色描边+红色光晕完整、无任何边角裁切
- 每例均满足：文字 bbox 距画布四边 ≥150px（远未触及边缘）

## 4) 半径调节（1→48）即时且可重复 — PASS
UI 实测（元素选中 → 发光半径 SeekBar `[119,2303][1161,2401]`）：
- 点最左（r≈1）→ 点最右（r≈48）：预览区域 diff **6.93**（光晕半径可见变化）；中间位置（r≈24）与 r48 diff 2.74
- 重复开关：发光 开→关 diff **7.48**；第二次关 vs 第一次关 **0.00**；第二次开 vs 第一次开 **0.00** → **无滞后、无残留**
- 截图：r33_ui_r1 / r33_ui_r48 / r33_ui_rmid / r33_ui_glowoff / r33_ui_glowon1/2

## 5) 导出 vs 预览 位置一致性 — PASS（附检测方法说明）
- 以**描边（蓝色）mask**定位文字（避开背景照片和白色 UI 干扰）：预览描边 bbox 与导出描边 bbox 的**垂直相对位置**一致：各用例 rel_y 预览 0.491–0.497 vs 导出 0.490–0.496（差 ≤0.002 ≈ 1px）；水平 rel_x 预览 0.471±0.005 vs 导出 0.501±0.003（预览侧卡片左边界自动检测受深色树叶区域影响偏右，存在系统偏差；垂直不含该偏差）
- 导出像素可见性：描边蓝色像素 16.7k–17.8k（r1/r40）、发光红色像素随半径增加 26.7k→56.6k（宽松阈值，含夕阳暖色背景）→ 发光/描边在 3x 导出中可见

## 6) 回归 — PASS
| item | 结果 |
|---|---|
| 预览拖动（motionevent ~30Hz） | **PASS** — 2 次 39 帧、**jank 2（5.13%）**、p50 8–9ms、p90 11ms（v0.2.21 同法 5%，无回归） |
| 返回转场 2 次 | PASS — 均返回 Home；53 帧、jank 3（5.66%）、p90 12ms |
| 3x 保存 | PASS — `render|timing 323ms`、导出 **std=73.56 非空白** |
| 门禁卡占位/重试 | PASS（重试链路）— 冷态进入门禁卡编辑器时日志出现 `original capture invalidated access:F621A1F3`、oplog `bridge|refresh-original`；本轮原图在 3s 窗口内即到达（未捕获占位停留截图，属正常自动替换） |
| 荣耀卡面替换 | PASS — `applied face transit:… v=7` |
| 双击电源冷启动 | PASS — `SwipeCardActivity` |
| 2 次冷启动 | **PASS — 0 real ANR/FATAL** |
| 关于页/诊断 | PASS — 药做卡面 / v0.2.22；`diagnostic_20261118-182201.txt` 30,391 B（不崩） |
| 清理 | PASS — config/exports cleared、stayon false、lspd 10/10 |

## 结论
**PASS**：
- ✅ 开/关发光与描边**不改变文字位置**（预览 bbox 完全一致、导出 bbox 一致；修复 baseline/padding 生效）
- ✅ radius 1/8/40、斜体、多行、小字号**均不截断**；r40 导出视觉完整
- ✅ 半径滑块 1→48 即时可见变化，重复开关 diff 0（无滞后）
- ✅ 3x 导出与预览垂直位置一致（rel_y 差 ≤0.002），发光/描边像素可见
- ✅ 回归无异常：拖动 jank 5.13%、返回 p90 12ms、导出非空白、钱包替换/双击/2 次冷启动 0 ANR、诊断不崩
- ⚠ 方法限制：预览侧卡片边界自动检测受深色背景影响（水平 rel_x 有系统偏差），垂直方向不受影响；门禁占位本轮因原图到达过快未截到占位停留画面（重试链路日志已确认）
