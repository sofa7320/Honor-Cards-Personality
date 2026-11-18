# Changelog / 更新日志

本文件记录「荣耀卡面工坊」的所有重要变更，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循语义化版本（`versionCode` 固定为 1，不随版本递增）。

## 0.3.0 预发布系列（0.2.6 – 0.2.18）

> **0.3.0 正式发布时**，将把 0.2.6～0.2.12 的所有变更统一汇总为「自 0.2.5 以来的变更」写入 0.3.0 的发布说明与 CHANGELOG，届时本区间的逐版本条目保留为历史记录。

## [Unreleased]

（暂无）

## [0.2.22] - 2026-11-18

### 修复
- **文字元素开启发光/描边后错位、发光半径为 1 时文字被截断、发光半径调节不生效**：
  - 离屏发光渲染的 baseline 改为与正常绘制一致（bitmap 内第一行 `padY - ascent`，多行累加 lineHeight），开/关发光与描边时文字全局位置完全一致，不再上移/错位；
  - 水平/垂直 padding 分开计算（发光半径、描边宽度、斜体外扩分别计入），半径 1/8/40、单行/多行、斜体/下划线均不再截断；
  - 发光/描边相关字段全部进入缓存键，半径每步变化都会重建离屏图层，预览光晕随滑块即时变化。

### 修复（补充）
- **发光半径调大后发光消失**：预览与导出的发光改为显式 `BlurMaskFilter` 光晕 pass（不再依赖大半径不可靠的 `setShadowLayer`），离屏 padding 取 `radius*3 + strokeWidth + 8`，大半径不再被裁剪；离屏渲染失败时降级直接绘制文字（不会整段消失）。
- **移除「已获取到卡面」Toast**：钱包原图自动替换底图时保持静默，仅保留进度文案。

## [0.2.21] - 2026-11-18

### 修复
- **文字元素的发光与描边效果失效**：文字缓存键补齐 `color/alpha/stroke/strokeWidth/strokeColor/glow/glowColor/glowRadius/visible`，样式变化立即重建；预览改为把文字+发光/描边离屏渲染到软件 Bitmap（不再依赖硬件 Canvas 的 `setShadowLayer`），拖动帧复用；导出仍走软件 Canvas，光晕/描边可见。
- **门禁卡偶现「钱包尚未渲染该卡」**：无原图时不再阻断编辑——生成中性占位底图（按卡类型/卡名渐变）可直接选图/加元素/保存；打开编辑器自动请求钱包重抓（`RECAPTURE_ORIGINAL`），每 1.5s 重试共 15s + 从钱包返回时再试，原图到达后自动替换底图。模块侧补充 ACCESS 原图来源：wallet view 抓取、内置资源（`faceId`）、钱包私有缓存文件，均上报 source（view/resource/file/url）。

## [0.2.18] - 2026-11-18

### 修复
- **编辑页返回主页出现双层滑动动画**：`AnimatedContent` 的 targetState 改为稳定 screen key（`editor:<fileKey>` / `__about__` / `__home__`），转场方向只依赖 key；Home 快照/数据更新不再触发转场，返回只播放一次「编辑页→主页」滑动。

### 性能
- 预览拖动不再触发重组：composition 只读父级配置，手势 live state 仅在 **draw 阶段**读取（Canvas 绘制 + 选择框 live 重算），松手一次性提交。
- 10x 保存内存进一步下降：`renderToFile` 结束后清理 `ScaledFaceCache`，图片缓存 LRU 64→**32MB**，避免解码/缩放/输出三份大 bitmap 长期共存。

## [0.2.17] - 2026-11-18

### 修复
- **从编辑页返回主页闪烁**：返回时不再重建整页/显示 loading；用 `HomeSnapshot` + `LazyListState` 直接渲染并后台静默刷新（数据相等不重组、滚动位置保留），仅内容变化的卡片行重新解码缩略图。
- **编辑页栏目切换动画**：图片 / 元素 / 组件 / 输出 改为即时切换（移除淡入/位移/尺寸动画），保留各栏独立滚动位置。

### 性能
- 预览手势继续走本地 live state（松手一次提交，不每帧重组编辑器）+ 文字测量缓存（measureKey）。
- 10x 保存内存优化：背景 decodeCap 降为 `min(输出长边, 3072)`、元素 cap 3072、图片缓存 LRU 96MB→**64MB**，减少 10x 保存中间 Bitmap 驻留。

## [0.2.16] - 2026-11-18

### 性能
- **Lanczos3 重写为流式低内存实现**：不再对整图分配 `FloatArray(sw*sh*4)`（此前 4K 图约 192MB、逐像素 pow）；改为逐行处理 + sRGB/线性 256/4096 项 LUT + 两级降采样（先整数 box 预缩到目标 2× 内再卷积）；异常自动降级 bilinear。
- **按用途分级**：预览/缩略图不再使用 Lanczos（采样解码 + bilinear）；Lanczos 仅用于最终导出；新增 `ScaledFaceCache`（源文件 stamp + 目标尺寸，LRU 48MB）；解码按输出尺寸 cap、图片缓存改为 96MB 按字节预算。
- **编辑器预览交互优化**：拖动/缩放/旋转只更新预览自有的 live state（不再每帧重组整个编辑器），松手一次性提交；避免每帧文字测量；预览纹理上限 1280/1024。
- 实测（4K 背景 + 元素贴纸）：3x 保存 render 1,649/489ms → **296/228ms**，10x 保存从失败 → **2,162ms 成功**；编辑器 PSS 224,902KB → **~178,000KB**；预览拖动帧率 +50%，p50 21–25ms → **9–10ms**。

### 修复
- 高分辨率背景下保存偶发「背景丢失」：渲染解码失败重试；输出做纯色/空白抽样校验，失败拒绝写坏卡面并提示。
- 导入背景图后预览偶尔不刷新：状态键加入文件 stamp，解码失败重试/降级。

## [0.2.15] - 2026-11-17

### 修复
- **导入背景图片偶尔不显示**：预览状态键加入图片文件戳（mtime+size），覆盖导入即时刷新；解码失败增加重试与降级路径，不再静默。
- **高分辨率背景 + 元素图片**：元素图按最终输出像素解码（清晰度恢复正常）；渲染耗时优化（背景按输出尺寸解码、Lanczos 仅必要层各一次、缓存复用、毫秒级 `render|timing` 日志）；渲染失败会重试并在输出纯色/空白校验失败时拒绝写坏卡面（提示保存失败）；高分辨率背景内存受控。
- **OPPO 卡片重复**：同一张卡（wallet+type+faceUrl 相同）不再出现两条记录，优先保留 AID identity（`A0000…`），丢弃数字卡号/URL 回退 identity。
- **OPPO 面板列表缩略图空白**：report 卡有 `faceUrl` 且无本地原图时，首页缩略图异步下载该 URL 并缓存；钱包侧新增 Fin* 自绘容器/根视图抓取（反射 Bitmap 字段或 `view.draw`）作为兜底。

### 变更
- **导出画质滑块不再显示关键点灰点**；保留倍率数值、预计输出尺寸与重置。
- 版本 0.2.15，`versionCode` 固定 1。

## [0.2.14] - 2026-11-17

### 新增
- **编辑页「输出」栏**：集中「重置为钱包原始卡面」与 **导出画质 1x–10x** 滑块（显示当前倍率与预计输出尺寸，按卡持久化）。
- **页面切换滑动转场**：页面之间采用 Miuix 风格的横向滑动动画（前进/后退方向相反）。

### 修复
- **OPPO 银行卡在部分机型不显示**：占位卡过滤不再误伤 identity 回退为 -1 的真实银行卡；对无法取到卡号的卡用可用字段派生稳定 identity 并正常上报。
- **OPPO 原生/第三方自定义卡面无法预览**：钱包渲染卡面时抓取当前显示图并上报 ORIGINAL，编辑器可显示钱包当前卡面（含钱包原生自定义、oplus-card-customizer 自定义）。

### 变更
- **首页缩略图降采样**（低分辨率解码，降低内存占用），不影响编辑与导出画质。
- **移除首页 1.5s 轮询刷新**：改为 App 打开、编辑返回首页时各刷新一次，保留手动刷新按钮。
- **卡面图像缩放改用 Lanczos3**（高质量重采样），用于渲染、导出与缩略图。
- 版本 0.2.14；`versionCode` 固定 1。

## [0.2.13] - 2026-11-17


### 新增
- **小米智能卡适配**（`com.miui.tsmclient`）：新增小米钱包引擎；Glide 加载入口 hook + dex 扫描定位卡面工厂与 CardInfo 方法；复用 loopback bridge 推送渲染图，钱包侧写入自身私有目录并以 `file://` 替换（无需 root）；卡面几何 320:202 → 960×606。参考 [卡面工坊](https://github.com/MiamBao/cardface-miuix)。

### 修复
- **荣耀门禁卡卡面（预览丢失/无法替换）**：门禁卡走独立入口 `xi#j`，按 `AccessCard.faceId`（≥1000 / ≤-1000 为原生自定义）与 `cardPicUrl` 决定卡面；ACCESS 的 faceUrl 不再恒为 null，并上报 `nativeFaceKey`；`xi#j` 改为 before hook，按模型字段确定性抓取/替换（不再依赖 ImageView.drawable，避免抓到旧图/占位图）；新增跨进程重抓通道（`RECAPTURE_ORIGINAL`），钱包原生自定义或模块重置后预览可正确刷新。
- **OPPO 保存链路重构**：由「面板广播、钱包单向接收」改为参考实现的**钱包进程内 ServerSocket**（127.0.0.1:47121–47123，`PING/URLS/PUT/DEL/CLEAR` 请求/响应）+ 面板 `WalletFaceClient`（重试）；按 URL hash 落盘并提供 `resolveFaceByUrl`；新增 `oppo|server-up/put-ok/put-fail/replace-hit/miss` 诊断。

### 变更
- **软件更名为「药做卡面」**（包名不变）。
- **README 精简重写**。
- **开源协议由 Apache-2.0 更换为 GPL-3.0**（LICENSE / NOTICE / CONTRIBUTING 同步）。

## [0.2.12] - 2026-11-17


### 修复
- **OPPO 卡面保存不生效（第二轮）**：钱包渲染时使用的卡片 identity 可能与面板保存时不一致，导致替换始终未命中。新增**原始卡面 URL 精确匹配兜底**：bridge `PUT` 增加 `faceUrl`（Base64 第 5 段），钱包维护 URL→本地卡面映射（去 query/scheme/basename 别名），identity 未命中时按 URL 命中替换为 `file://`；新增 `oppo|put-ok/config-ok/replace-hit/replace-miss` 诊断日志。
- **荣耀门禁卡「双向保存」预览**：进入编辑器或从钱包返回面板时，对未自定义卡面的卡去抖重新抓取钱包当前卡面（包含钱包原生自定义的门禁卡卡面）；重置后恢复钱包原生卡面且不覆盖钱包自身文件。`FaceCapture` 增加失效/重抓/全量失效（2s 去抖）。

## [0.2.11] - 2026-11-17


### 变更
- **API 全量迁移到 libxposed API 102**：删除 legacy 入口（`assets/xposed_init`、manifest legacy meta-data）与 `xposed-stub`/`de.robv.android.xposed` 包；hook 兼容层改为内部 `com.cardpersonality.honor.hook.api` 包（内部使用 libxposed API 102）；仅保留 `META-INF/xposed/{module.prop,java_init.list,scope.list}` 现代元数据。

### 修复
- **OPPO 卡面保存不生效**：面板保存时 wallet 作用域误用荣耀钱包（诊断日志 `wallet=com.hihonor.wallet`）；现按卡片所属钱包（`com.finshell.wallet`）保存，并通过 bridge `CONFIG/PUT/DEL` 推送，钱包侧本地配置命中后以 `file://` 本地卡面替换。
- **OPPO 占位卡**：「添加卡/添加交通卡/添加门禁卡」等占位项不再作为真实卡片上报/显示。
- **荣耀自定义/重置后预览丢失**：钱包原生自定义卡面或模块「重置卡面」后，原图快照失效并重新抓取（新增 `refreshOriginal`），预览不再空白。
- **编辑器**：元素旋转时虚线选择框跟随旋转；自动吸附与拖动冲突导致的「一拽一拽」已修复（拖动中不牵引、松手吸附/滞回）。

## [0.2.10] - 2026-11-16

> OPPO 系改动为静态移植实现，无需实机验证（维护者无 OPPO 设备）。

### 新增
- **OPPO 卡面能力移植**（参考朋友模块 `oplus-card-customizer` / com.crrashh.opcardcustomizer）：
  - `CircleNetworkImageView` 14 个卡面入口方法全部覆盖（setImageUrl/setImageBitmap/e1/g1/j1/k1/d1/f1/h1/i1/a1/b1/setImageUrlByTemp/A0）。
  - 覆盖 `FinBigCardFaceView`、`FinCustomCardView`、`FinSmallCardFaceView`、`FinUINfcCardFaceView` 容器方法；"是卡面"判定含 View 向上 8 层 parent 匹配容器。
  - 11 个卡数据模型 getter 只读观测，记录 url→(aid/appCode/cardType/cardNo/name/balance)，按 getCardType() 与 appCode/aid 规则判定卡类型并上报（面板卡片显示名称/类型）。
  - 替换采用朋友已验证的 `file://` 钱包私有目录方案：按长边≤1600、JPEG q88 递减≤700KB 落盘到钱包 `filesDir/oppo_faces/`，将参数替换为 file:// 路径交钱包 Glide 加载（保留圆角/缩放/缓存）；维护钱包侧 `cardpersonality_oppo_map.json` 缓存，provider 不可用时仍可替换。
  - 诊断增加 widgetHits / fileReplacements / recordedModels；配置变更后通过 WeakReference 视图注册表重新加载。
- **关于页「开源支持」**新增 `oplus-card-customizer` 行（白字标题 + 灰字说明，点击打开仓库）。

## [0.2.9] - 2026-11-16

> OPPO 系改动为静态实现，**无需实机验证**（维护者无 OPPO 设备）。

### 新增
- **关于页「开源支持」**：Miuix（https://github.com/compose-miuix-ui/miuix）与 LSPosed（https://github.com/LSPosed/LSPosed）两行，白字标题 + 灰字说明，可点击跳转。
- OPPO 引擎诊断新增 `pairCount` / `captureCount` / `faceSource`（url/file/bitmap/none），OpLog 增加配对/抓图/本地读文件/预热记录。

### 修复（OPPO/欧加钱包 5.52.0 读取链路）
- 补上 OPPO 侧「抓原图」：无自定义卡面时 `FaceCapture.schedule(view, identity)`，面板可获取钱包原生卡面（此前 OPPO 引擎从不抓图，面板只能下载 URL，导致「读不到卡面」）。
- 新增 L0 配对 hook：`QuickCardNfcCardView#h/setNfcCardData`、`cardframe.e#bindData`（`mCardImage`）、`DragonflyConsumeCardsFrameController#T/N`、`b0.a#d`、`FinUINfcCardFaceView#showCardImg`（`mThemeImg`）→ `viewToIdentity` 映射并立即 `recordCard` 上报，无需 URL 反查。
- 进程内读取本地卡面：`file://` / 绝对路径归一化后由钱包进程（uid）直接 `File.readBytes()` 回传，解决面板无权限读取钱包私有目录的问题。
- 卡类型映射修正：按 `NfcCard.getCardType()`（5/6/10/9/15/1/2/8/12/14/21/7/99/33）映射到 6 类，未知类型不上报（此前默认 transit）。
- 替换卡面改为「URL → 钱包 cacheDir 本地 JPEG 路径」交给钱包 Glide 重新加载，保留原生圆角/缩放/缓存（此前直接 `setImageBitmap` 导致非首页卡片直角）。
- 修正 `CircleNetworkImageView` hook 参数个数（g1=4/h1=2/j1=2，补 setSvgUrl/r1）、配置后台预热、URL 归一化 + 双表身份映射。

### 界面
- 关于页「链接」小标题更名为「反馈」。
- 关于页软件图标放大，同步提高合成位图分辨率（与桌面图标一致）。

## [0.2.8] - 2026-11-16


### 新增
- **OPPO 引擎诊断**：provider `engine_state` / `getReport.engine` / `files/oppo_engine.json`（walletPackage、installedCount、attemptedCount、observedUrls、cardCount、replaceCount、missed[]），OpLog 记录 OPPO 初始化/hook 安装/URL 观察（脱敏）。
- **OPPO 卡面入口修正**：按名字反射 hook `CircleNetworkImageView#A0(String)`、`#y0(String,boolean)`、`#x0(String)`、`#setSvgUrl(String)`、`#F0(String)`；URL-only 入口用本地缓存 JPEG 文件路径作为参数（不跳过原调用，Glide 可直接加载本地文件），有 View 的入口保持跳过+自解码 Bitmap。
- **卡片上报健壮性**：扫描 15 个卡数据类 + 多候选 getter/字段，identity 优先级 aid→appCode→cardNo→cardId/id/uid，兜底 `url:<sha24>`，确保面板能看到 OPPO 卡片。
- 亮度 hook 补充 `CardCoverSettingUtil#fetchBrightnessUnderTextView*`。

### 修复
- 关于页：系统侧滑/返回键与顶部箭头均返回软件主页（不再回到桌面）；图标改为真实 launcher 图标；「GitHub 仓库」「发送错误报告」去掉灰色说明小字。
- 关于页「支持的钱包」：只保留荣耀钱包、欧加钱包两项，仅显示支持版本与包名（不显示本机安装版本），删除「检测到未适配钱包」区块。
- 首页状态行去掉「识别钱包包名：」前缀，仅显示包名。

## [0.2.7] - 2026-11-16


### 新增
- **关于页「导出诊断报告」**：一键收集设备信息、root 环境（su 路径/`su -c id`/getprop/SELinux）、LSPosed 与模块状态（lspctl）、钱包与开关状态（已安装钱包/激活钱包/卡片配置摘要，卡号仅保留后 4 位）、模块操作记录（最近 300 条）、异常摘要，写入 `/sdcard/Download/honor_cards_personality/logs/diagnostic_yyyyMMdd-HHmmss.txt`；无 root 时明确标注且不崩溃。
- **OpLog 操作日志**：面板与钱包进程关键操作（保存/应用/ZIP 导入导出/字体导入/刷新/钱包检测/hook 失败等）环形记录，供诊断报告读取。
- **`scan_wallets`**：枚举 label 含「钱包/Wallet」的已安装应用，用于展示「检测到但未适配的钱包」（不自动 hook）。

### 修复
- **OPPO/欧加设备上「未检测到已安装的受支持钱包」**：补齐 Android 11+ 包可见性声明 —— manifest `<queries>` 声明荣耀 + 5 个欧加/OPPO 候选包（com.finshell.wallet / com.coloros.wallet / com.oplus.wallet / com.oneplus.wallet / com.nearme.wallet）；钱包候选注册表与 provider `wallets` 同步扩展；面板状态行/关于页/空态提示更新。

## [0.2.6] - 2026-11-16

> 本轮按要求**不发布 GitHub Release**（仅源码/构建）。

### 新增
- **多钱包支持**：荣耀钱包（`com.hihonor.wallet`）+ OPPO/欧加钱包（`com.finshell.wallet` 5.52.0）。配置 key 增加钱包维度（`<包名>|<类型>:<身份>`，旧配置按荣耀兼容）；provider 新增 `wallets` 钱包检测接口。
- **OPPO 卡面适配（静态逆向，无实机验证）**：`CircleNetworkImageView` 的 6 个 String 加载方法为主替换点，`Fin*` 卡面控件作为离线补图/刷新辅助，`CardCoverSettingUtil#loadBitmapSync` 保证文字黑白色按自定义卡面计算；overlay 默认 KEEP，功能控件永不隐藏；EID/桌面小组件不覆盖（fail-open）。
- **按钱包区分卡面几何**：荣耀 336:208/圆角 12dp；OPPO 328:202/圆角 14dp（预览/导出/说明文字自动切换）。
- **关于页**：Miuix 关于页（图标/名称/版本、支持钱包与版本行、GitHub 仓库、发送错误报告、致谢/隐私）。
- **首页「识别钱包包名：com.xxx」**：替换原「卡片上报…」行。

### 修复
- 首页右上角刷新按钮真正生效（loading 态 + 重拉上报/缩略图，失败 Toast）。
- 门禁卡更换卡面后首页缩略图不更新：config version watcher + 缩略图缓存键 + 位图缓存 evict，返回首页即更新，无需重启。
- 组件 tab 按钱包包名显示对应元素开关（荣耀设备不再出现无关钱包组件）。

## [0.2.5] - 2026-11-16

### 新增
- **数值精准输入**：点击滑杆右侧数值弹出 Miuix 输入弹窗，显示允许范围；支持合法值、越界夹取、非法输入提示；覆盖字号/粗细/尺寸/旋转/透明度/发光/描边宽度等全部滑杆。
- **自适应渲染分辨率**：按底图原始像素选择导出尺寸 —— 照片最高 2016×1248（6x），钱包原生 672×416 卡面按 672×416（2x）渲染，避免双重重采样造成的模糊；预览下方显示实际导出尺寸。
- **以当前卡面为底图无损抓取**：按原生分辨率保存（小图 PNG、大图 JPEG q100），不缩放、不二次压缩。

### 变更
- 旋转范围由 ±360° 调整为 **-270° ~ +270°**（滑杆/手势/导出/吸附一致）。
- 滑杆磁吸减弱（普通 0.018、旋转 0.007），保留吸附感但可精准控制。

### 修复
- **双击电源键冷启动显示原始卡面**：钱包进程内配置/卡面磁盘缓存 + Provider 失败回退 + pending 根延迟重试（300/800/1500/2500ms）+ 配置到达即时重应用。
- 图片贴纸解码长边 768→1536，200% 放大更清晰。

## [0.2.4] - 2026-11-15

### 修复
- **长图/大图自由摆放**：彻底移除贴纸拖动的 `cx/cy` 硬性范围限制（旧版仅允许画布 -0.5~1.5），
  无论图片多长、放大到多少，都可以把任意部分拖到卡面任意位置；预览、保存、钱包导出的位置完全一致。

### 新增
- **字体语言标注**：字体选择弹窗在每个字体名后标注「（西文）」或「（中文）」；
  默认项显示「Sans Serif（西文，默认）」，导入字体显示「文件名（自定义）」。

## [0.2.3] - 2026-11-14

### 新增
- **旋转**：范围 -360° ~ +360°，吸附 0/90/180/270/360（±11°，松手在 ±15° 内精确补齐），
  双指旋转累计 >9° 才解锁（防误触），解锁与吸附均提供震动反馈。
- **尺寸滑杆**：图片贴纸「宽度」更名为「尺寸」，默认 50%，范围 5%~200%，
  双指缩放与滑杆共用同一上限。
- **滑杆手感**：关键点磁吸停顿；关键点震动只在到达时触发一次；滑到两端各提示一次震动。
- **字体名称**：字体选择显示原族名（Sans Serif / Serif / Monospace / Cursive / Honor Sans CN / Noto Sans CJK 等）。
- **新元素置顶**：新增文字/图片贴纸插入层级最上层。

### 修复
- **仅重命名元素后无法保存**：比较逻辑补齐 `name` 等字段，重命名后保存按钮立即可用。
- **描边出现在字形内部**：改为「先描边、后填充」，描边只保留在字形外轮廓。
- **组件隐藏不生效**：钱包原生元素（如「已出站」标签）隐藏失败 —— 修复 hook 查找范围（窗口根/祖先/延迟重应用/配置变更重应用），并支持取消隐藏即时恢复。
- **选中延迟**：选中高亮/参考线移至独立 Overlay Canvas，选中不再触发卡片内容重绘；文字测量缓存扩大。
- 预览中选中文字描边出现偏移重影：预览改用与导出相同的 Paint 渲染管线（先描边后填充）。

## [0.2.2] - 2026-11-13

### 新增
- **卡面 ZIP 导入**：从本模块导出的 ZIP 恢复卡面配置、源图、贴纸与自定义字体。
- **自动底图**：预览处于「钱包当前卡面」占位状态时，添加元素会自动把当前卡面落为底图。
- **滑杆点击定位**：点击轨道即可跳到对应数值（保留拖动）。
- **真实震动**：Honor 系统忽略第三方 HapticFeedback 常量，改用系统 Vibrator（EFFECT_TICK）。

### 修复
- 移除编辑器右上角多余的导出图标（导出入口保留在图片页）。
- 吸附力度更轻：阈值 1.2%、拖动牵引约 20%、松手 0.5% 内精确落位。
- 层级方向修正：列表靠上 = 渲染最上层，重叠点击优先选中顶层。
- 字体弹窗改为可滚动列表，高度约屏高 48.7%。
- 描边保留填充并支持彩色轮廓。
- 页面/标签切换改为纯淡入，窗口背景主题化，消除闪白。
- 选中态隔离，切换元素不再整页重组。

## [0.2.1] - 2026-11-10

### 修复
- 自定义字体导入上限提升至 32 MiB，并给出明确失败原因。
- HEX 颜色输入支持全角字符归一化。

## [0.2.0] - 2026-11-08

### 新增
- 文字样式：粗细（100–900）、斜体、下划线、发光、描边、字体选择。
- 元素吸附（可开关）、图片水平/垂直翻转。
- 页面与标签切换动画。
- 文字/图片贴纸透明度、旋转。

## [0.1.0] - 2026-11-05

### 新增
- 首个可用版本：荣耀钱包卡面替换（显示层 hook）、图片导入、文字/图片贴纸、Miuix 编辑面板、
  卡面预览与实时保存。

[Unreleased]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.18...HEAD
[0.2.18]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.17...v0.2.18
[0.2.17]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.16...v0.2.17
[0.2.16]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.15...v0.2.16
[0.2.15]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.14...v0.2.15
[0.2.14]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.13...v0.2.14
[0.2.13]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.12...v0.2.13
[0.2.12]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.11...v0.2.12
[0.2.11]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.10...v0.2.11
[0.2.10]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.9...v0.2.10
[0.2.9]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.8...v0.2.9
[0.2.8]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.7...v0.2.8
[0.2.7]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.6...v0.2.7
[0.2.6]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.5...v0.2.6
[0.2.5]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.4...v0.2.5
[0.2.4]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/sofa7320/Honor-Cards-Personality/compare/v0.1.0...v0.2.0
