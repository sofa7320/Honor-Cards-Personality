# ACCEPTANCE ROUND 25 — v53-final（0.2.14：转场/刷新/缩略图/输出 tab/Lanczos3 + OPPO 修复）

Artifact: `work\module\poc\card-personality-v53-final.apk`, 37,547,279 B, sha256 **ECBDC345CDEE2E46AA3B06CC74403A975B6ED6D593CFBB80630B60A5F8D2AC93**（versionCode=1 / versionName=**0.2.14** / label **药做卡面**；本地交付）。
Device: Honor 192.168.31.10:5555（已恢复在线），LSPosed 2.2.1 / API 102。

## 1) 滑动转场 — PASS（功能级）
- Home→Editor→返回 Home、Home→About→返回 Home 均正常，返回后状态保持（Home 文本/卡片列表完整，无状态丢失）
- 前进/返回方向相反、300ms、无闪烁：**功能截图 PASS**（round25c_transition_to_editor / round25c_transition_to_about / round25c_transition_back_home / round25c_transition_about_back，150ms 中途截图与稳定态一致）；精确方向/时长需录屏逐帧，本轮未做逐帧核验

## 2) 刷新 — PASS
- **无 1.5s 轮询**：Home 静置 6s，logcat 仅 1 条 CARD_PERSONALITY/PROVIDER 行（无周期性重载）；静态 `无 delay(1500)` 佐证
- 打开 App / 编辑返回 / 右上角刷新 均正常（round25c_after_refresh.png）
- 保存后即时更新：Home 交通卡行时间变为 22:50（2x 保存后）、out tab 与预览同步（round25c_state.png）

## 3) 缩略图压缩 — PASS
- `files/exports/<hash>.jpg.thumb420.webp` 与 `files/cache/original/*.thumb420.webp` 实测存在（10,378→8,912→14,682 B 随内容更新）
- 首页缩略图清晰（round25c_home2.png / round25c_thumb_crop2.png）；编辑/导出不受影响（导出按所选倍率，见下）
- 内存下降未做定量测量（无 profiler 数据），仅有 webp 缓存生成/尺寸证据

## 4) 编辑页「输出」tab — PASS
- tab 存在（图片/元素/组件/输出）；说明「输出画质决定保存到钱包的卡面分辨率…」
- **重置按钮已迁入输出 tab**：「重置为钱包原始卡面」+ 确认弹窗「恢复原始卡面？/将删除本卡的自定义卡面与元素设置…/取消/恢复」（round25c_reset_dialog.png）
- **1x–10x 滑块 + 当前倍率/预计输出**：实测 1x=336×208、2x=672×416、6x=2016×1248、10x=3360×2080（`当前倍率 Nx，预计输出 …px` 同步）
- **导出按所选倍率**（render|export-ok 证据 + 拉取测量）：
  | 倍率 | 预计 | 实际导出 JPEG 尺寸/大小 |
  |---|---|---|
  | 2x | 672×416 | **672×416 / 57,271 B**（22:50:49） |
  | 6x | 2016×1248 | **2016×1248 / 257,803 B**（22:53:05） |
  | 10x | 3360×2080 | **3360×2080 / 514,684 B**（22:52:22） |
  - 倍率持久化：`shared_prefs/card_personality_editor.xml` → `export_scale` 值随选择变化
  - 注意：仅拖动滑块不触发保存（保存按钮无 dirty 行为）——需改动图片/元素后再保存才重新导出（本轮通过「使用示例图片」制造 dirty 后保存验证；属 UI 交互设计，建议文档说明或滑块变更即标 dirty）
- ZIP 导入按倍率：本轮未实测（文件选择器交互耗时/不稳定），列为待补

## 5) Lanczos3 — PASS
- 10x 导出 3360×2080（514,684 B）放大查看线条/文字边缘平滑、无明显锯齿（round25c_export_10x.jpg）
- 保存耗时：2x≈?（<4s）、6x≈6s、10x≈**8.2s**（含导出+缩略图+界面刷新）；**无 OOM/ANR**（logcat 无 OutOfMemory/FATAL/ANR）

## 6) 荣耀回归 — PASS
| item | result |
|---|---|
| 卡面替换 | PASS — `nh5#c SUBSTITUTED` + `applied face … iv_traffic_card_bg`（v=9，config 注入） |
| 编辑/保存 | PASS（2x/6x/10x 保存均成功并导出对应尺寸） |
| 组件隐藏 | PASS（配置 hidden tv_card_label；钱包侧 hide 逻辑执行） |
| 双击电源冷启动 | PASS — `SwipeCardActivity` 自定义卡面（round25c_doublepress.png） |
| 冷启动 ×2 | **PASS — 0 real ANR/FATAL** |
| 诊断导出 | PASS — `diagnostic_20261117-225400.txt`（27,985 B，0.2.14 + render|export-ok 记录 + bridge 段） |
| 关于页 | PASS — **药做卡面** / v0.2.14 |
| 清理 | PASS — config/exports 清理、stayon false、lspd 10/10 |

## 7) 静态 — PASS
- aapt `versionCode=1/versionName=0.2.14/label 药做卡面`；API102-only（无 `assets/xposed_init`、10 dex、0 de.robv/0 libxposed、META-INF 三件、scope.list 7 包含 com.miui.tsmclient）
- dex：`slideInHorizontally`、`Lanczos3`(2)、`setExportScale`(2)、`thumb`、`输出`、`预计`、`capture-view-ok`、`skipPlaceholder`、`viewCaptureCount`、identity 派生链 `getCardNo/getAid/getAppCode/getBizId/getCardId/getRecordId`、`OppoFaceServer`(14)、`WalletFaceClient`(14)、`push-face-ok`、`XiaomiFaceHookEngine`(90)、`XiaomiBridge`(41)、`HookBridge`、`Xposed102Entry`、`FaceHookEngine`(388)、ANR guard
- **无 `delay(1500)`**；`build.yml` L160-169 从 CHANGELOG 抽节并 **strip `>` 开头行**（RELEASE_NOTES 不再含「本轮以 Pre-release…/Pull Request…」）✓
- OPPO/小米：静态标记在（无真机）

## 结论
**PASS（设备 + 静态）**，附注：
1. 转场方向/300ms 未逐帧验证（功能+截图正常）；
2. 滑块变更本身不标 dirty（需再改动图片/元素后保存才重导出）— 建议确认是否符合预期；
3. ZIP 导入按倍率未实测；
4. 缩略图内存下降未定量测量（有 webp 缓存证据）。
其余 lead 验收项均实测 PASS；可打 tag 发 v0.2.14 Pre-release（PR #9 base=main）。

---

# v54 re-freeze（authoritative，替代 v53）— PASS

Artifact: `work\module\poc\card-personality-v54-final.apk`, 37,548,603 B, sha256 **D9695466486A11842C9C22EB8AEC5D6092CFFD9E675FB21AB6122F39FBA20E9C**（versionCode=1 / versionName=**0.2.14** / label 药做卡面）。
变更：仅修 v53 的两条 caveat（② 倍率滑块改动即 dirty + 保存前显式 setExportScale；③ ZIP 导入按当前倍率）。

## 静态 — PASS
- aapt `versionCode=1/versionName=0.2.14/label 药做卡面`；API102-only（无 xposed_init、10 dex、0 de.robv/0 libxposed、META-INF 三件）
- dex 新增：`initialExportScale`(3)、`setExportScale`、`scaleDirty`、`export_scale`；其余 v53 markers 均在；**无 delay(1500)**

## Caveat ② 修复验证 — PASS（不再需要改图制造 dirty）
在已有自定义卡面的编辑器里**只拖「导出画质」滑块**（不改图）→ 保存：
| 滑块设置 | 预计输出 | 实际保存导出（render|export-ok + 拉取测量） |
|---|---|---|
| 2x | 672×416 | **672×416 / 24,826 B**（23:01 后） |
| 6x | 2016×1248 | **2016×1248 / 129,309 B**（23:01:17，config v=10） |
| 10x | 3360×2080 | **3360×2080 / 254,119 B** |
- 未保存确认弹窗：「您的修改尚未保存，确定离开吗？/保存并离开/不保存并离开/继续编辑」实测出现（round25d_unsaved_dialog.png）→ 滑块改动确实标 dirty

## Caveat ③ ZIP 导入按倍率 — PASS
- 文件选择器选中历史 ZIP（`岭南通·羊城通_20261113-213845.zip`）→ 导入后编辑器保留当前倍率 **4x（导出 1344×832）**
- 导入后保存 → 实际导出 **1344×832 / 48,293 B**（render|export-ok 23:03:26，config v=14）✓

## 荣耀抽测 — PASS
- 双击电源冷启动 → `SwipeCardActivity`（round25d_doublepress.png）；**2 次冷启动 0 real ANR/FATAL**
- 转场/刷新（无 1.5s 轮询）/缩略图 webp（前节 v53 实测，v54 仅编辑器保存逻辑改动）无回归
- 清理：config/exports cleared、原卡面、stayon false、lspd 10/10

## 结论
**v54 = PASS**：caveat ②/③ 均已实测修复（滑块即 dirty、保存按倍率导出、ZIP 导入保留倍率）；v53 其余 PASS 项延续。可打 tag 发 v0.2.14 Pre-release（PR #9 base=main）。
