# ACCEPTANCE ROUND 14 — v0.2.10 (v40-final)

Device: Honor 192.168.31.10:5555（仅荣耀钱包+面板；未安装/未启动 OPPO 钱包）。
Delivery artifact: `work\module\poc\card-personality-v40-final.apk`, 36,451,228 B, sha256 **59650F37DA93CE25C0B9A38BE4469CDD6286CD984783AA40AD8BD294C1649C1D**（versionCode=1 / versionName=0.2.10）。安装后启动一次面板、重启钱包。

## A) 荣耀回归（快速 PASS）
| item | verdict | evidence |
|---|---|---|
| 版本/首页 | **PASS** — dumpsys versionName=0.2.10；Home `v0.2.10` + 状态行 `com.hihonor.wallet` | round14_home.png, ui_r14_home.xml |
| 编辑/保存/自适应 | **PASS** — 编辑器可换示例底图、加文字贴纸、保存；说明行 `导出 2016 × 1248（6x）` | ui_r14_ed.xml, ui_r14_style.xml |
| 组件隐藏 | **PASS** — 组件 tab 站点状态标签→隐藏；config `hiddenAppElements=["tv_card_label"]`；钱包详情 chip 不再显示 | ui_r14_comp.xml, cfg_r14_hide.json, round14_hide_wallet.png/crop |
| ZIP 导出 | **PASS** — `岭南通·羊城通_20261116-233540.zip` 76,341 B（含底图资源） | Download/honor_cards_personality |
| 旋转 ±270 | **PASS** — 滑杆两端 -270° / 270° | ui_r14_rotL.xml, ui_r14_rotR.xml |
| 数值弹窗 | **PASS** — 旋转弹窗“范围：-270° ~ 270°”、预填 270、取消/确定 | ui_r14_num.xml |
| 诊断导出 | **PASS** — `diagnostic_20261116-233613.txt` 17,084 B；含 `应用版本：0.2.10`、`OPPO 引擎状态（provider engine_state）` 段（Honor 上引擎未激活：`配对 ? / 抓图 ? / 来源 无`，JSON `{}`，属预期） | round14_diag_toast.png, work\device\logs\r14_diagnostic.txt |
| 双击电源冷启动 | **PASS** — force-stop 面板+钱包 → 锁屏 → `input keyevent 26 26` → `SwipeCardActivity` 自定义卡面 | round14_doublepress.png / crop |
| 字体/描边/刷新缩略图 | **carried PASS（v39/v38 实测）** — v40 Honor 侧零改动（module-dev：Honor 全回归零改动） | 既往 acceptance |
| 冷启动 ANR | **PASS** — 2 次冷启动 **0 real ANR/FATAL** | logcat |

## B) 关于页 UI（PASS）
| # | item | verdict | evidence |
|---|---|---|---|
| a | 开源支持三行 | **PASS** — `Miuix` + 灰字`界面组件与设计规范`；`LSPosed` + 灰字`Xposed 框架与模块加载`；`oplus-card-customizer` + 灰字`OPPO 钱包卡面自定义支持（实现参考）`。点击第三行 → `capturedLink=https://github.com/crrashh1542/oplus-card-customizer`（GitHub app 接管，无崩溃） | ui_r14_about2.xml, round14_about.png, round14_jump_oplus.png |
| b | 「反馈」标题 | **PASS** — 原「链接」已为「反馈」 | ui_r14_about2.xml |
| c | 支持钱包两行 | **PASS** — 荣耀 `com.hihonor.wallet` 11.0.3.305 / 欧加 `com.finshell.wallet` 5.52.0 | ui_r14_about.xml |
| d | 图标 96dp 保持 | **PASS** — About 图标 View `[472,547][808,883]` = 336×336 px ≈ 96dp @3.5（与 v39 一致） | ui_r14_about.xml, round14_about.png |
| e | 版本 0.2.10 | **PASS** — `版本 v0.2.10` | ui_r14_about.xml |

## C) OPPO 静态验证（无设备）— PASS
frozen APK dex（classes3/4 等）实测标记：
- **CNIV 补全方法**：精确短串 `k1`、`d1`、`a1`、`b1` 均存在（classes3 各 ≥1）
- **Fin* 四容器**：`FinBigCardFaceView`、`FinCustomCardView`、`FinSmallCardFaceView`、`FinUINfcCardFaceView`
- **本地文件替换**：`file://`、`oppo_faces`、`cardpersonality_oppo_map`
- **模型 getter/上报**：`getAid`、`getAppCode`、`getCardType`、`getBalance`（`balance`/`getAcctAmount` 由 module-dev 静态清单确认）
- **诊断**：`widgetHits`、`fileReplacements`、`recordedModels`（dex 标记；荣耀上 engine_state 为空属预期）
- **OPPO 引擎**：`OppoFaceHookEngine`(86)；`CircleNetworkImageView`、`setSvgUrl` 等保留
- **荣耀 hook 未回归**：`getter hook re-entered` 在 classes4；Honor 侧实测 PASS
- manifest `<queries>` 候选钱包包保留；**未在荣耀设备安装/启动 com.finshell.wallet**；OPPO 运行时行为不在本轮范围（用户明确无需实机验证）

## D) 版本 / 发布 / PR
- aapt2: `versionCode='1' versionName='0.2.10'` — PASS
- **无 v0.2.10 tag**：`git tag --list` 仅 `v0.2.5`, `v0.2.7`（tag 由 Lead 在本轮验收通过后创建）
- **main 未合并**：repo 分支 `feat/round14` HEAD `2de21f4 feat: v0.2.10 round-14 …`；`main` HEAD 仍 `523ab54 feat: v0.2.7 …`；`rev-list --count main..HEAD = 5` → stacked（feat/round14 ← feat/round13 ← feat/round12 ← main），PR #3 由 Lead 创建

## E) 清理
config/exports cleared；wallet 重启回原卡面（round14_final_original.png）；`svc power stayon false`；LSPosed 10/10 enabled；冷启动 0 ANR/FATAL。

## F) Pre-release 核对（v0.2.10 已发布，PASS）
GitHub Release: https://github.com/sofa7320/Honor-Cards-Personality/releases/tag/v0.2.10 （API id 447241120，published_at 2026-11-16T15:42:08Z）

| 核对项 | 结果 |
|---|---|
| prerelease / draft | **PASS** — `prerelease: true`、`draft: false`；设备端 Release 页显示「预发行」标签（round14_release_page.png） |
| assets（3 个） | **PASS** — `CardPersonality-v0.2.10.apk` 27,343,721 B，digest `sha256:e5d7bd9100c5ae041ef507500199dacb1e57b4350d6177d8dc0ecf6609038b9b`；`CardPersonality-v0.2.10.apk.sha256`（94 B）；`NOTICE.md`（2,765 B） |
| 正文含 0.2.10 changelog | **PASS** — body 开头「## 本次更新 · v0.2.10」+ PR/Pre-release 说明 + OPPO 卡面能力移植（CNIV 14 方法、Fin* 容器、file:// 方案、诊断 widgetHits/fileReplacements/recordedModels）+「关于页开源支持新增 oplus-card-customizer」+ 安装/资产说明 |
| 发布链接可达 | **PASS** — 设备打开 release 页，`capturedLink=https://github.com/sofa7320/Honor-Cards-Personality/releases/tag/v0.2.10`（GitHub app，截图 round14_release_page.png） |
| main 是否合并 | **未合并** — repo `main` HEAD 仍 `523ab54`（v0.2.7）；PR #3 仍开启，Lead 侧管理 |

备注：Release 资产为 **release 签名构建**（27,343,721 B，R8 收缩后），与验收用 debug 构建（36,451,228 B，sha 59650F37…）字节不同属预期；版本/功能同源（R8 仅优化体积）。建议用户在发布资产上做一次冒烟安装即可（本验收设备保持 debug 构建以便后续回归）。

