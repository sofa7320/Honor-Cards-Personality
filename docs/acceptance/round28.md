# ACCEPTANCE ROUND 28 — v57-final（0.2.17：主页返回无闪烁 + tab 无动画 + 预览/10x 内存优化）

Artifact: `work\module\poc\card-personality-v57-final.apk`, 36,812,046 B, sha256 **7CA110A8ACBA05054C5266D1812A8B6FE518A809C4E7975C9FBF23E234DC8B19**（versionCode=1 / versionName=**0.2.17** / label 药做卡面；本地交付）。
Device: Honor 192.168.31.10:5555，LSPosed 2.2.1 / API 102。场景：4K 背景（`img_1794929704957_2a04de.jpg`）+ 1 元素贴纸。基线：v0.2.16（99E2D767…，同机同场景）。

## 1) 静态 — PASS
- aapt `versionCode=1/versionName=0.2.17/label 药做卡面`；API102-only（无 xposed_init、10 dex、0 de.robv、META-INF 三件）
- dex：`HomeSnapshot`(4)、`initialSnapshot`(2)、`onSnapshot`(3)、`listState`(3)、`decodeCap`、`3072`、`Lanczos3`(7)、`ScaledFaceCache`(9)、`CARD_PERSONALITY_PERF`、dedupe/scheduleRootCapture/OppoFaceServer/XiaomiFaceHookEngine/HookBridge/Xposed102Entry/FaceHookEngine/ANR guard
- 源码确认：`AppRoot.kt` 把 `homeSnapshot`/`homeListState` 传入 HomeScreen（`initialSnapshot`/`onSnapshot`/`listState`）；`EditorScreen.kt:265` 注释「Per-tab scroll state only (no transition animation — tabs switch instantly)」

## 2) 主页返回无闪烁 — PASS（v0.2.17）
方法：Home 滚动到中部（岭南通·羊城通 row y=534）→ 点卡进编辑器（已确认 `卡面预览` 出现）→ 点 X 返回 → 截图（150ms / 1s）与返回前对比 + dump 位置：
- **滚动位置保持**：row 返回前 [476,**534**] → 返回后 [476,**534**]（完全一致）
- **无整页重建/空白/loading**：返回前 vs 150ms diff=0.11、vs 1s diff=0.11；std 42.6 vs 42.7（无白屏/骨架帧）
- 静态：`HomeSnapshot(cards, reportUpdatedAt, storeVersion)` + `initialSnapshot`/`onSnapshot` + `listState` 复用（返回不重载）
- 证据：r28_v57_home_before.png / r28_v57_ret150.png / r28_v57_ret1000.png，ui_r28_v57_n1~n3.xml
- 说明：v0.2.16 基线的同法自动化多次因行定位失败未取得可靠数值（不纳入对比），v0.2.17 结果以自身证据为准

## 3) 栏目切换无动画 — PASS
- 静态：源码无 tab 切换动画（仅页面级 `AnimatedContent` 导航动画；tab 内容无 AnimatedContent/Crossfade 包裹）
- 设备：点“元素”后 100ms 截图 vs 1s 截图 diff=0.12（无持续过渡/淡入帧；差异为水波纹/滚动条），切换即时
- 证据：r28_v57_tab_100.png / r28_v57_tab_1s.png

## 4) 预览拖动性能 — 未达标（数据记录，供下轮）
同 v0.2.16 方法（设备内连续 swipe 循环 ≈4.2s）：
| 指标 | v0.2.16 基线 | **v0.2.17** | 目标 |
|---|---|---|---|
| frames | 108 | 108 | — |
| Janky | 36（33.33%） | **36–37（33.33–34.26%）** | ≤10% ❌ |
| p50 | 9–10 ms | **10 ms** | ≤16ms ✅ |
| p90 / p95 / p99 | 24 / 25 / 27–28 ms | **25 / 27 / 28–29 ms** | — |
→ **p50 达标，jank 仍 ~33%**，与 v0.2.16 基本持平（本版未针对预览拖动作进一步优化，符合 lead 预期）；建议 ui-dev 下轮 graphicsLayer/显示尺寸缓存。

## 5) 10x 保存 PSS 与耗时 — 改善（PASS）
| 指标 | v0.2.16 基线 | **v0.2.17** | 变化 |
|---|---|---|---|
| 保存前 PSS | 176,137–172,845 KB | 171,961 KB | ≈持平 |
| **保存峰值 PSS** | **356,988 KB（≈349MB）** | **315,072 KB（≈308MB）** | **-41,916 KB（-11.7%）** |
| 保存后 PSS | 276,358 KB | 221,104 KB | **-55,254 KB（-20%）** |
| render\|timing | 2,205 ms | **197 ms** | **-91%**（命中缓存/新路径） |
| jpeg | 975,544 B / 44ms | **1,238,074 B / 48ms** | 画质/内容不同（4K 背景重导入后） |
| 导出校验 | 3360×2080 | **3360×2080，std=76.44（非空白）** | ✅ |
- 4x 保存：render **54ms**、jpeg 97,546B/6ms、导出 **1344×832 std=63.62 非空白**、元素+背景均在

## 6) 回归 — PASS
| item | 结果 |
|---|---|
| 大图导入 | PASS — PhotoPicker 选图后 **1,330ms 预览显示**（`已导入图片`，`导出 3360×2080（10x）`） |
| 滑块/输出 tab | PASS — 输出 tab 正常显示「10x · 3360 × 2080」/预计尺寸/重置，无灰点 |
| 缩略图 | PASS — Home 卡片缩略图正常（r28_v57_home_thumbs.png），v0.2.17 |
| 荣耀卡面替换 | PASS — `applied face transit:… v=18 to 1 view(s)`（r28_v57_wallet.png） |
| 双击电源冷启动 | PASS — `SwipeCardActivity` |
| 2 次冷启动 | **PASS — 0 real ANR/FATAL** |
| 关于页/诊断 | PASS — 药做卡面 / v0.2.17；`diagnostic_20261118-011602.txt` 28,731 B（不崩） |
| 清理 | PASS — config/exports cleared、stayon false、lspd 10/10 |

## 结论
**PASS（含 1 项未达标数据）**：
- ✅ 主页返回：滚动位置保持、无整页重建/空白帧；tab 切换无动画；10x 保存 PSS 峰值 **-11.7%**、保存后 PSS **-20%**、render 2205→197ms、导出非空白；回归全 PASS、0 ANR。
- ⚠ **预览拖动 jank 33–34% 仍 >10% 目标**（p50 10ms 已达标），与 v0.2.16 持平 → 记录数据，建议 ui-dev 下轮 graphicsLayer/显示尺寸缓存。
- 说明：v0.2.16 的主页返回基线未取得同法可靠数据（自动化行定位失败），已如实标注。
