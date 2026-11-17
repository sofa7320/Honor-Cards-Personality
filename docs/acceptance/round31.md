# ACCEPTANCE ROUND 31 — v60-final（0.2.20：NavEntry/registry 返回转场修复）

Artifact: `work\module\poc\card-personality-v60-final.apk`, 36,828,430 B, sha256 **52FE974F9422F8133D868E460F94B39CC6CE667646C40D3C73AE81AD7033E4BF**（versionCode=1 / versionName=**0.2.20** / label 药做卡面；本地交付）。
Device: Honor 192.168.31.10:5555，LSPosed 2.2.1 / API 102。基线：v0.2.19（CFA0B326…）。

## 1) 静态 — PASS
- aapt `versionCode=1/versionName=0.2.20/label 药做卡面`；API102-only（无 assets/xposed_init、10 dex、0 de.robv、META-INF 三件）
- dex：**`NavEntry`(9)、`transitionActive`(3)、`allowAutoRefresh`(3)**、`transitionSpec`、`__home__`、`HomeSnapshot`(6)、`listState`(3)、`draggingStickerId`(4)、`TextStickerPlan`(7)、`staticScene`(6)、`Lanczos3`(7)、`CARD_PERSONALITY_PERF`、dedupe/scheduleRootCapture/OppoFaceServer/XiaomiFaceHookEngine/HookBridge/Xposed102Entry/FaceHookEngine/ANR guard

## 2) 返回转场：outgoing 层确认为编辑页、单层滑动 — PASS（截帧证据）
设备录制限制：`screenrecord` 本机输出为静态帧；`screencap` 后台连拍会阻塞输入 → 改用 **host 侧交错 `adb exec-out screencap -p` + 帧间注入 X 点击**（≈250ms/帧），共 3 次转场：
- 帧序列分类（E=编辑页、T=转场中间帧、H=首页）：**`E E T H H H H`（连续 3 次一致）**
- **中间帧 `r31_seq/s_3.png` 内容为编辑页**（岭南通·羊城通标题、保存按钮、卡面预览、图片/元素/组件/输出 tabs）→ **outgoing 层是编辑页，不是首页**
- 转场后连续 4+ 帧全为 Home（dHome≈0.1，无任何二次滑动/闪烁帧）
- 证据：`work\device\shots\r31_seq\s_3.png`（中间帧）、`r31_seq1\`、`r31_seq2\`（重复验证序列）、`r31_editor_baseline.png`、`r31_home_baseline.png`

## 3) 返回瞬间卡顿 — **PASS（明显改善）**
gfxinfo 窗口：X 点击前 reset → 点击 → 1.5s 后统计（2 次采样）：
| 指标 | **v0.2.19** | **v0.2.20** |
|---|---|---|
| frames | 34–35 | 51 |
| Janky | 3（8.57–8.82%） | **3（5.88%）** |
| p50 | 5 ms | 6 ms |
| **p90** | **44–46 ms** | **8 ms** |
| **p95** | **133–150 ms** | **31 ms** |
| **p99** | **150 ms** | **109 ms** |
| Slow UI thread | 3 | 3 |
| Slow issue draw | 0–1 | 0（单次采样） |
→ 返回窗口 p90 **46→8ms（-83%）**、p95 **150→31ms（-79%）**、jank 比例 8.8%→5.9%，**返回瞬间卡顿明显改善**；仅 3 个 Slow UI 帧（转场本身）。

## 4) 预览拖动（回归，motionevent 高频法）— PASS（与 v0.2.19 持平）
注入脚本：设备端 `sh /data/local/tmp/drag.sh 900 700 40 -15`（DOWN + 40×MOVE ≈30Hz + UP）：
| rep | frames | Janky | p50 | p90 | Slow UI | Slow issue draw |
|---|---|---|---|---|---|---|
| 1 | 41 | **2（4.88%）** | 8ms | 11ms | 2 | 0 |
| 2 | 40 | **2（5.00%）** | 7ms | 10ms | 2 | 1 |
| 3 | 40 | **2（5.00%）** | 6ms | 10ms | 2 | 0 |
→ **jank 5%，无回归**（v0.2.19 同方法 5%/p50 6/p90 10）；编辑器 PSS 203,050 KB（与 v0.2.19 182–204MB 同区间）。

## 5) 回归 — PASS
| item | 结果 |
|---|---|
| 进入/返回 3 次 | PASS — 3 次序列均 EET→H…，返回后 Home 正常 |
| 3x 保存 | PASS — `render|timing size=1008x624 ms=192`、jpeg 106,552B/3ms、导出 **std=73.56 非空白** |
| 大图导入 | PASS — PhotoPicker 选图后 **1,297ms 预览即显示**（已导入图片） |
| 缩略图 | PASS（Home 截图正常，见 r31_home_baseline.png） |
| tab 切换 | PASS（前轮已验证即时；本版未改 tab 逻辑） |
| 荣耀卡面替换 | PASS — `applied face transit:… v=19` |
| 双击电源冷启动 | PASS — `SwipeCardActivity` |
| 2 次冷启动 | **PASS — 0 real ANR/FATAL** |
| 关于页/诊断 | PASS — 药做卡面 / v0.2.20；`diagnostic_20261118-022830.txt` 29,064 B（不崩） |
| 清理 | PASS — config/exports cleared、stayon false、lspd 10/10；已删除 /data/local/tmp/drag.sh、/sdcard/_raw |

## 结论
**PASS**：
- ✅ **outgoing 层确认为编辑页**（中间帧内容=编辑页），3 次转场序列 `EET→H` 单次、无二次滑动/闪烁
- ✅ **返回卡顿明显改善**：p90 46→**8ms**、p95 150→**31ms**、jank 8.8%→**5.9%**
- ✅ 预览拖动 jank **5%**（无回归）、导入 1.30s、3x 导出非空白、钱包/双击电源/2 次冷启动 0 ANR、诊断不崩
- ⚠ 限制：本机 `screenrecord` 静态帧、`screencap` 后台连拍阻塞输入 → 用交错截帧（≈250ms）仅能获得 1 个中间帧/次，无法逐帧统计动画层数；但中间帧内容与“单次 E→T→H”序列支持单层结论
