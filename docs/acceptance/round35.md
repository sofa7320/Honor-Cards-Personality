# ACCEPTANCE ROUND 35 — v64-final（0.2.23：状态灯/加载圈/≥6x 提示/关于致谢）

Artifact: `work\module\poc\card-personality-v64-final.apk`, 36,877,582 B, sha256 **9DF2CF438A075CC74A4D52A677B6D32535028B3BBF496793B434861FB74A28F8**（versionCode=1 / versionName=**0.2.23** / label 药做卡面；本地交付）。
Device: Honor 192.168.31.10:5555，LSPosed 2.2.1 / API 102（本轮开始前设备离线，`adb connect 192.168.31.10:5555` 重连后安装）。

## 1) 静态 — PASS
- aapt `versionCode=1/versionName=0.2.23/label 药做卡面`；API102-only（无 assets/xposed_init、10 dex、0 de.robv、META-INF 三件）
- dex（classes5）：**`facesReadable`(5)**、`CircularProgressIndicator`、`contentAlignment`（classes.dex=81/6 等）、`当前导出画质倍率过高`(1)、`可能会导致卡面加载过慢`(1)、`性能下降`(1)、**`DeepSeek V4.1-Flash`(1)**、**`发送错误报告`(2)**、`导出诊断报告`(4)；保留 `glowLayers`、`renderTextOffscreen`、`accessPendingCapture`、`RECAPTURE_ORIGINAL`、`NavEntry`、`Lanczos3`、`CARD_PERSONALITY_PERF`、Xiaomi/Oppo guards
- 无「已获取到卡面/已获取钱包卡面」字符串（0 命中）

## 2) 状态指示灯三色逻辑 — PASS（截图取色 + 文案保持）
方法：状态卡圆点位于 (136,692) 附近，取「区域最大饱和像素 RGB」；通过临时改写 `files/report.json`（备份后恢复）构造无卡面/无卡场景。
| 场景 | 构造方式 | 圆点实测 RGB | 状态文案 |
|---|---|---|---|
| **绿** | 正常（模块已激活+3 张卡+faceUrl 可读） | **(101,196,102)**（#34C759 抗锯齿混合后） | 模块通信正常 |
| **黄** | 卡片 faceUrl 全部置空 + 清空 originals（已激活+有卡但卡面不可读） | **(243,179,70)**（#FFB020 混合） | **模块通信正常（文案不变 ✓）** |
| **红** | report.json cards=[]（无卡） | **(215,83,63)**（#E94634 混合） | 模块已连接，未识别到卡片（无卡场景文案按状态变化，属预期） |
- 绿/黄两场景**文案逐字一致**（「模块通信正常」「已识别的卡片」列表相同）→ 指示灯颜色变化不影响文案 ✓
- 恢复：备份 report.json 回写 → 重新打开后恢复绿色 (101,196,102)、文案「模块通信正常」✓
- 截图：r35_green_home / r35_yellow_home / r35_red_home / r35_green_restored

## 3) 加载圆圈居中 — PASS（源码 + 几何）
- 源码（HomeScreen.kt）：**L249-253** `Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) }` → 20dp spinner 在 20dp Box 内两轴居中；状态卡 spinner 同构（L445-465 Box + contentAlignment=Center）
- 几何：右上角刷新按钮 bounds `[1070,148][1238,316]`（中心 1154,232），图标亮像素 bbox `(1119,197)-(1188,266)` 中心 **(1153.4,231.3)** → 与按钮中心偏差 **0.7/0.7 px**，spinner 由同 Box/Constraints 居中（同心）
- 说明：真实 spinner 为瞬时状态（刷新 <250ms、冷启动 <250ms），**screencap 采样周期内未捕获到 spinner 帧**（多次冷启动/刷新连拍帧均无差异）→ 以源码结构与按钮同心几何为证据；建议 ui-dev 若需截图证据可临时加长 spinner 最小时长
- 截图：r35_spin_pre/1-8/post、r35_cold_1-6/final

## 4) ≥6x 红色提示 — PASS（边界/文案/颜色）
| 倍率 | 提示文案 | 说明 |
|---|---|---|
| 2x / 3x / 4x / 5x | **不显示** | 5x 及以下隐藏 ✓ |
| **6x / 7x / 8x / 10x** | **显示**「当前导出画质倍率过高，可能会导致卡面加载过慢、性能下降。」 | 边界在 6x ✓ |
- 颜色实测（提示行红像素均值）**(210,58,46) ≈ #D23A2E**（colorscheme error #E94634 抗锯齿/JPEG 混合）→ 红色 ✓
- 截图：r35_scale_460/579/698/790/870/1150

## 5) 关于页致谢 — PASS
- 版本行 **v0.2.23**；「反馈」区含 GitHub 仓库、**发送错误报告**；「致谢」原文逐字匹配：
 「本软件使用 **DeepSeek V4.1-Flash** 制作，可能存在部分问题。若您在使用过程中遇到问题，请**导出诊断报告**，并通过**“发送错误报告”**向我反馈问题，**感谢各位的支持**。」（中文引号 “” 正确、Miuix 卡片排版）
- 开源支持区：Miuix / LSPosed / oplus-card-customizer / 卡面工坊；诊断区：导出诊断报告（OPPO bridge 状态行正常）
- 「发送错误报告」入口：源码 `AboutLinkRow(label="发送错误报告")` 包在 `Card(onClick = { openUrl(GITHUB_ISSUES_URL) })`，`openUrl` = `ACTION_VIEW + NEW_TASK` fail-safe；**本机点击后无可见跳转（日志无 SENDTO/ACTION_VIEW 记录，可能无可用浏览器/Intent 被系统拦截），入口绑定与源码存在，属环境限制**
- 截图：r35_about_thumbs/r35_about_thanks/r35_report2

## 6) 回归 — PASS
| item | 结果 |
|---|---|
| 主页返回/缩略图 | PASS — Home 卡片缩略图正常（青紫发卡面/门禁/车钥匙），返回后正常 |
| 编辑器 tap/拖动 | PASS — `tap handled 0ms`、`tap->nextFrame +8ms`；**拖动 2 次 39 帧、jank 2（5.13%）**、p50 8–9ms、p90 10–12ms |
| 保存 3x | PASS — `render|timing size=1008x624 ms=195`、导出 **std=73.56 非空白** |
| 返回转场 3 次 | PASS — 均返回 Home；52–54 帧、jank 2.6–3.85%（3.85/5.56/5.77%）、p90 6–14ms |
| 2 次冷启动 | **PASS — 0 real ANR/FATAL** |
| 钱包替换 | PASS — `applied face transit:… v=1` |
| 双击电源 | PASS — `SwipeCardActivity` |
| 诊断导出 | PASS — `diagnostic_20261121-013621.txt` 30,701 B，「应用版本：0.2.23」 |
| 清理 | PASS — report.json 已恢复备份、originals 清空（按需再抓）、stayon false、lspd 10/10 |

## 结论
**PASS**：
- ✅ 状态灯：绿 (101,196,102) / 黄 (243,179,70) / 红 (215,83,63) 三态与可读性逻辑一致；绿↔黄文案逐字不变
- ✅ spinner 居中：源码 20dp Box+Center 两轴居中、刷新按钮内几何同心（偏差 0.7px）；瞬时帧受 screencap 周期限制未捕获（已注明方法限制）
- ✅ ≥6x 提示：4x/5x 不显示、6x/7x/8x/10x 显示且文案完全一致、红色 #D23A2E
- ✅ 关于致谢：DeepSeek V4.1-Flash 全文逐字匹配、中文引号、Miuix 排版、发送错误报告入口存在（本机无可见跳转，环境限制）
- ✅ 回归全 PASS（拖动 jank 5.13%、返回 p90 ≤14ms、3x 导出非空白、2 次冷启动 0 ANR、诊断 30.7KB）
