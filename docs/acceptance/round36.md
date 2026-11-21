# ACCEPTANCE ROUND 36 — v65-final（0.2.24：加载圈尺寸/位置 + 关于致谢去重）

Artifact: `work\module\poc\card-personality-v65-final.apk`, 36,877,582 B, sha256 **6A326B094A944583AD34AD93E8DF09C8BBE1BACDB4C87745F6F72DA04FF0AE77**（versionCode=1 / versionName=**0.2.24** / label 药做卡面；本地交付）。
Device: Honor 192.168.31.10:5555，LSPosed 2.2.1 / API 102；密度 560dpi（density=**3.5**，1dp=3.5px），分辨率 1280×2800。

## 1) 静态 — PASS
- aapt `versionCode=1/versionName=0.2.24/label 药做卡面`；API102-only（无 assets/xposed_init、10 dex、0 de.robv）
- 源码三处 spinner：
 - `AboutScreen.kt` L347 `CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)`（导出诊断按钮）
 - `HomeScreen.kt` L253 `CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)`（右上角刷新，Box contentAlignment=Center）
 - `HomeScreen.kt` L467 `CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)`（状态卡检测行）
- 致谢：`HomeScreen`/`AboutScreen` 源码 `SmallTitle("致谢")` **仅 1 处**（AboutScreen L360），DeepSeek 文案位于该 Card 内（L380-381）
- dex：`strokeWidth`、`CircularProgressIndicator`、`facesReadable`(5)、`glowLayers`(2)、`致谢`(2，其中 1 处为「开源致谢」子串)、`DeepSeek V4.1-Flash`(1)、`发送错误报告`(2，行 + 正文)、`导出诊断报告`(4)、`当前导出画质倍率过高`(1)

## 2) 加载圆圈尺寸/位置 — PASS（About 实测 A/B 对比 v0.2.23）
方法：About 页点「导出诊断报告」，**设备端连续 screencap 10 帧**（每帧 ~150ms）捕获加载态；对蓝色+白色圆弧像素取多帧并集得完整圆环外径/中心；行卡片中心由加载态 uiautomator dump 的 clickable Card bounds 求得。
| 版本 | 圆环外径（实测 px） | 换算 dp | 行卡片中心 y | 圆环中心 y | 垂直偏移 |
|---|---|---|---|---|---|
| **v0.2.23**（v64 build） | **105 × 105** | **30dp** | 2055.5（Card [56,1933][1224,2178]） | 2073.0 | **+17.5px（+5.0dp，偏下）** |
| **v0.2.24**（v65） | **70 × 70** | **20dp（= 20dp × 3.5）** | 2383.5（Card [56,2261][1224,2506]） | 2383.5 | **0.0px（完全居中）** |
- v0.2.24 圆环右缘 1160.5px 与行内容右缘 1161px 对齐（右内边距 1px 内），无溢出/裁剪
- **Home 两处 spinner**：源码固定尺寸容器 `Box(size=20/18.dp, contentAlignment=Center)` 保证同心；右上下拉按钮中心 (1154,232) 与图标亮像素中心 (1153.4,231.3) 偏差 0.7px（几何同心）。**运行时限制**：Home 刷新/状态检测 spinner 为 <150ms 瞬时态，已尝试设备端 10–12 帧连拍（~150ms/帧）、SIGSTOP 冻结帧均未捕获到 spinner 帧 → 以源码参数（20dp/18dp、strokeWidth 2dp、固定 Box 居中）+ About 同组件 20dp 实测外径 70px 为证据；未发现 30dp 大环（v0.2.23 旧行为已消除）
- 截图/帧：r36_spinA_1..10（v65 About 加载态）、r36_v64b_1..10（v0.2.23 对比）、r36_home_frozen / r36_home_after（Home 状态）

## 3) 关于致谢无重复 — PASS
- 全页滚动 dump（5 个滚动位置汇总）：**「致谢」section 仅 1 处**（unique=1；重复计数来自同一节点在多次 dump 中重复出现）
- DeepSeek 文案 **仅 1 处**：「本软件使用 DeepSeek V4.1-Flash 制作，可能存在部分问题。若您在使用过程中遇到问题，请导出诊断报告，并通过“发送错误报告”向我反馈问题，感谢各位的支持。」
- 「发送错误报告」入口 1 行（Card→GitHub Issues ACTION_VIEW）+ 正文内引用 1 处；无重复 Card、无旧致谢残留
- 致谢 Card 内含：感谢 Miuix（界面组件）、LSPosed（模块框架）以及所有开源贡献者；本应用只在本机修改卡面显示，不收集、不上传任何数据
- 截图：r36_about_last / r36_about_final

## 4) 回归 — PASS
| item | 结果 |
|---|---|
| 状态灯三色 | PASS — 绿 **(101,196,102)**、黄 **(243,179,70)**（faceUrl 置空+清 originals）、红 **(215,83,63)**（cards=[]），与 round-35 一致；恢复 report 备份后回绿 |
| ≥6x 红字 | PASS — 3x/4x/5x **不显示**；10x **显示**「当前导出画质倍率过高…」 |
| 主页/缩略图 | PASS — 三卡片缩略图（交通卡/门禁/车钥匙）正常 |
| 编辑器 tap/拖动 | PASS — `tap handled 0ms`、`tap->nextFrame +1ms`；拖动 **41 帧 jank 2/41=4.88%**（p50 5/p90 8）、**39 帧 jank 1/39=2.56%**（p50 8/p90 10） |
| 保存 3x | PASS — `render|timing ms=251`、导出 **std=73.56 非空白** |
| 返回转场 3 次 | PASS — 均返回 Home；53/54/54 帧、jank 3.70–3.77%、p90 9–11ms |
| 2 次冷启动 | **PASS — 0 real ANR/FATAL** |
| 钱包替换 | PASS — `applied face transit:… v=19` |
| 诊断 | PASS — 0.2.24 导出 `diagnostic_20261121-020731.txt` 30,343 B「应用版本：0.2.24」 |
| 清理 | PASS — report.json 已恢复备份、originals 按需再抓、stayon false、lspd 10/10 |

## 结论
**PASS**：
- ✅ **加载圈修复**：About 导出 spinner 外径实测 **70px = 20dp**（v0.2.23 旧版 **105px = 30dp**），垂直偏移由 **+17.5px（+5.0dp）→ 0.0px**，右缘对齐内容边（1px 内）；Home 两处 spinner 源码为 20dp/18dp+2dp 描边固定居中容器（几何同心 0.7px）
- ✅ **About 致谢无重复**：致谢 section 与 DeepSeek 文案各 1 处，发送错误报告入口正常（1 行 + 正文引用）
- ✅ 回归全 PASS（三色/≥6x/保存/拖动 jank 4.88% 与 2.56%/返回 p90 ≤11ms/2 次冷启动 0 ANR/诊断 30.3KB）
- 限制：Home 两处 spinner 为 <150ms 瞬时态，运行帧未能捕获（多手段尝试），以源码参数 + 同组件 About 实测 70px 为证据，未复现 30dp 大环
