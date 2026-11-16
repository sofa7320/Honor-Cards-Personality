# ACCEPTANCE ROUND 12 — v0.2.8 (v38-final, PR-only)

Device: Honor 192.168.31.10:5555（仅荣耀钱包+面板；未安装/未启动 OPPO 钱包）。
Delivery artifact: `work\module\poc\card-personality-v38-final.apk`, 37,120,437 B, sha256 **95EBC9F2C46B74F6091958558C49E25167C0DD8465564573F508280C2D8F3705**（versionCode=1 / versionName=0.2.8）。安装后启动一次面板、重启钱包。
> 注：先前 v37-final（`43386779…`）的 b) 图标项为 PARTIAL（foreground-only）；v38 为图标修复版，本文件 b) 结论已按 v38 实测更新为 **PASS**。

## A) 荣耀回归（快速 PASS）
| item | verdict | evidence |
|---|---|---|
| 版本/首页 | **PASS** — dumpsys versionName=0.2.8；Home `v0.2.8` + 状态行仅 `com.hihonor.wallet` | round12_home.png, ui_r12_home.xml |
| 编辑/保存/卡面替换 | **PASS** — 门禁卡换示例图保存 → 首页缩略图行时间更新（18:19）、钱包详情渲染自定义卡面 | round12_detail.png/crop, ui_r12_home4.xml |
| 双击电源冷启动 | **PASS** — force-stop 面板+钱包 → 锁屏 → `input keyevent 26 26` → `SwipeCardActivity` 自定义卡面 | round12_doublepress.png / crop |
| 旋转范围 | **PASS** — 滑杆两端 -270° / 270° | ui_r12_rotL.xml, ui_r12_rotR.xml |
| 数值输入 | **PASS** — 旋转弹窗“范围：-270° ~ 270°”、预填 270 | ui_r12_num.xml |
| 刷新/缩略图 | **PASS** — 右上角刷新点击后重新拉取（loading 截到，数据一致时缩略图不变）；换面后首页缩略图更新（round-10 行为沿用） | round12_refresh.png, ui_r12_home4.xml |
| 诊断导出 | **PASS** — About「导出诊断报告」→ toast 路径；`diagnostic_20261116-184839.txt`（10,241 B）已拉取；含 `应用版本：0.2.8`、wallets JSON、**OPPO 引擎状态（provider engine_state）** 与 getReport extra engine 段、OpLog 行（含 hook|apply） | round12_diag_toast.png, work\device\logs\r12_diagnostic.txt |
| 冷启动 ANR | **PASS** — 2 次冷启动 **0 real ANR/FATAL** | logcat |

## B) UI 修复（重点）
| # | item | verdict | evidence |
|---|---|---|---|
| a | 关于页系统侧滑/顶部返回 → 软件主页 | **PASS** — ① 系统边缘侧滑返回：About → focus 变 `com.cardpersonality.honor/.MainActivity`（Home 文本：模块通信正常/已识别的卡片）；② 硬件返回键同上；③ 顶部返回箭头（content-desc 返回）→ Home。均未回桌面 | round12_about_back_sys.png, round12_about_back_arrow.png, round12_edge_back.png, ui_r12_after_back*.xml |
| b | 关于页图标与桌面/应用图标一致 | **PASS (v38 复测)** — v38 已改为合成 adaptive 背景+前景并按容器遮罩裁剪；About 图标现与桌面图标内容/遮罩一致（同一角色 + 白色圆角底框 + 圆角遮罩），差异仅为荣耀桌面额外叠加的图标背板色调。并排截图：`work\device\shots\round12b_icon_compare2.png`（左=About，右=桌面文件夹图标），单张：round12b_about.png / round12b_folder.png。v37（43386779…）为 foreground-only PARTIAL，已被本版本取代 | round12b_icon_compare2.png, round12b_about.png, round12b_folder.png |
| c | GitHub/错误报告行无灰色小字 | **PASS** — About dump 中「GitHub 仓库」「发送错误报告」仅标题（原灰字 `sofa7320/Honor-Cards-Personality`、`GitHub Issues` 已移除） | ui_r12_about.xml, round12_about.png |
| d | 支持的钱包不显示本机安装版本号 | **PASS** — 仅显示支持版本+包名：荣耀钱包 `com.hihonor.wallet` 11.0.3.305；欧加钱包 `com.finshell.wallet` 5.52.0（无“本机 11.0.3.305/已安装”字样） | ui_r12_about.xml, round12_about.png |
| e | 仅荣耀+欧加两项、无「未适配钱包」区块 | **PASS** — About 列表仅 2 项；无「检测到未适配钱包」区块/文案 | ui_r12_about.xml |
| f | 首页状态行只显示包名 | **PASS** — Home 行 = `com.hihonor.wallet`（无「识别钱包包名：」前缀） | ui_r12_home.xml, round12_home.png |

## C) OPPO 适配（静态验证，无设备）— PASS
- **Manifest `<queries>`**：6 候选包全在（com.hihonor.wallet / com.finshell.wallet / com.coloros.wallet / com.oplus.wallet / com.oneplus.wallet / com.nearme.wallet），无 QUERY_ALL_PACKAGES。
- **Dex 标记**：`A0 / y0 / x0 / F0 / setSvgUrl`（H-A 入口反射名，classes3 及精确 2 字符字符串均在）、`engine_state`、`oppo_engine.json`、`url:`、`com.finshell.wallet`、`OppoFaceHookEngine`(87)、`CardCoverSettingUtil`、`fetchBrightnessUnderTextView`、`oplog.txt`、`scan_wallets`、Honor ANR guard `getter hook re-entered`。
- **未在荣耀设备安装/启动 OPPO 钱包**；OPPO 真机行为不在本轮可验证范围。

## D) 版本 / 发布 / PR
- aapt2: `versionCode='1' versionName='0.2.8'` — PASS
- **无 v0.2.8 tag**（`git tag --list` 仅 v0.2.5、v0.2.7）；GitHub Releases API 最新为 **v0.2.7 Pre-release**（`prerelease: true`），无 v0.2.8 Release — PASS
- **PR 分支未并入 main**：repo 分支 `feat/round12`，HEAD `82132f4 feat: v0.2.8 round-12 …`；`main` HEAD 仍为 `523ab54 feat: v0.2.7 round-11 …`；`git rev-list --count main..HEAD = 1` → main 未被直接合并（PR 状态由 Lead 核查）

## E) 清理
config/exports cleared；wallet 重启回原卡面（round12_final_original.png）；`svc power stayon false`；LSPosed 10/10 enabled。

## 备注
- **B-b 图标项建议**：About 图标应使用与桌面一致的 **adaptive icon 合成结果**（或 mipmap ic_launcher 位图/已合成 drawable），当前 `getApplicationIcon` 转位图在 Compose 下只拿到 foreground，导致无背景/遮罩；截图对比可见差异。
- 其余 round-12 UI 项（a/c/d/e/f）均实测 PASS；荣耀回归无变化。

---

## v38 复测（图标修复版，sha 95EBC9F2…）

| 复测项 | 结果 | 证据 |
|---|---|---|
| 1) About 图标 vs 桌面图标 | **PASS** — v38 About 图标为 adaptive 背景+前景+圆角遮罩合成，角色/白框/遮罩与桌面文件夹图标一致（桌面额外叠加主题背板色） | round12b_icon_compare2.png（左 About / 右 桌面）, round12b_about.png, round12b_folder.png |
| 2a) About 系统边缘侧滑返回 | **PASS** — About → 边缘侧滑 → focus com.cardpersonality.honor/.MainActivity（Home 文本：模块通信正常/com.hihonor.wallet/已识别的卡片） | ui_r12b_after_edge.xml, round12b_back_gesture.png |
| 2b) About 顶部返回箭头 | **PASS** — 返回箭头 → Home | ui_r12b_after_arrow2.xml |
| 2c) 支持钱包两行无本机版本 | **PASS** — 荣耀 com.hihonor.wallet 11.0.3.305 / 欧加 com.finshell.wallet 5.52.0（无 “本机/已安装” 字样） | ui_r12b_about2.xml, round12b_about.png |
| 2d) 首页状态仅包名 | **PASS** — com.hihonor.wallet（无前缀） | ui_r12b_home.xml |
| 2e) 诊断导出仍含 engine_state | **PASS** — diagnostic_20261116-190156.txt（12,176 B）含 应用版本：0.2.8、OPPO 引擎状态（provider engine_state）、getReport extra engine 段 | round12b_diag_toast.png, work\device\logs\r12b_diagnostic.txt |
| 3) 无 v0.2.8 tag/Release | **PASS** — git tag --list 仅 v0.2.5 / v0.2.7；GitHub Releases 最新 = **v0.2.7 Pre-release**（prerelease:true），无 v0.2.8 Release；eat/round12 HEAD 82132f4 领先 main 523ab54 1 commit（main 未合并，PR 由 Lead 更新） | git 输出, GitHub API |
| 清理 | **PASS** — 已清 config/exports、钱包回原卡面、stayon false、lspd 10/10；冷启动 0 ANR/FATAL | round12b_final_original.png |
