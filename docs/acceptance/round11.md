# ACCEPTANCE ROUND 11 — v0.2.7 (v36-final)

Device: Honor 192.168.31.10:5555（仅荣耀钱包+面板；**未安装/未启动 OPPO 钱包**）。
Delivery artifact: `work\module\poc\card-personality-v36-final.apk`, 36,641,925 B, sha256 **1CB4A318187502223B78BCB12D8DF0A7701C79FD49D61BC354725025122C56B0**（versionCode=1 / versionName=0.2.7）。安装后先启动一次面板，再重启钱包。

## A) 荣耀回归（快速 PASS）
| item | verdict | evidence |
|---|---|---|
| 版本/首页 | **PASS** — dumpsys versionName=0.2.7；Home `v0.2.7` + `识别钱包包名：com.hihonor.wallet` | round11_home.png, ui_r11_home.xml |
| 编辑/保存/卡面替换 | **PASS** — 元素编辑（旋转改动）保存后钱包详情页渲染自定义卡面 | round11_detail.png |
| 双击电源冷启动 | **PASS** — force-stop 面板+钱包 → 锁屏 → `input keyevent 26 26` → `SwipeCardActivity` 显示自定义卡面 | round11_doublepress.png / crop |
| 旋转范围 ±270 | **PASS** — 滑杆两端 -270° / 270° | ui_r11_rotL2.xml, ui_r11_rotR2.xml |
| 数值输入 | **PASS** — 旋转数值弹窗“范围：-270° ~ 270°”、预填 270、取消/确定 | round11_num_dialog.png, ui_r11_num.xml |
| 自适应渲染 | **PASS** — 编辑器说明 `导出 2016 × 1248（6x）` | ui_r11_rows.xml |
| ZIP 导出 | **PASS** — 新包 `岭南通·羊城通_20261116-134301.zip` 3,062,753 B | Download/honor_cards_personality |
| 组件隐藏 / 字体 / 描边 / 选中延迟 | **carried PASS**（v36 仅加诊断/多钱包检测，未改 UI 渲染/hook 行为；round-10 v35 实测通过） | ACCEPTANCE-round10.md |
| 冷启动 ANR | **PASS** — 2 次冷启动均为 **0 real ANR/FATAL** | logcat |

## B) 诊断报告（PASS）
- About 页「诊断」区 → **导出诊断报告** → Toast `已导出诊断报告：/sdcard/Download/honor_cards_personality/logs/diagn…`（截图 round11_diag_toast.png；About 行副标题提示同一路径）。
- 落盘文件：`/sdcard/Download/honor_cards_personality/logs/diagnostic_20261116-134053.txt`（5,886 B），已拉到 `work\device\logs\r11_diagnostic.txt`。
- 内容六区段齐全（实测）：
  1. **基本信息**：`0.2.7（versionCode 1）`、包名、构建 debug、Android 17（API 37）、品牌 HONOR、型号 PTP-AN10、指纹、ABI arm64-v8a、语言/时区、1280×2800/560dpi
  2. **Root 环境**：`未检测到 su 可执行文件（未获取 root 权限）`；Magisk/KernelSU/APatch 目录 false；`which su`（无输出 exit=1）；`su -c id`（超时 3s，执行失败：Cannot run program "su"）；`getenforce` Permission denied；`getprop` 键值（release/sdk/security_patch/brand/model/device/boot state/verity/timezone 等）
  3. **LSPosed / 模块状态**：/data/adb 相关路径 false、`lspctl status` 执行失败（明确标注）；**provider ping：pong**；`provider wallets` JSON（6 候选钱包 + installed/version + activeWalletPackage）；`provider getReport` 截断 JSON（3 卡）
  4. **钱包与开关状态**：activeWalletPackage、6 个候选钱包 installed/version 行、自动吸附开关、上报卡片数量、3 条卡片摘要（type/identity 打码/wallet/name/hasCustomFace/hidden/stickers）
  5. **模块操作记录（最近 ≤300 条）**：8 条，格式 `时间|来源|操作|详情`（wallet|detect、wallet|report、config|save、render|export-ok 等）
  6. **异常文件摘要**：crash 目录（空）、最近 ANR trace（需 root，执行失败明确标注）
- **su 不可用场景不崩溃且明确标注** — 本次面板进程实际处于无 su 状态（设备已 root 但 app 进程 PATH 无 su），报告完整生成、六区段齐全，即为负路径实测。
- OpLog 文件：`files/oplog.txt` 存在，19 行（≤300），格式 `时间|来源|操作|详情`，含 config|save / render|export-ok（已留证 work\device\logs\r11_oplog.txt）。

## C) OPPO 检测（静态验证，无设备）— PASS
- **Manifest `<queries>`**（aapt2 xmltree）：`com.hihonor.wallet`、`com.finshell.wallet`、`com.coloros.wallet`、`com.oplus.wallet`、`com.oneplus.wallet`、`com.nearme.wallet` 六个候选包全部声明；**未使用 QUERY_ALL_PACKAGES**。
- **代码/字符串**（frozen APK dex）：`scan_wallets`、`wallets`、六个候选包名、`oplog.txt`、`OppoFaceHookEngine`、`getter hook re-entered` 均在；面板空态文案 `未检测到已安装的受支持钱包` 在 classes5.dex（静态确认；荣耀设备因已检测到受支持钱包，空态未实际触发）。
- **About 支持钱包列表实测**：荣耀钱包 `com.hihonor.wallet · 已安装` 11.0.3.305；欧加 `com.finshell.wallet · 未安装` 5.52.0；ColorOS/OPPO/OnePlus/欧加旧版 均为 `未安装`（截图 round11_about.png，dump ui_r11_about.xml）。
- 未在荣耀设备安装/启动 `com.finshell.wallet`；OPPO 真机检测与运行时 hook 不在本轮可验证范围（无设备）。

## D) 版本 / 发布
- `aapt2 dump badging`: `versionCode='1' versionName='0.2.7'` — PASS
- 本轮由 module-dev 冻结、verifier 仅验收；**verifier 未创建 tag/Release**。截至检查 GitHub Releases API 最新仍为 **v0.2.5**，无 v0.2.7 Pre-release（Lead 计划在本报告后打 tag 发 Pre-release）。建议发布后确认 Pre-release 资产 sha256 = `1CB4A318…`（或 Lead 等价标注）。

## E) 清理
config/exports cleared；wallet 重启回原卡面（round11_final_original.png）；`svc power stayon false`；LSPosed 10/10 enabled。

## 备注
- 字体/描边/组件隐藏/选中延迟为 carried（v36 改动仅 OPPO 包可见性/候选注册表/OpLog/About 诊断入口，未触及荣耀渲染与 hook 路径；round-10 v35 已全项实测 PASS）。
- 诊断报告的「Root 环境」显示 su 不可用属 app 进程环境事实（非设备无 root），负路径行为符合要求。
