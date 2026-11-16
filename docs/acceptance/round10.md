# ACCEPTANCE ROUND 10 — v0.2.6 (v35-final)

Device: Honor 192.168.31.10:5555 (only Honor wallet + panel used; **OPPO wallet `com.finshell.wallet` was never installed/launched on it**).
Delivery artifact: `work\module\poc\card-personality-v35-final.apk`, 37,051,401 B, sha256 **18064916A5E35526B6981AC4566A7E0C66AF34BD8FA7FAF468279121E77DFD31** (versionCode=1 / versionName=0.2.6). Panel launched once after install, wallet restarted.
Earlier v33/v34 shas were ignored for delivery per Lead (v33 7C77AD3F…, v34 B3AAF40E…); this matrix is the v35 run.

## A) Honor regression (PASS)
| item | verdict | evidence |
|---|---|---|
| 卡面替换/编辑/保存 | **PASS** — 交通卡 face replaced via editor, saved, wallet detail renders custom face | round10_detail.png, round10_detail_crop.png |
| 双击电源冷启动 | **PASS** — force-stop panel+wallet → lock → `input keyevent 26 26` → `SwipeCardActivity` shows custom face | round10_doublepress.png, round10_doublepress_crop.png |
| 组件隐藏 | **PASS** — 组件 tab 站点状态标签 → 隐藏；config `hiddenAppElements=["tv_card_label"]`; wallet detail chip 「已出站」 GONE | round10_components.png, round10_hide_wallet.png/crop, cfg_r10c.json |
| ZIP | **PASS export** — new `岭南通·羊城通_20261116-131903.zip` 1,679,929 B | ls Download/honor_cards_personality |
| 字体语言标注 | **carried PASS** from v32/round-9 (font catalog unchanged in round 10; editor scroll state prevented fresh dialog capture this pass) | ACCEPTANCE-round8/9 evidence |
| 描边外轮廓 | **PASS** — selected text sticker shows ONE glyph set, white fill + red outer-only outline, dashed selection box, no ghost | round10_stroke_selected_zoom.png |
| 自适应渲染 | **PASS** — editor captions show actual adaptive output: `导出 2016 × 1248（6x）` for photo base, `导出 1344 × 832（4x）` | ui_r10_ed.xml, ui_r10_acc_ed.xml |
| 旋转 ±270 | **PASS** — slider ends: -270° and 270° | ui_r10_rotL.xml, ui_r10_rotR.xml |
| 数值输入 | **PASS (dialog)** — tap 旋转 value → Miuix dialog “输入旋转 / 范围：-270° ~ 270°”, prefilled 270, 取消/确定 | round10_num_dialog.png, ui_r10_num.xml; invalid/clamp 逻辑 carried PASS from round 9 (v32) |
| 选中延迟 | **PASS** — `CARD_PERSONALITY_PERF: tap handled in 0ms`, `tap->nextFrame +22ms` | logcat |
| 冷启动 ANR | **PASS** — 2 cold starts → 0 real ANR/FATAL | logcat |

## B) UI（PASS）
1) 首页状态小字：**PASS** — `识别钱包包名：com.hihonor.wallet`（不再有卡片上报行）— ui_r10_home.xml, round10_home.png
2) 关于页：**PASS** — Miuix About: 图标/名称/`版本 v0.2.6`; 支持的钱包行：`荣耀钱包 com.hihonor.wallet · 已安装（本机 11.0.3.305）` 与 `欧加钱包 com.finshell.wallet · 未安装` + `5.52.0`；链接行 `GitHub 仓库 sofa7320/Honor-Cards-Personality`。点击 GitHub 行 → 系统跳转确认 → GitHub（已安装 app 接管深链），系统日志 `capturedLink=https://github.com/sofa7320/Honor-Cards-Personality`，无崩溃 — round10_about.png, ui_r10_about.xml, round10_github.png
3) 右上角刷新：**PASS (loading)** — 点击刷新（content-desc 刷新）触发重新拉取，截到加载态；已是最新时缩略图不变；换面后刷新生效 — round10_refresh_loading.png / round10_refresh_after.png
4) 门禁卡换面回首页缩略图自动更新：**PASS** — 门禁卡换为相册照片保存后，回首页无需重启，门禁卡缩略图区域像素 hash 由 `900925…` → `f16908…` — round10_home_access_now_thumb.png vs round10_home_after_access2_acc_thumb.png
5) 组件 tab 荣耀设备仅荣耀组件：**PASS** — 显示 `荣耀钱包 · com.hihonor.wallet` + `站点状态标签`（保持原样/隐藏），无任何 OPPO 组件；开关可下发隐藏 — round10_components.png

## C) OPPO 适配静态验证（无设备）— PASS，均标注「静态验证（无设备）」
- APK dex 字符串：`com.finshell.wallet`、`OppoFaceHookEngine`(77 处)、`CircleNetworkImageView`、`FinBigCardFaceView`、`CardCoverSettingUtil`、`loadBitmapSync`、`getLocalImgPath`、`CardTheme`、`WalletScope`、`activeWalletPackage`、`fail-open`(2 处)；方法短名精确 MUTF-8 字符串 `e1 / i1 / g1 / h1 / j1` 均存在（H-A e1/i1 勘误在包内）
- fail-open：classes3 含 `fail-open` 标记；OPPO engine 类存在于 classes3，未发现对荣耀引擎类的替换/破坏
- 荣耀 hook 未回归：`PROVIDER_CACHE`、`hide_reapply`、`CARD_PERSONALITY_HIT`、ANR guard `getter hook re-entered` 均在 dex（classes4/6），设备侧荣耀功能实测 PASS
- 未在设备上安装/启动 `com.finshell.wallet`

## D) 版本 / 发布
- `aapt2 dump badging`: `versionCode='1' versionName='0.2.6'` — PASS
- 本地产物冻结 sha 见开头；**repo 无 v0.2.6 相关 git tag**（`git tag --list` 无匹配）
- GitHub Releases API（https://api.github.com/repos/sofa7320/Honor-Cards-Personality/releases）最新 release 仍为 **v0.2.5**，**无 v0.2.6 Release** — 符合本轮「不 tag / 不 Release」

## E) 清理
config/exports cleared; wallet restarted on original face (round10_final_original.png); `svc power stayon false`; LSPosed 10/10 enabled.

## 备注 / 限制
- 本轮 0.2.6 的 UI 与 hook 改动未触及字体目录/描边渲染，字体语言标注与「数值越界/非法」用例沿用 v32 结论（其余项均在 v35 重新实测）。
- 刷新按钮在「数据已最新」时视觉上无变化属正常（重新拉取后缩略图一致）；换面场景已验证加载态与更新。
- OPPO 相关仅静态证据（无 OPPO 设备），未验证运行时行为；EID/桌面小组件/直传 Bitmap 卡面按 module-dev 文档为 out of scope。
