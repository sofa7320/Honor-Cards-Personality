# 小米智能卡（`com.miui.tsmclient`）卡面替换移植笔记 — v0.2.13

> 状态：**静态移植完成，Debug/Release 构建均通过；无小米真机，未做设备验证。**
> 参考实现：`参考仓库/cardface-miuix-main`（MiamBao，GPL-3.0，已验证机型 Xiaomi 15 Pro / HyperOS 4 /
> LSPosed 2.2.1 + 小米智能卡）。本模块为 GPL-3.0 同类项目，移植思路与 hook 点来自该参考。
>
> 本文与代码是配套文档：代码里 `XiaomiFaceHookEngine` / `XiaomiBridge` 的注释引用本文的章节。

## 0. 构建证据（本次交付）

| 项 | 结果 |
|---|---|
| `pwsh -File scripts/build.ps1`（`:app:assembleDebug`） | **BUILD SUCCESSFUL**，`app/build/outputs/apk/debug/app-debug.apk`，37,028,627 B，sha256 `ECBCDA69899571F3E5BFC437050D43D5B9E71947332CFA7400BFC64B26426EBD` |
| `pwsh -File scripts/build.ps1 -Tasks ":app:assembleRelease"` | **BUILD SUCCESSFUL**，`app/build/outputs/apk/release/app-release.apk`，27,540,594 B，sha256 `ABD16179CA466F666942D5D196E11C0548ED240C5C6FD8124812EDC79B34260E` |
| 版本 | `versionName = "0.2.13"`、`versionCode = 1`（未变；**未** push/tag/release） |
| APK `META-INF/xposed/` | `module.prop`（minApi 101 / targetApi 102 / staticScope=true）、`java_init.list`（`com.cardpersonality.honor.hook.Xposed102Entry`）、`scope.list` 含 **7 个包**（新增 `com.miui.tsmclient`） |
| DEX 校验 | release APK 的 `classes2.dex` 含 `XiaomiFaceHookEngine` / `XiaomiBridge` / `loadGeneric` / `MIUI_UTIL` 符号 |
| 编译告警 | 新增的 `hook/xiaomi/*.kt` 无编译告警（`-Xjvm-default` deprecation 与其余告警均来自既有文件） |
| 未改动 | Honor `FaceHookEngine`、OPPO `OppoFaceHookEngine`/`OppoBridge` 引擎内部；未改 UI 文件 |

变更面（1 新增目录 + 3 个新文件 + 8 处最小改动）：

```
新增  app/src/main/java/com/cardpersonality/honor/hook/xiaomi/XiaomiFaceHookEngine.kt
新增  app/src/main/java/com/cardpersonality/honor/hook/xiaomi/XiaomiBridge.kt
新增  work/re/xiaomi/XIAOMI-PORT.md（本文）
改动  hook/Wallets.kt（注册 xiaomi）
改动  data/WalletScope.kt（xiaomi 域 / usesLoopbackBridge）
改动  hook/XposedDispatch.kt（KIND_XIAOMI 分支）
改动  FaceRenderer.kt（仅 FaceGeometry：XIAOMI_SPEC）
改动  data/FaceRepository.kt（2 处 OPPO 判断 -> usesLoopbackBridge）
改动  ui/util/DiagnosticExporter.kt（小米引擎汇总一行 + 私有 helper）
改动  app/src/main/resources/META-INF/xposed/scope.list（+1 行）
改动  app/src/main/AndroidManifest.xml（<queries> +1 行）
改动  app/build.gradle.kts（versionName 0.2.12 -> 0.2.13）
```


---

## 1. 结论（TL;DR）

| 项目 | 结论 |
|---|---|
| 新钱包 kind | `xiaomi` / `com.miui.tsmclient` / 「小米智能卡」 |
| 替换原理 | 面板渲染 JPEG → 通过 loopback bridge 把字节送进卡包进程 → hook 把字节写进卡包**自己的**私有目录 → 把卡包 Glide 的 URL 参数替换成 `file://` 本地路径（与参考一致，且**不需要 root**） |
| 主 hook | ① Glide `RequestManager`/`RequestBuilder` 单参数 `load(...)`；② dex 扫描 `com.miui.tsmclient.util` 里 `static (String)->Object/String` 的 CustomGlideUrl 工厂（参考 slot 0）；③ dex 扫描持有 `CardInfo` 参数的方法（参考 slot 10，只读增强+超级岛替换） |
| 卡片枚举 | 以「卡面 URL」为单位（参考模型一致）；identity 优先取 URL 的 `cardId/id/...` 参数，否则用归一化 URL 的哈希；type 由 query/path 推断，CardInfo 观察可覆盖；name 优先 CardInfo，否则 URL basename，否则「小米+类型」 |
| 预览当前卡面 | 上报 `faceUrl`（http CDN URL），面板沿用现有下载缓存路径；本引擎不做 Bitmap 抓图 |
| IPC | 复用现有 loopback bridge（`PanelBridgeServer`，127.0.0.1:47131..47135，与 OPPO 同协议）；**没有**使用 ContentProvider / LSPosed RemotePreferences |
| 几何 | 320:202 设计单位 = 参考验证过的 **960×606** 输出（`FaceGeometry.XIAOMI_SPEC`） |
| 生效时机 | 保存后需要重新进入卡包卡片页（或重启卡包）才会重新走 Glide 加载；与参考「一键重启卡包」一致 |
| 元素隐藏（KEEP/HIDE） | 小米**未实现**（`XIAOMI_CATALOG` 为空、引擎不处理 hidden 列表），不影响卡面替换 |

---

## 2. 参考实现调查（`参考仓库/cardface-miuix-main`）

### 2.1 模块形态

- LSPosed 模块，libxposed API 101，入口 `com.dsh.miuix.cardface.xposed.HookEntry`。
- `HookEntry.onPackageLoaded` **提前**装 hook（首屏图片加载前生效），`onPackageReady` 兜底重装。
- 只处理 `com.miui.tsmclient`，其它包直接返回。
- 我们的模块是 API 102，入口 `Xposed102Entry.onPackageReady` → `XposedDispatch`；没有
  `onPackageLoaded` 时机，因此把「快 hook（Glide）」放在 install 同步执行，把「慢的 dex 扫描」放到
  后台线程（避免卡包启动线程抖动/ANR）。

### 2.2 Hook 点（参考 `xposed/MethodLocator.kt` + `xposed/HookEngine.kt`）

参考用 **DexKit 2.3.0** 在卡包进程内按「包名 + 源文件名 + 方法签名」定位混淆方法，并缓存到卡包
`files/cardface.method.*.v2.txt`：

| slot | 定位 | 行为（参考） |
|---|---|---|
| 0 `SLOT_URL_FROM_ARG` | 包 `com.miui.tsmclient.util`，源文件 `CustomGlideUrl.java` 的类，`static (String)->Object` 工厂（混淆后名字如 `i`） | 取 arg0 当卡面 URL → `replaceUrl()`；**直接返回替换值**（`Object` 返回类型，返回 String 被 Glide 接受） |
| 1 `SLOT_REPLACE_ARG` | 包 `com.bumptech.glide`，源文件 `RequestManager.java` 的类，`void(String)`（或任意单参数） | 取 arg0 → 替换**参数**后再 `proceed(newArgs)`；同时把所有单 String/Object/Uri/File 的 `load` 重载都装上（多装无害） |
| 10 `SLOT_SUPER_ISLAND` | 包 `com.miui.tsmclient.util`，`String x(com.miui.tsmclient.entity.CardInfo)` | 超级岛显示 URL，返回替换值（`super_land` 开关打开时才装） |

参考的 `replaceUrl` 纯逻辑（`xposed/UrlMapper.kt`）要点：

1. **小图过滤**（仅 MIUI）：丢弃含 `w270h480`、`/door-card-img/logo/`、`/Mibi/` 的 URL；
2. `normalizedUrl()`：去掉 fragment、`/w960h606` 这类尺寸路径段、路径中的 hash/UUID 段，
   query 里丢掉 `w/h/width/height/scale/q/sign/ts/...` 等易变参数，但**保留 id/cardId**（否则交通卡
   列表图标会归一化成同一张图）；
3. 映射查找：先精确 URL，再归一化 URL，最后「唯一同名文件」兜底；
4. 替换值：http(s) 原样；否则视为图库文件名 → `content://<module>.images/images/<name>`；
   若卡包目录 `files/images` 已有同名文件则直接 `file://`；否则（兼容模式）请求 root 复制后返回
   `file://`，复制失败退 `content://`；
5. 记录卡面 URL：只有「卡面专用」hook 点（slot 0/10）记录，通用 Glide（slot 1）**不记录**，避免把
   图标/横幅当卡面。

### 2.3 图像输出

- 固定 **960×606 PNG**，**烘焙 40px 圆角**（4×4 超采样抗锯齿），Lanczos3 缩放 + sRGB 线性光，
  可叠加交通卡标志（京津冀/上海/泉城通/银川）。默认 `roundCorners = true`
  （`ui/Bridges.kt`：`TARGET_W=960, TARGET_H=606, CORNER_RADIUS_PX=40`）。
- 参考的 UI 说明「按卡面的圆角把四个角裁掉，避免露出直角」，即**小米卡包不负责裁圆角**，
  所以参考把圆角画进 PNG。

### 2.4 落盘与下发（参考）

- 模块 App 把图写到**自己**的 `files/images/<name>`；
- 卡包进程 hook（`HookEngine.copyToWallet`）用 **root**（`RootShell.copyIntoDir`，`su -c cp`）把图
  复制到 `/data/data/com.miui.tsmclient/files/images/<name>`（SukiSU 下用
  `/proc/1/root/data/data/...` 前缀），然后把 `file://` 路径交给 Glide；
- root 不可用时退 `content://`（部分卡包版本不认）；README 明确「复制步骤需要 root」。

### 2.5 设置 / IPC（参考）

- 卡包进程用 `getRemotePreferences("settings")` / `getRemotePreferences("card_urls")`（LSPosed
  RemotePreferences）读写设置与卡面 URL 集合；
- 模块 App 自带一个 `ContentProvider`（authority = `<modulePackage>.preference`，
  `core/HookPrefsProvider.kt`，实现 LSPosed RemotePreferences 协议：query 返回 key/type/value，
  type 0..6，StringSet 以 `\`、`;` 转义），并 `notifyChange` 通知 hook；
- `core/RemotePrefsBridge.kt` / `core/SettingsRepository.kt` 是 App 侧与 hook 侧的封装；
- 另有一份「映射快照」用 root 写到卡包 `files/cardface.map`，冷启动无 IPC 时可读。

**我们没有照搬 provider/RemotePreferences**：需要在 Manifest 增加导出 provider（与 lead/honor
路径耦合），且 LSPosed remote prefs 需要额外 opt-in；而 loopback bridge 已在 OPPO 上验证可用，
并且对「卡包进程无 Context」的次要进程也有效。见 §3.5。

### 2.6 参考的已知行为

- 用户必须先把卡包的卡面页「浏览一遍」让 hook 记录到 URL，之后才能给该卡选图；
- 换图后需要「一键重启卡包」（或重新进卡片页）才生效；
- 只改展示图，不碰支付/余额/刷卡逻辑；不对小米卡包版本做兼容承诺。

---

## 3. 本模块实现（v0.2.13）

### 3.1 注册（改动面最小）

| 文件 | 改动 |
|---|---|
| `hook/Wallets.kt` | 新增 `XIAOMI = "com.miui.tsmclient"`、`KIND_XIAOMI = "xiaomi"`、`XIAOMI_TARGET`、`XIAOMI_PACKAGES`、`isXiaomi()`、`usesLoopbackBridge()`；`ALL` 增加小米；`kindOf()` 返回 `xiaomi` |
| `data/WalletScope.kt` | 新增 `XIAOMI`、`isXiaomi()`、`usesLoopbackBridge()`；`walletOfFileKey()` / `labelOf()` / `kindOf()` 识别小米 |
| `META-INF/xposed/scope.list` | 追加 `com.miui.tsmclient` |
| `AndroidManifest.xml` `<queries>` | 追加 `com.miui.tsmclient`（targetSdk 36 包可见性，面板才能检测到安装状态） |
| `hook/XposedDispatch.kt` | `KIND_XIAOMI -> XiaomiFaceHookEngine.install(...)`（Honor/OPPO 分支原样保留） |
| `FaceRenderer.kt` `FaceGeometry` | 新增 `XIAOMI_SPEC = FaceSpec(320f, 202f, 13f, XIAOMI)`，`specFor()` 三分支 |
| `data/FaceRepository.kt` | `pushToWalletBridge()` / `clearCard()` 的「OPPO 专用」判断改为 `WalletScope.usesLoopbackBridge()`，小米复用同一套「渲染 JPEG + 推 config/face」路径 |
| `ui/util/DiagnosticExporter.kt` | 诊断报告新增「小米智能卡引擎」汇总（bridge HELLO 的 armed/hooks/cards/replace 计数） |
| `app/build.gradle.kts` | `versionName = 0.2.13`（versionCode 仍为 1） |

新增文件：`hook/xiaomi/XiaomiFaceHookEngine.kt`、`hook/xiaomi/XiaomiBridge.kt`。

> UI 侧（`ui/WalletCatalog.kt` 的 `WalletPackages.XIAOMI` / `XIAOMI_CATALOG`、`ui/HomeScreen.kt` 的
> 小米检测提示）已由 ui-dev 预先落地；本移植**没有改 UI 文件**。`AboutScreen` 里
> `XIAOMI_SUPPORTED_VERSION = ""` 会显示「待适配」，发版前建议 ui-dev/lead 改成实际验证版本号（见 §7）。

### 3.2 已实现的 hook（本模块）

安装顺序：Glide 同步装（快），小米混淆目标在后台线程 dex 扫描后装（慢，避免启动抖动）。

| # | 目标 | 定位方式 | 行为 |
|---|---|---|---|
| 1 | `com.bumptech.glide.RequestManager#load(*)`、`com.bumptech.glide.RequestBuilder#load(*)`（单参数，类型 String/Object/Uri/File） | 固定类名（Glide 库类名稳定） | before：取 arg0 URL → 命中已配置卡面则把**参数**换成 `file://`（String/Uri/File 按原类型构造；Object 模型换成 String，Glide 接受）；未配置则只记录 |
| 2 | `com.miui.tsmclient.util.*` 中 `static (String) -> java.lang.Object` 或 `-> String` 的方法（参考 slot 0 的 CustomGlideUrl 工厂） | **dex 扫描**卡包 dex 类名（`DexPathList.dexElements[].dexFile.entries()`，过滤前缀 `com.miui.tsmclient.util`，上限 1200 类/12 个 hook），按参考签名匹配 | before：URL 命中已配置卡面 → `param.setResult("file://...")`（参考已证明 Glide 链接受 String 模型）；未配置只记录 |
| 3 | `com.miui.tsmclient.util.*` 中参数包含 `com.miui.tsmclient.entity.CardInfo` 的方法（参考 slot 10 超级岛） | 同上 dex 扫描（上限 12 个 hook） | after：反射读 `CardInfo` 的 id/name/type/faceUrl（名字关键字匹配 + 每类方法缓存 + 每实例提取缓存）；有 faceUrl 时记录卡片；若方法返回 String 且命中配置则把**返回值**换成 `file://`（类型安全） |

**为什么不用 DexKit**：参考引入了 `org.luckypray:dexkit`（含 native `libdexkit.so`）。本模块保持
「纯 Kotlin + libxposed API 102」，不新增 native 依赖；用 dex 类名扫描 + 反射签名匹配复刻参考的
两个 DexKit 查询。代价：`CustomGlideUrl` 的真实类名可能被混淆（参考也是靠 DexKit 的
`source("CustomGlideUrl.java")` 定位），我们只能按「包 + 签名」猜，故该项是**尽力而为**，失败时
只记录 miss（fail-open），此时仍有 Glide 通用 hook 兜底。

**设计取舍 / 安全边界**

- 只处理 **http(s)** URL；`file://`、`content://`、本地缓存路径一律放过（避免把 Glide 自己缓存
  目录的临时文件当成卡面）。
- 只对「像卡面」的 URL 做替换/记录（见 §3.3），因为通用 Glide hook 会看到图标、横幅、头像等。
- `looksLikeCardFace()` 参考了参考实现的小图过滤：直接丢弃 `w270h480`、`/door-card-img/logo/`、
  `/Mibi/`；丢弃文件名含 `logo/icon/banner/splash/avatar/qrcode/guide/advert/promotion/...` 的；
  丢弃尺寸段宽度 <240 的小图；要求「有图片扩展名 / 有 ≥480×300 的尺寸段 / 有图片处理参数」之一。
- 任何异常都被吞掉，卡包原 URL 不被改动（fail-open）。
- 多进程：每个卡包进程都装（与 OPPO 一致，不做进程门控）；类不存在时 hook 自然 miss。

### 3.3 卡片枚举 / 身份 / 类型 / 名称

参考的模型是「一张卡 = 一个卡面 URL」，本模块沿用，但补上我们这个 App 需要的 `(type, identity)`
稳定键：

- **identity**：优先 URL query 中 `cardid/card_id/cardno/card_no/instanceid/instance_id/recordid/
  record_id/bizid/biz_id/id` 的第一个可用值 → `m_<value>`；否则 `u_<sha256(normalizedUrl)[0..16]>`。
  归一化规则复刻参考 `UrlMapper.normalizedUrl`（去尺寸段、hash/UUID 段、易变 query，保留 id 类参数），
  因此卡包换签名/换尺寸后仍能命中。
- **type**：query 的 `cardtype/type/biztype`（`1`=bank、`5`/`33`=transit、`6`=access、
  door/access→access、bank→bank、campus→campus）→ 路径关键字（door/bank/campus/ebike）→ 默认
  `transit`；`CardInfo` 观察到的 type 可覆盖。
- **name**：`CardInfo` 的 `getCardName/getName/...`（含关键字兜底）→ URL basename（可读且非纯 hash）
  → 「小米 + 类型名」（如「小米交通卡」）。
- **faceUrl**：被拦截的原始卡面 URL（面板用它下载当前卡面做预览）。
- 过滤「添加卡」占位（名称含「添加」、identity 为 0/-1/null）。

上报 JSON 与 Honor/OPPO 完全同构（`type/identity/name/subtitle/lastSeen/appElementIds/faceUrl/
wallet/walletKind/walletLabel`），因此 `FaceRepository.reportCards()`、`report.json`、面板卡片列表、
编辑器（`EditorTarget.walletPackage`）**无需任何修改**即可显示小米卡片。

### 3.4 卡面替换（不需要 root）

1. 面板 `FaceRepository.saveCard()` → `renderExport()`（`FaceGeometry.XIAOMI_SPEC` → 320×202×scale，
   典型 960×606）→ `pushToWalletBridge()`；
2. `PanelBridgeServer` 通过 loopback 广播
   `PUT\t<pkg>|<type>:<id>>\t<version>\t<base64 jpeg>\t<base64 faceUrl>`（以及全量 `CONFIG`）；
3. 卡包进程 `XiaomiBridge` 收到后把字节写到
   `/data/user/0/com.miui.tsmclient/files/cardpersonality/faces/<sha256(fileKey)[0..24]>_v<version>.jpg`
   ——**这一步在卡包进程内执行（模块以卡包 uid 运行），所以不需要 root**，也不需要像参考那样从
   模块 App 目录跨 uid 复制；
4. hook 命中卡面 URL 时返回 `file:///data/user/0/com.miui.tsmclient/files/cardpersonality/faces/...`
   —— 与参考验证过的 `file://` scheme 一致（参考放在 `files/images/`，我们放自己的子目录，Glide 的
   FileLoader 对路径无特殊限制）；
5. 面板删除自定义卡面（`clearCard`）→ `DEL` → 本地文件删除 → 下次 Glide 加载命中不到 → 保留原图。

**生效时机**：替换发生在「卡包下一次执行 Glide load」时。已停留在卡片页的旧图不会自动刷新——重新
进入卡片页（或重启卡包）即可；与参考 README 的「一键重启卡包后生效」一致。`CONFIG` 到达时引擎只做
日志，不做主动重载（我们没有可靠的 ImageView 注册表，参考也没有）。

### 3.5 IPC：为什么复用 loopback bridge

| 方案 | 结论 |
|---|---|
| ContentResolver 调面板 provider | ❌ 与 OPPO 同样的两个问题：targetSdk 36 包可见性导致 `Unknown authority`；卡包的 `:xxx` 次要进程没有 Application Context。Honor 走 provider 是因为它可用且历史路径已稳定 |
| LSPosed RemotePreferences / 参考的 `<module>.preference` provider | ⚠️ 可行（参考就是这么做的），但要在 Manifest 增导出 provider、双方都要实现 RemotePreferences 协议，且改动会与 Honor provider 路径、lead 的 Manifest 归并冲突 |
| 复用 `PanelBridgeServer` loopback（OPPO 已验证） | ✅ 面板侧零改动（协议相同），卡包侧只需新增独立客户端；无 Context 依赖、多进程可用、连接断开自动重连（2s）、未连接时 CARD/OPLOG 排队并在重连后 flush |

协议（与 OPPO 共用同一个 `PanelBridgeServer`，端口 127.0.0.1:47131..47135）：

```
wallet -> panel : HELLO <engineState json>
                  CARD <card json>            （单卡，去重队列）
                  CARDS <json array>          （REQ_CARDS 的应答 = 全量快照）
                  OPLOG xiaomi <action> <detail>
                  ORIGINAL <type> <id> <base64 jpeg>   （本引擎暂不产生）
                  ORIGINAL_INVALID <type> <id>
                  PONG
panel -> wallet : CONFIG <json>   PUT <fileKey> <version> <base64 jpeg> <base64 faceUrl>
                  DEL <fileKey>   REQ_CARDS   REQ_ORIGINAL <type> <id>
                  DEL_ORIGINAL <type> <id>   PING
```

`HELLO` 里带 `walletPackage: com.miui.tsmclient`、`walletKind: xiaomi`、`processName`、
`isMainProcess`、`armed`、`installedCount`、`glideHooks/factoryHooks/cardInfoHooks`、`cardCount`、
`replaceCount` 等计数；面板 `PanelBridgeServer.engineStateJson()` 按 `processName` 合并，诊断报告
用 `walletPackage` 过滤出小米进程（见 §3.6）。`reportVersion` 变化会让首页刷新（与 OPPO 相同）。

### 3.6 本地存储布局（卡包进程内）

```
/data/user/0/com.miui.tsmclient/files/cardpersonality/
  config.json                 面板 CONFIG 快照（cards/types 的 version），冷启动无面板时仍可解析
  faces/<sha256(fileKey)[0..24]>_v<version>.jpg   面板推来的替换图（file:// 来源）
  faces/urls.json             原始卡面 URL -> "<fileKey>|<文件名>"（URL 精确/归一化匹配，落盘）
  originals/<sha256(type:id)>.jpg                   预留（本引擎暂不抓图）
```

卡包内 `config.json` 与 `faces/` 的写入都是普通 `File` 写（同 uid），原子替换用 `tmp + rename`。

### 3.7 与既有数据/诊断的对接

- `FaceRepository.reportCards()` 直接吃 `CARD`/`CARDS`（`PanelBridgeServer` 已有逻辑），卡片带
  `wallet=com.miui.tsmclient` / `walletKind=xiaomi`；`WalletScope.walletOfFileKey()` 能把
  `<pkg>|<type>:<id>` 还原成小米域（存储键与 OPPO/Honor 不冲突）。
- 钱包检测：`FaceProvider.putWallets()` 遍历 `Wallets.ALL`（已含小米）→ 面板 `WalletProbe` /
  `WalletPackages.installed()` 都能看到安装状态与版本。
- 诊断报告：`ui/util/DiagnosticExporter.kt` §3 新增「小米智能卡引擎」一行，解析 bridge 合并 JSON
  中 `walletPackage == com.miui.tsmclient` 的进程心跳（armed/hooks/glide/factory/cardInfo/cards/
  replace）；同时 §4「钱包与开关状态」会列出所有小米卡片（type/identity/wallet/name/hasCustomFace）。
  运行时操作日志（`OpLog`）里小米引擎的来源标记是 `xiaomi`（`OPLOG\txiaomi\t...`）。
- 枚举兼容性：`Wallets.KIND_*` 与 `WalletScope.kindOf()` 只是新增分支，Honor/OPPO 的枚举值、存储键
  和 UI 分支未变（`CardModels.CardInfo.walletKind` 默认仍是 `honor`）。

---

## 4. 已知限制（无小米真机，全部未设备验证）

1. **未在小米真机 / 真实卡包版本上验证**：本机只有荣耀/欧加设备；参考实现的验证环境是
   Xiaomi 15 Pro / HyperOS 4 / LSPosed 2.2.1。首次上机需要按 §5 逐项确认。
2. **混淆类定位是启发式**：`CustomGlideUrl` 的真实类名未知，dex 扫描按「包 + `static (String)->Object`」
   签名匹配，可能 miss（此时只靠 Glide 通用 hook）或误命中 util 包里别的签名相同的方法（已限制返回
   类型为 Object/String 且只在 URL 像卡面时替换；但仍不排除误伤，日志会记录 `factory:` 标签）。
   首次上机请用 logcat 核对 `Xiaomi factory hook: <class>#<method>` 的类名是否像 GlideUrl 包装器。
3. **`CardInfo` 字段名未知**：id/name/type/url 的 getter 名是按「稳定实体类通常不混淆」假设 + 关键字
   匹配猜的；全 miss 时卡片仍能按 URL 枚举，只是名称退化为「小米交通卡」这类默认名、type 退化为
   URL 推断。真机日志 `cardinfo` 行会打印实际读到的值。
4. **圆角**：参考在 PNG 里烘焙 40px 圆角（默认开启），我们沿用本模块「不烘焙、由钱包裁剪」的约定，
   只有 `FaceGeometry.XIAOMI_SPEC.cornerRadiusDp` 影响面板预览圆角。若真机上小米卡片露出直角，
   需要给小米单独加「烘焙圆角」选项（建议 ui-dev/lead 决定，见 §7 契约）。
5. **预览当前卡面走 URL 下载**：若小米 CDN 卡面 URL 需要鉴权/签名过期，面板可能下载失败（此时
   卡片仍可编辑，只是「当前卡面」显示为空）。本引擎没有实现从 ImageView 抓 Bitmap（OPPO 那种
   `FaceCapture` 方案），如需要可作为 follow-up。
6. **元素隐藏（KEEP/HIDE）未实现**：`XIAOMI_CATALOG` 为空，引擎不处理 hidden 列表；面板不会显示
   可隐藏元素开关（与 ui-dev 现状一致）。
7. **生效需要重进卡片页**：见 §3.4；本移植不主动重载已加载的图片。
8. **多钱包同机**：`PanelBridgeServer` 的进程心跳表按 `processName` 合并，若同机同时有 OPPO 与小米
   进程连接，`engineStateJson()` 顶层字段取「最新心跳」的那条（这是既有设计）；诊断里用
   `walletPackage` 区分。`FaceRepository` 推送是广播，两个钱包都会收到 PUT 但只解析自己域下的
   fileKey，互不影响（各自写自己包目录）。
9. **未处理「超级岛」的其它形态**（如视频/动画卡面）；slot 10 只做 String 返回值替换。

---

## 5. 小米真机验证步骤（给有设备的同学）

前置：已 root + LSPosed（libxposed API 102+）；安装小米智能卡并至少有一张卡；安装本模块 0.2.13。

1. **作用域**：LSPosed 里启用「荣耀卡面工坊」，作用域勾选「小米智能卡」（`com.miui.tsmclient`）。
   确认 APK 内 `META-INF/xposed/scope.list` 含该包名（本版本已加）。
2. **注入验证**：
   ```bash
   adb logcat -c && adb shell am start -n com.miui.tsmclient/<launcher>
   adb logcat -s CARD_PERSONALITY | grep -E "XIAOMI|Xiaomi"
   # 期望：XPOSED102_READY pkg=com.miui.tsmclient ... wallet=xiaomi
   #       Xiaomi engine: glideHooks>=1
   #       Xiaomi factory hook: <类名>#<方法>   （后台 dex 扫描，约 1~4s 后）
   #       Xiaomi bridge: connected to 127.0.0.1:47131
   ```
   打开模块 App → 关于/诊断页「导出诊断报告」，报告 §3 应出现
   `小米引擎：com.miui.tsmclient armed=true hooks=... glide=... factory=... cardInfo=...`。
3. **卡片枚举**：在小米智能卡里来回浏览卡包列表 + 某张卡的卡片页/详情页（参考 README 的「把卡面
   浏览一遍」）。回到模块 App 刷新，应出现 1..N 张小米卡片（名称可能是「小米交通卡」等默认名）。
   诊断报告 §4 可见 `wallet=com.miui.tsmclient` 的条目；logcat 里应有
   `Xiaomi bridge: face stored` / `OPLOG xiaomi card`。
4. **替换卡面**：点一张小米卡片 → 选图/裁剪 → 保存并应用。日志期望：
   `bridge push-face`（面板）→ `Xiaomi bridge: face stored <key> v=N`（卡包）→ 重进卡片页后
   `replace-hit` / `replace` 行，卡面变成自定义图。
5. **恢复原图**：编辑器里「恢复默认/清除」→ 日志 `Xiaomi bridge: del-ok`、`del` → 重进卡片页恢复。
6. **不崩 / 不卡**：卡包冷启动、切后台、刷卡页反复进出，`logcat | grep -E "FATAL|ANR"` 无本模块
   栈；`Xiaomi engine install failed` 不应出现（即使出现也应不崩，只是不替换）。
7. **回归**：同机有荣耀/欧加钱包时，确认它们的卡面替换/上报不受影响（本移植未改它们的引擎）。

调参建议（若首次上机不生效）：
- 若 `factoryHooks=0`：说明 dex 扫描没匹配到 CustomGlideUrl 工厂，把
  `XiaomiFaceHookEngine.max factory` 放宽/日志里看 `miui-hooks classes=?` 与 `missed`；
  必要时把参考的 DexKit 方案作为 fallback（需评估 native 依赖）。
- 若 `cardCount=0` 但 Glide hook 有命中：`looksLikeCardFace()` 可能过滤掉了小米真实卡面 URL；
  抓 `observedUrls/skippedNonFace` 与 `url-recorded` 日志，按真实 URL 形态调整
  `looksLikeCardFace()`/`IMAGE_EXTENSIONS`/`NON_FACE_WORDS`。
- 若卡面替换后显示直角：启用圆角烘焙（见 §4.4）。

---

## 6. 回滚 / 排障

- 关闭 LSPosed 作用域里的「小米智能卡」即完全停用；模块对小米的所有写入都在
  `com.miui.tsmclient` 私有目录下（`files/cardpersonality/`），删除该目录即清空替换图/配置快照；
- 面板侧删除自定义卡面会推送 `DEL`，不会残留（除非卡包从未连接过面板，此时文件仍在但不会被引用）；
- 常见现象：
  - 卡片列表为空：先确认作用域 + 是否浏览过卡面页（URL 是唯一枚举来源）；看 `url-recorded` /
    `skippedNonFace` 计数判断是「没命中」还是「被过滤」。
  - 有卡片但替换不生效：看 `replace-hit` 是否出现；若只有 `url-recorded`，说明配置键不匹配
    （identity 不一致）——诊断报告 §4 的 identity 与卡包进程日志里的 identity 对比；
  - `bridgeConnected=false`：面板未启动/被冻结，或端口被占用（47131..47135）；面板侧诊断
    `bridge 状态` 行可确认。桥接会每 2s 重连。

---

## 7. 给 lead / ui-dev 的契约（本移植依赖 / 建议）

```
wallet package : com.miui.tsmclient
wallet label   : 小米智能卡
wallet kind    : xiaomi            (Wallets.KIND_XIAOMI / WalletScope.kindOf)
scope.list     : 已追加 com.miui.tsmclient
manifest       : <queries> 已追加 <package android:name="com.miui.tsmclient" />
geometry       : FaceGeometry.XIAOMI_SPEC = 320:202 设计单位 → 3x = 960x606 输出（参考验证尺寸），
                 cornerRadiusDp = 13（仅面板预览裁剪）
IPC            : 复用 PanelBridgeServer loopback（端口 47131..47135），无需新增 provider/manifest 权限
卡片上报        : 与 Honor/OPPO 同构 JSON（type/identity/name/faceUrl/wallet/walletKind）
生效时机        : 保存后重进卡包卡片页；UI 文案可沿用现有「请打开小米智能卡后返回本页刷新」
建议 ui-dev    : 1) AboutScreen WalletPackages.XIAOMI_SUPPORTED_VERSION 从 "" 改为真机验证版本号；
                 2) 若真机发现卡面直角，考虑给小米加「烘焙圆角」选项（FaceRenderer 需支持按
                    walletPackage 烘焙 40px/960 圆角，或复用参考的 BitmapPipeline 思路）；
                 3) XIAOMI_CATALOG 的 elementIds 留空即可（本版本不实现元素隐藏）。
```

---

## 8. 参考文件清单（调查时读过）

- `README.md`（功能、root 说明、验证环境）
- `docs/HOOK-SPEC.md`（原版 hook 规格、RemotePreferences 协议、DexKit 查询、缓存文件）
- `app/src/main/kotlin/com/dsh/miuix/cardface/xposed/`：`HookEntry.kt`、`HookEngine.kt`、
  `MethodLocator.kt`、`UrlMapper.kt`、`ProviderPrefs.kt`、`SettingsRepository.kt`
- `app/src/main/kotlin/com/dsh/miuix/cardface/core/`：`HookPrefsProvider.kt`、`RemotePrefsBridge.kt`、
  `HookCache.kt`、`CardSync.kt`、`WalletApps.kt`、`Model.kt`、`BitmapPipeline.kt`、`ImageStore.kt`
- `app/src/main/kotlin/com/dsh/miuix/cardface/ui/Bridges.kt`（960×606 + 40px 圆角输出参数）
- `app/src/main/resources/META-INF/xposed/`（module.prop / java_init.list / scope.list）
- `imaging/src/test/.../CardBuildTest.kt`（960×606、40px 圆角、超采样抗锯齿的验收断言）
