# ACCEPTANCE ROUND 13 — v0.2.9 (v39-final, stacked PR)

Device: Honor 192.168.31.10:5555（仅荣耀钱包+面板；未安装/未启动 OPPO 钱包）。
Delivery artifact: `work\module\poc\card-personality-v39-final.apk`, 36,562,425 B, sha256 **7AD2ACDD94D0A68DA907D6F4D05601183FBD664AC680F50B4223F18842B822C3**（versionCode=1 / versionName=0.2.9）。安装后启动一次面板、重启钱包。

## A) 荣耀回归（快速 PASS）
| item | verdict | evidence |
|---|---|---|
| 版本/首页 | **PASS** — dumpsys versionName=0.2.9；Home `v0.2.9` + 状态行 `com.hihonor.wallet` | round13_home.png, ui_r13_home.xml |
| 编辑/保存 | **PASS** — 组件隐藏保存（config 写入）后钱包详情生效 | cfg_r13_hide.json, round13_hide_wallet.png |
| 组件隐藏 | **PASS** — 组件 tab 站点状态标签→隐藏；config `hiddenAppElements=["tv_card_label"]`；钱包详情 chip 不再显示 | ui_r13_comp.xml, round13_hide_wallet_crop.png |
| ZIP 导出 | **PASS** — 新包 `岭南通·羊城通_20261116-225445.zip` 3,062,766 B | Download/honor_cards_personality |
| 诊断导出 | **PASS** — `diagnostic_20261116-225552.txt` 15,310 B；含 `应用版本：0.2.9`、`OPPO 引擎状态（provider engine_state）` 段与 OpLog | round13_diag_toast.png, work\device\logs\r13_diagnostic.txt |
| 双击电源冷启动 | **PASS** — force-stop 面板+钱包 → 锁屏 → `input keyevent 26 26` → `SwipeCardActivity` 自定义卡面 | round13_doublepress.png / crop |
| 旋转 ±270 / 数值弹窗 / 字体 / 描边 / 自适应 / 关于页刷新缩略图 | **carried PASS（v38 实测）** — v39 delta = OPPO 读取链路 hook + About UI（开源支持/反馈/图标），编辑器渲染与滑杆/字体/描边代码未变；本轮 v39 已实测 2016×1248(6x) 自适应照常、编辑/保存/ZIP/隐藏/诊断/冷启动 | UI 证据同 round-12/v38 |
| 冷启动 ANR | **PASS** — 2 次冷启动 **0 real ANR/FATAL** | logcat |

## B) 关于页 UI（PASS）
| # | item | verdict | evidence |
|---|---|---|---|
| a | 开源支持区块（Miuix / LSPosed 白字标题+灰字说明，可点击跳转） | **PASS** — 区块两行：`Miuix` + 灰字 `界面组件与设计规范`；`LSPosed` + 灰字 `Xposed 框架与模块加载`。点击分别触发深链：`capturedLink=https://github.com/compose-miuix-ui/miuix` 与 `capturedLink=https://github.com/LSPosed/LSPosed`（GitHub app 接管，无崩溃） | ui_r13_about2.xml, round13_about.png, round13_jump_miuix.png, round13_jump_lsposed.png |
| b | 原「链接」标题改为「反馈」 | **PASS** — About 区块标题 `反馈`（GitHub 仓库 / 发送错误报告 两行在其下） | ui_r13_about2.xml |
| c | 软件图标明显放大且清晰 | **PASS** — About 图标 View 由 v38 的 252×252 px 放大到 **336×336 px（+33%）**，清晰无糊；内容与桌面图标一致（同一角色/白框/圆角遮罩，桌面额外叠加主题背板色） | 图标 bounds: v39 `[472,547][808,883]`；round13_icon_compare.png（左 About / 右 桌面文件夹图标）, round13_about.png |
| d | 版本显示 0.2.9 | **PASS** — `版本 v0.2.9` | ui_r13_about.xml |

## C) OPPO 读取链路（静态验证，无设备）— PASS
frozen APK dex 实测标记（classes3/4/7）：
- **L0 配对**：`QuickCardNfcCardView`、`setNfcCardData`、`getNfcCardData`、`bindData`、`DragonflyConsumeCardsFrameController`、`b0.a`、`mThemeImg`
- **抓图**：`FaceCapture`（classes4 33 处）、`capture-scheduled`、`local-file-captured`、`local-file-fail`
- **本地文件读取/圆角安全替换**：`ensureLocalFaceFile`(6)、本地文件路径交给钱包 Glide（capture-* 标记）
- **类型映射**：`typeOfNfcCard`
- **诊断**：`pairCount`、`captureCount`、`faceSource`、`oppo_engine.json`、`engine_state`
- **OPPO 引擎**：`OppoFaceHookEngine`(103)、`setSvgUrl`；manifest `<queries>` 6 候选包保留，无 QUERY_ALL_PACKAGES
- **荣耀 hook 未回归**：`getter hook re-entered` 在 classes4；荣耀侧 A 项全部实测 PASS
- 未在荣耀设备安装/启动 `com.finshell.wallet`；OPPO 运行时行为不在本轮范围（用户明确无需实机验证）

## D) 版本 / 发布 / PR
- aapt2: `versionCode='1' versionName='0.2.9'` — PASS
- **无 v0.2.9 tag**：`git tag --list` 仅 `v0.2.5`, `v0.2.7` — PASS
- GitHub Releases：页面/API 均无 v0.2.9 Release（最新可见稳定版 v0.2.5，另存在 v0.2.7 Pre-release）— **本轮无 Release** PASS
- **main 未合并**：repo 分支 `feat/round13` HEAD `085a5f6 feat: v0.2.9 round-13 …`；`main` HEAD 仍 `523ab54 feat: v0.2.7 …`；`rev-list --count main..HEAD = 3` → stacked PR（feat/round13 ← feat/round12 ← main）未合并

## E) 清理
config/exports cleared；wallet 重启回原卡面（round13_final_original.png）；`svc power stayon false`；LSPosed 10/10 enabled；冷启动 0 ANR/FATAL。

## 备注
- 本轮 v39 的 UI 改动仅 About（开源支持/反馈/图标）；编辑器/渲染路径未变，故滑杆/字体/描边等沿用 v38 实测结论并在 v39 上抽查自适应/编辑保存正常。
- About 图标与桌面图标内容一致；桌面端额外叠加了启动器主题背板色（系统行为）。
