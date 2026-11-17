# 朋友 OPPO 卡面模块逆向（com.crrashh.opcardcustomizer 1.0.0）→ 移植到我们模块

对象：`D:\Files\dsh work\card-personality\opcardcusatomizer-v1.0.0-signed.aPk`
- package **com.crrashh.opcardcustomizer**，versionName **1.0.0**，versionCode 1
- sha256 **F6B7EF41F03F361910635BFECAE57C05005F1BCD6434E08C22003F7EEAD808CE**，1,881,379 bytes
- minSdk 26 / targetSdk 37 / compileSdk 37；1,402 个 dex 类（JADX 反编译 2,320 java，55 个失败但不影响主逻辑）
- 技术栈：**libxposed API（io.github.libxposed，非 legacy XposedBridge）** + **DexKit（libdexkit.so）** + Kotlin + Jetpack Compose Material3
- LSPosed 元数据（META-INF/xposed）：
  - `java_init.list` = `com.crrashh.opcardcustomizer.Hook`
  - `module.prop` = `minApiVersion=101`, `targetApiVersion=102`, `staticScope=true`
  - `scope.list` = `com.finshell.wallet`
- Manifest：permissions = `INTERNET` + 自定义 DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION；`<queries>` 声明 `com.finshell.wallet`；MainActivity exported（MAIN/LAUNCHER + `de.robv.android.xposed.category.MODULE_SETTINGS`）；`io.github.libxposed.service.XposedProvider` exported。
- 应用名：**欧加卡包助手**；描述：*"自定义 ColorOS NFC 卡片的卡面，支持本地图片与网络图片"*。
- 反编译参考源码已复制到 `work/re/oppo/friend-src/`：
  `Hook.java`（含 Companion/Target）、`MainActivity.java`、`b.java`、`lc.java`(钱包侧配置/桥数据)、`xc.java`(UI 侧配置)、`xj.java`(TCP 客户端；R8 合并大文件)、`j4.java`(钱包侧桥服务器命令处理)、`ja.java`/`ia.java`(桥服务启动)、`k.java`(URLS 同步)、`tc.java`(保存图片协程)、`ha.java`/`kc.java`(卡片条目模型)、`fk0.java`(重置)、`of.java`/`fp.java`(onResume 清理/调试写入)、`g2.java`(进程判断 lazy)、`yc.java`(类型列表)、`strings.xml`、`AndroidManifest.xmltree.txt`。

---

## 1) Hook 常量与全部映射（逐字）

```java
public final class Hook extends XposedModule {
    @Deprecated public static final int MAX_DEOPTIMIZE = 400;
    @Deprecated public static final String TAG = "OpCardCustomizer";
    @Deprecated public static final String TARGET_PACKAGE = "com.finshell.wallet";

    // 类 → 方法名集合（locateTargets 只取：方法名匹配 && paramCount>0 && 第一个参数类型为 java.lang.String）
    private static final Map<String, Set<String>> TARGET_CLASSES = {
      "com.finshell.finui.widget.imageview.CircleNetworkImageView":
          {"setImageUrl","setImageBitmap","e1","g1","j1","k1","d1","f1","h1","i1","a1","b1","setImageUrlByTemp","A0"} // 14 个
      "com.finshell.finui.widget.atomic.FinBigCardFaceView": {"setCardFaceUrl"},
      "com.finshell.finui.widget.atomic.card.FinCustomCardView": {"setCardFaceUrl"},
      "com.finshell.finui.widget.atomic.FinSmallCardFaceView": {"setImageBitmap","setCardFaceUrl"},
      "com.finshell.finui.widget.atomic.card.FinUINfcCardFaceView": {"showCardImg"}
    };

    // 卡面容器类（isCardFace 判定：ImageView 向上最多 8 层 parent 的类名）
    private static final Set<String> CARD_FACE_CONTAINERS = {
      "com.finshell.finui.widget.atomic.FinBigCardFaceView",
      "com.finshell.finui.widget.atomic.card.FinCustomCardView",
      "com.finshell.finui.widget.atomic.FinSmallCardFaceView",
      "com.finshell.finui.widget.atomic.card.FinUINfcCardFaceView"
    };

    // 数据层 getter（locateTargets：0 参 + 返回 java.lang.String）→ 只记录（recordOnly=true）
    private static final Map<String, Set<String>> CARD_MODEL_GETTERS = {
      "com.nearme.wallet.db.NfcCard": {"getCardImg","getNewCardImg","getLocalImgPath"},
      "com.nearme.wallet.db.NfcCardDetail": {"getCardImg","getCardImgUrl","getLocalImgPath"},
      "com.nearme.nfc.domain.transit.rsp.CardDetailRsp": {"getCardImg","getNewCardImg"},
      "com.nearme.pay.domain.rsp.CardDetailRspVo": {"getCardImg"},
      "com.nearme.pay.domain.rsp.PayCardInfo": {"getCardImg"},
      "com.finshell.wallet.supercard.net.rsp.CardInfo": {"getCardImg"},
      "com.nearme.wallet.db.CarInfo": {"getCardImg"},
      "com.nearme.nfc.domain.door.rsp.CardTheme": {"getCardImg"},
      "com.nearme.wallet.entrance.bean.EntranceCard": {"getCardImg"},
      "com.nearme.iccoa.net.VerifyIssuerCardRsp": {"getCardImg"},
      "com.nearme.wallet.nfc.bus.net.BackupCard": {"getCardImg"}
    };

    private static final List<String> NAME_GETTERS = {"getCardName","getDisplayName","getName","getNickName","getCardNickName"};
}
```
- 类型枚举（`defpackage.yc.a`）：`["transit","door","bank","qr","other"]`。
- 配置/UI 常量（`defpackage.xc`）：
  - 图片扩展名白名单 `a = {"png","jpg","jpeg","webp","svg"}`
  - 排除文案 `b = ["添加卡","添加银行卡","添加交通卡","添加门禁卡","添加车钥匙","去开通","立即开通","开通卡片","查看更多","查看全部","更多卡片"]`（`xc.e` 用于过滤非卡面文本）
  - SharedPreferences 名 `opcard`；键：`card_urls`(换行列表)、`name_<url>`、`type_<url>`、`balance_<url>`(long)、`localimg_<url>`(文件名)、`pending_remove`(换行列表)、`needs_push`(bool)
  - 图片上限 **716800 bytes (700 KiB)**；文件名 = `card_<UUID.nameUUIDFromBytes(url.getBytes(UTF_8))>.jpg`（模块 App 私有 files/images）
- 本地桥端口（`defpackage.t00.b`）：**{47121, 47122, 47123}**（127.0.0.1）
- 数据模型：`defpackage.ha` / `kc`（url, name, type, balanceCents）。
- `Hook.Target`：`{MethodData data; String label; boolean cardFaceWidget; boolean recordOnly}`。

---

## 2) onPackageReady 流程（完整）

```java
public void onPackageReady(PackageReadyParam p) {
  if (!p.getPackageName().equals("com.finshell.wallet")) return;
  String apkPath = p.getApplicationInfo().sourceDir;
  ClassLoader cl = p.getClassLoader();
  Context app = currentApplication();                      // ActivityThread 反射
  if (app != null) { lc.b(app); ja.a(app); }               // 初始化钱包侧配置 + 启动桥
  else { startBridgeWhenApplicationReady(); }              // 每 100ms 重试最多 10s
  List<Target> targets = locateTargets(apkPath);           // 失败→空；空则"模块不生效"直接 return
  for (Target t : targets) {
     hook(t.getData().getMethodInstance(cl)).intercept(chain -> interceptCall(chain, t));
  }
  deoptimizeCallers(cl, targets);                          // 见下
  log("初始化完成 framework=... api=...");
}
```
- **locateTargets(apkPath)**：`DexKitBridge.create(apkPath)`（懒加载，libdexkit）：
  1. `TARGET_CLASSES`：`findClass(className$default(key))` → `singleOrNull()`；遍历该类 methodData：`方法名 in value && 参数个数>0 && trim(第一个参数类型名)=="java.lang.String"` → 加 Target(label=`SimpleName#method/paramCount`, `cardFaceWidget = CARD_FACE_CONTAINERS.contains(className)`, `recordOnly=false`)。
  2. `CARD_MODEL_GETTERS`：同名类查找；`方法名 in value && 参数个数==0 && 返回类型=="java.lang.String"` → Target(label=`SimpleName#getter()`, `cardFaceWidget=false`, `recordOnly=true`)。
  - 找不到类只 log(5)，不中断；典型总 target 数 ~20-30。
- **安装 hook**：libxposed `hook(method).intercept(chain -> interceptCall(chain,target))`；每个失败单独 log。
- **deoptimizeCallers(cl, targets)**：对 targets 按 `recordOnly` 排序（非 recordOnly 优先），对每个 Target 用 `MethodData.getCallers()` 逐个 `deoptimize(caller.getMethodInstance(cl))`，**总量上限 MAX_DEOPTIMIZE=400**（超过 log 并停止）；目的：防 R8 内联导致 getter/setter hook 不生效。日志：`反优化调用方: 成功 N，失败 M`。
- **hit 日志**：`hitLogged: ConcurrentHashMap<label,AtomicBoolean>`，每个 label 首次命中 log(4) `首次命中 hook 点/数据层: label`。
- **cache/cacheVersion**（见 §3 resolve）：`ConcurrentHashMap<String,String> cache` + `long cacheVersion`；与 `lc.j` 比较，不等则 clear + 更新 → 避免每次查询都读文件。
- **RemotePreferences**：`j4` 里引用了 `io.github.libxposed.service.RemotePreferences.Editor` 的 lambda（`j4` case 3），但主流程实际用的是**自建 TCP 桥**；RemotePreferences 仅作为 libxposed 依赖的残留/备用路径。
- 每个进程都会执行 onPackageReady（staticScope）；`lc.f` 只在 `Application.getProcessName()==com.finshell.wallet` 时记录（`lc.a` 是 lazy `rxd`，`g2` case 11 判断进程），所以主进程记录、其他进程只替换（若命中）。

---

## 3) 三个 hook 的精确语义

### 3.1 interceptCall（widget 方法；recordOnly=false）
```java
Object interceptCall(Chain chain, Target t) {
  List args = chain.getArgs();
  Context ctx = resolveContext(chain);              // thisObject instanceof View ? view.getContext() : currentApplication()
  lc.b(ctx); ja.a(ctx);                             // 刷新配置/确保桥已启动
  Object[] newArgs = null;
  for (i in args) {
    String s = args[i] instanceof String ? ... : null;
    if (s != null && !s.isEmpty()) {
        if (xc.k(s) && isCardFace(chain, t)) lc.f(ctx, s, cardNameOf(thisObj), cardTypeOf(thisObj), balanceOf(thisObj)); // 记录
        String repl = resolve(ctx, s);                // url → "file:///data/data/com.finshell.wallet/files/images/card_<md5>.jpg" 或 null
        if (repl != null) { newArgs[i] = repl; }
    }
  }
  return newArgs == null ? chain.proceed() : chain.proceed(newArgs);
}
```
- **返回值不做替换**；只替换参数中的 String。替换值是 **file:// 本地文件路径**（不是 content://、不是 Bitmap）。
- 例外/兜底：任何异常 → `chain.proceed()`；无 Context → 放行；替换后调用失败 → 再 `chain.proceed()` 一次（原始参数）。

### 3.2 interceptDataGetter（模型 getter；recordOnly=true）
```java
Object interceptDataGetter(Chain chain, Target t) {
  Object ret = chain.proceed();
  if (ret instanceof String s && !s.isEmpty() && xc.k(s)) {
     lc.b(ctx); ja.a(ctx);
     lc.f(ctx, s, cardNameOf(thisObject), cardTypeOf(thisObject), balanceOf(thisObject));
     log 首次命中数据层
  }
  return ret;                                        // 永远返回原值
}
```
- 作用：把"钱包里实际出现的卡面 URL"登记到配置（url/name/type/balance），供 UI 列表展示。
- `cardNameOf`：用 `NAME_GETTERS` 反射 invoke，取第一个非空 String（trim）。
- `cardTypeOf`：`getCardType()` 命中 `"1"→bank`、`"5"→transit`、`"6"→door`、`"33"→qr`；否则回退：
  - `appCode.lowercase` 以 `oppodoor` 开头或包含 `door` → **door**；
  - `aid.uppercase` 以 `A000000632`/`D156` 开头，或 appCode 以 `kw_`/`tft_` 开头，或 appCode 含 `_bus` → **transit**；否则 **other**。
- `balanceOf`：`getAcctAmount`/`getBalance`，Number→long、String→double（含 '.' 则四舍五入×100 分），仅 >0 保留（用于 UI 显示"余额：x 元"/同类卡区分）。

### 3.3 isCardFace(chain, target)
```java
if (target.cardFaceWidget) return true;               // 目标类本身就是 4 个卡面容器之一
View v = thisObject instanceof View; if (v==null) return false;
for (i<8 && (p=v.getParent())!=null; i++, p=p.getParent())
    if (CARD_FACE_CONTAINERS.contains(p.getClass().getName())) return true;
return false;
```
- 所以：CircleNetworkImageView 的 hook 只有在**其祖先链 8 层内出现 FinBigCardFaceView / FinCustomCardView / FinSmallCardFaceView / FinUINfcCardFaceView** 时才被当作卡面（避免把 banner/icon 误判成卡面）——但注意：`resolve()` 替换本身**不检查** isCardFace，只检查该 url 是否已有映射（映射只会在 isCardFace + xc.k 通过时建立，所以等价）。
- `xc.k(url)` 判定（"这是图片 URL"）：必须 `http://`/`https://`；去掉 `?`/`#` 后小写；排除含 `advertisement` 或 `/ad/`；取最后一段，按扩展名必须在 {png,jpg,jpeg,webp,svg}；且不能含 `icon`/`logo`/`avatar`。

### 3.4 resolve（url → file uri；含缓存/版本失效）
```java
String resolve(Context ctx, String url) {
  if (cacheVersion != lc.j) { cache.clear(); cacheVersion = lc.j; }   // 配置变更则失效
  String c = cache.get(url);
  if (c != null) return c.isEmpty() ? null : c;
  File f = lc.c(ctx, url);                                            // 查配置 images[url] → files/images/<file>，存在且非空
  String uri = f == null ? null : "file://" + f.getAbsolutePath();
  cache.put(url, uri==null ? "" : uri);
  return uri;
}
```

### 3.5 配置/图片存储（钱包侧 `defpackage.lc`）
- 位置：**钱包自己的** `context.getFilesDir()`（`/data/data/com.finshell.wallet/files`）
  - 配置：`files/opcard_config.json`
  - 图片：`files/images/card_<md5hex(bytes)>.jpg`
- 配置 JSON 格式：
  ```json
  {
    "urls":   ["https://.../a.webp", ...],                 // 已记录/已替换的卡面 URL 全集
    "names":  {"<url>": "卡名"},
    "types":  {"<url>": "transit|door|bank|qr|other"},
    "balances": {"<url>": 12345},                           // 分
    "images": {"<url>": "card_<md5>.jpg"}                   // 已替换的图片文件
  }
  ```
- `lc.b(ctx)`：文件 mtime 变（或 filesDir 变）才重载；`lc.e(ctx,url,bytes)`：bytes 非空 → MD5(bytes) 命名写 `files/images/card_<md5>.jpg`（原子写），`images[url]=file`，`urls+=url`，删除同 url 旧文件，落盘 `h(ctx)`、`j++`（版本自增）；返回 true/false。`lc.g(ctx,url)`：删映射+删文件+落盘；`lc.a(ctx)`：清空所有图片+配置+桥缓存（`CLEAR` 命令）。
- `lc.f` 记录：仅当进程是钱包主进程（`g2` case 11 判断 `Application.getProcessName()`）时才写；写 `urls/names/types/balances`，有变化才落盘（`needs_push` 触发）。

---

## 4) 模块 App ↔ 钱包进程 的本地桥（核心机制，移植重点）

钱包侧（Hook 进程内）：`ja.a(ctx)` 只在主进程启动一次，`ia` 线程 bind `ServerSocket(127.0.0.1, {47121,47122,47123}, backlog=4)`，每个连接交给 `j4` 处理。**文本 + base64 协议**：

| 请求（按行） | 响应 | 作用 |
|---|---|---|
| `PING` | `PONG` | 探活（UI 判断钱包进程是否运行） |
| `URLS` | 每行 `url\tname\ttype\tbalanceCents`，最后 `END` | 拉取已记录卡片列表（UI 列表数据源） |
| `CLEAR` | `OK` | `lc.a` 清空全部自定义卡面 |
| `PUT <url>\n<base64(JPEG bytes)>` | `OK` / `ERR write failed` / `ERR bad request` | 写入自定义卡面（钱包 files/images，MD5 命名） |
| `DEL <url>` | `OK` | 删除某卡自定义卡面（`lc.g`） |
| 其它 | `ERR unknown command` | — |

模块 App 侧（`defpackage.xj`，端口同）：`J(url,bytes)`=PUT（500ms 连接超时/5s 读超时，失败换端口试 3 个）；`Q(url)`=DEL；`k` 协程=URLS 拉取并写 SharedPreferences（`card_urls/name_/type_/balance_`）；`PING` 用于在线判定（字符串 `msg_wallet_offline`="钱包进程未运行…"）。**上传大小上限**：`xc.i` 拒绝 `bytes.length==0 || >716800`；调试路径 `fp` 把图 decode 到 ≤1600px，JPEG q88 逐级 -8 直到 ≤700KB。

### UI 能力（MainActivity + Compose；Q4）
- MainActivity（`zf`/ComponentActivity）：`setContent(v70.i)` → Compose M3 界面；`onResume` 跑 `of`：
  1. 读 `pending_remove`：对每个 url `xj.Q`（DEL），成功则移除；
  2. `needs_push==true` → `xc.g` 重新 PUT 所有 `card_urls` 的本地图。
- 界面字符串（strings.xml）：`我的卡片` / `还没有识别到卡片` / 提示：**"请先打开钱包 App 并进入卡包页面后再刷新，以保证模块可以读取卡片"**；每卡：`卡面预览`、`选择图片`、`重置卡面`、`保存`、`刷新`、`导出卡面`/`导入卡面`；预览支持**拖动移动/双指缩放/双击复位 + 水平/垂直位置/缩放滑杆 + 自动吸附**；按类型显示 `银行卡/门禁卡/交通卡/乘车码/其他` + `余额：%s 元`；`卡面保存成功`/`已重置为默认卡面`/`卡面导出失败`/`卡面已导出到 %s`。
- 选图/缩放后：Compose 预览生成 Bitmap → JPEG → 走 `tc` 协程 `xc.i(context,url,bytes)`：
  - 写入**模块 App 私有** `filesDir/images/card_<UUID(nameUUID(url))>.jpg`；
  - `SharedPreferences opcard` 写 `localimg_<url>=文件名`；
  - `xj.J(url,bytes)` 立即 PUT 到钱包；失败 → `needs_push=true`，回主界面/刷新时重试。
- 重置：`fk0` → `xc.h(ctx,url)`：删模块本地图+pref，`pending_remove+=url`，并 `xj.Q(url)`；若 DEL 失败保留 pending_remove 下次重试。
- 导出/导入：导出 `ma` 写 zip（`face.jpg`=处理后的bytes，`source.jpg`=q92 原图）；导入 `ck0` 读 zip 条目（ZipInputStream）→ 解码 → `xj.J` 推送到钱包；注意导入会覆盖当前全部更改（描述文案）。
- 尺寸/格式限制：JPEG 重编码（q88 起，上限 700KB；源图 ≤1600px）；文件名/哈希：钱包侧按内容 MD5，UI 侧按 URL UUID。
- **不需要重启钱包**：桥在线即可推送；钱包侧 `lc.j` 版本自增使 Hook 缓存失效；但要**重新进入/刷新卡包页**才能让已显示的 ImageView 重新绑定（UI 文案也提示进卡包页刷新）。若钱包进程没运行 → PING 失败 → 提示 `钱包进程未运行`。
- Manifest/debug：MainActivity 支持 intent extras `opcard_debug_image`(本地路径)+`opcard_debug_url`(目标url) 直接调试写卡面（`fp` 路径）。
- 导出目录（`xj.E`）：API≥29 用 MediaStore `Download/OpCardCustomizer/`；<29 用外部 files 目录。

---

## 5) 兼容性/错误处理
- 无钱包版本号硬校验；完全依赖**类名/方法名**（DexKit 精确类名查找 + 方法名 + 参数形状）。因此只要 OPPO 保留这些类/方法名就生效（至少 5.52.0 实测）；任一类缺失 → 只漏该 hook，不崩（每 target 单独 try/catch；locate 为空则"模块不生效"并 return）。
- 失败降级：无 Context/异常/替换后调用失败 → 一律 `chain.proceed()`（原始行为），绝不破坏钱包。
- `deoptimizeCallers` 上限 400；超过只警告不再反优化。
- 多进程：staticScope；记录只在主进程（进程名判断）；其他进程也 hook 但一般不命中。
- 安全：只做本地文件替换 + 127.0.0.1 桥，不含网络请求（除图片原始 URL 由钱包自己加载）；导出走 MediaStore。
- 已知缺陷（我们移植时可改进）：
  1. 只检查 `CircleNetworkImageView` 祖先容器（8 层）判定卡面；但替换不校验 isCardFace → 若 UI 曾把某 url 记录为卡面（如其它容器的 setImageUrl 命中 isCardFace 误判）也会全局替换同 url。
  2. 亮度/文字颜色未处理：OPPO `CardCoverSettingUtil` 仍按**原 URL**下载图片计算黑白文字色，自定义深色/浅色卡面可能导致标题/标签/logo 颜色不可读（我们此前 OPPO 报告 §6 已指出；移植时应加 H-E）。
  3. 没有 recycle/刷新通知：配置变更后需用户重新进卡包页；我们可做 WeakReference 视图注册表主动重载。
  4. 导出/导入仅图片，身份仍按 URL；OPPO 升级换 CDN URL 后旧映射失效（我们应用 CardIdentity(type,id) 更稳）。

---

## 6) 移植方案（→ 我们 legacy XposedBridge + provider 架构）

### 6.1 能力映射表
| 朋友模块能力 | 我们的实现 | 备注 |
|---|---|---|
| libxposed `hook()` + DexKit 按类名找方法 | **legacy XposedBridge**：`Class.forName("com.finshell.finui.widget.imageview.CircleNetworkImageView", false, walletCL)` → `getDeclaredMethod`/遍历方法按名+参数形状匹配（无需 DexKit） | 我们已在 OPPO FINDINGS H-A/H-C 列出同样的类名；失败 fail-open |
| TARGET_CLASSES 14+4 方法 | hook 同一批方法（建议再加 `e1` 之外的 `g1/h1/i1/d1/a1/b1/A0` 以防缺） | legacy `XC_MethodHook`：`beforeHookedMethod` 改 `param.args[i]`；记录用 `afterHookedMethod` |
| CARD_MODEL_GETTERS 11 类 getter（记录） | hook 同上 getter（**只观测，不改返回值**）；并额外读 `getAid()/getAppCode()/getCardType()` 组成我们的 **CardIdentity(type,id)**（type: 1/5/6/33 + appCode/aid 规则，直接抄 `cardTypeOf`） | 解决朋友"URL 即身份"的脆弱点 |
| `isCardFace`（祖先 4 容器类） | 同逻辑（`View.getParent()` 循环 8 层比类名） | 减少误替换 |
| `xc.k(url)` 图片 URL 过滤 | 同逻辑（http(s)、扩展名白名单、排除 icon/logo/avatar/广告） | 可加 OPPO 真实 CDN 域名白名单（`fast-wallet.finzfin.com` 等） |
| 自定义图存在钱包 `filesDir/images/card_<md5>.jpg`，替换为 `file://` 路径 | **沿用该方案**（最稳，Glide 直接 load file://，无需 content:// 兼容性验证）：  
  - 我们已有 provider 配置：模块 App 侧 UI 存图到我们 provider 目录；  
  - 钱包侧 hook 首次需要某卡面时，通过 `ContentResolver` 读我们 provider（或由我们模块 app 通过 LSPosed 广播/TCP 推给钱包进程）把 bytes 写入**钱包 `filesDir/oppo_faces/<key>.jpg`**，在内存 map 记 `url→file://`；  
  - 推荐直接复刻朋友 **TCP 桥**（最简单、已被验证）：钱包侧 legacy hook 初始化时起 `ServerSocket(127.0.0.1, 47121..47123)`，我们模块 app 复用同一文本协议 `PING/URLS/PUT/DEL/CLEAR` 推送内置配置与 JPEG；钱包侧写入自己的 filesDir 并替换参数。  
  - 若不想起服务：用我们 provider（exported + 只读）在 wallet 进程里 `openInputStream` 读图的 bytes，再落到钱包 cacheDir/filesDir → `file://`；仅需模块 app 配置版本号参与缓存失效。 | content:// 给 Glide 有兼容风险 ⇒ 落地文件最稳 |
| 配置键 `<url>→file` / URLS 列表 | 我们 **CardIdentity(type,id)** 为主键：`type ∈ {bank,transit,door,qr,other}`，id=`aid`(+appCode)（NfcCard/NfcCardDetail/EntranceCard 可读）；URL→identity 由记录 hook 学到，缓存 `url→identity→file`；UI 列表用 identity 展示（可显示 name/type/balance） | 兼容 CDN URL 变更；与荣耀版 CardIdentity 统一 |
| UI：Compose 列表/预览/选图/缩放/吸附/导出导入 | 我们已有模块 UI（Miuix）；新增 OPPO 卡面页：**复用朋友交互**（列表、预览拖动缩放、重置、导出/导入 zip），图片处理用同一套：decode ≤1600、JPEG q88 递减到 ≤700KB；保存后通过桥/provider 推送并提示"重新进入卡包页" | 可选：我们主动通知刷新（视图注册表） |
| deoptimizeCallers(≤400) | legacy 无 `deoptimize` API；替代：①hook 低层公共漏斗（`CircleNetworkImageView#y0/x0/A0` 或 `e1/g1`）降低内联影响；②getter 记录 hook 失效时用我们 H-D 兜底（adapter/Activity bind 直接替换）；③LSPosed legacy 也提供 `XposedBridge.hookAll*` 等 | 需在设备上验证内联程度 |
| RemotePreferences | 我们不需要；用 provider/TCP 自管配置 | — |

### 6.2 落地文件/类（建议）
1. `hook/oppo/OppoCardHooks.kt`（legacy）：
   - `install(walletCL, configProvider)`：
     - hook `CircleNetworkImageView`：14 个方法（同朋友表）；`before` 遍历 `param.args` 中 String：`if OPPO.isFaceUrl(s) && isCardFace(view)` → `record(url, identity)`；`val repl = mapper.resolve(url)`; `if (repl!=null) param.args[i]=repl`（`file://...`）；异常吞掉走原方法。
     - hook 4 个容器 setter（FinBigCardFaceView#setCardFaceUrl、FinCustomCardView#setCardFaceUrl、FinSmallCardFaceView#setImageBitmap/#setCardFaceUrl、FinUINfcCardFaceView#showCardImg）——同样改 String 参数（`showCardImg` 第 1 个参数）。
     - hook 11 个模型 getter（`after`：不改返回值，只 `record(url, readIdentity(thisObject))`）。
     - `CardCoverSettingUtil$Companion#loadBitmapSync`（我们 H-E）：url 命中 → 返回自定义 Bitmap（避免文字颜色按原图）。
   - 视图注册表：hook 时对替换的 ImageView `WeakReference` 入表；配置变更/推送后遍历重设（`view.setImageBitmap(bitmap)` 或重新 `setImageUrl(fileUri)`）。
2. `hook/oppo/OppoConfig.kt`：config JSON `{type,id}→{url,fileName,version}`（我们 provider 配置）；`resolve(url)`、`invalidate()`；写入路径 `wallet.filesDir/oppo_faces/`（若用 TCP 桥则 `filesDir/images` 兼容朋友路径便于迁移）。
3. `hook/oppo/OppoBridge.kt`（可选，推荐）：复刻 `ja/ia/j4`：主进程 `ServerSocket` 47121-3，协议同 §4；模块 App 侧复用 `PING/URLS/PUT/DEL/CLEAR` 文本协议；`PUT` 里 bytes→`wallet.filesDir/oppo_faces/<md5>.jpg` + 配置；`URLS` 返回我们 identity 列表（`type\tid\turl\tname\tbalance`，可扩展列）。
4. `data/OppoCardStore`（模块 App）：`CardIdentity` 映射 + 图片（JPEG ≤700KB）+ 导出/导入 zip（复用朋友 `ma/ck0` 逻辑：`face.jpg`+`source.jpg`）。
5. UI：OPPO 页 `CardFaceEditorScreen`（列表→选图→拖动/缩放/吸附→保存/重置/导出/导入），文案参考 `strings.xml`（`选择图片`/`重置卡面`/`刷新`/`卡面保存成功`/`钱包进程未运行…`）。

### 6.3 失败回退与兼容
- 所有 hook `try/catch`；类/方法找不到 → log 并跳过；替换异常 → 调原方法；bridge 不在线 → 读 provider/缓存或仅显示"请先打开钱包"提示。
- 不修改钱包 DB/网络/签名；只改传给 ImageView 的字符串参数。
- 荣耀不回归：OPPO hooks 全部在 `com.finshell.wallet` 进程内按类名尝试，荣耀包内类不存在 → 全跳过（安装时按钱包 package 分流，互不影响）。
- 支持版本：建议先按 5.52.0 类名表实现；后续版本类名不变即自动兼容；若方法签名变化，按朋友"名称+参数形状"匹配，可兼容重载。

### 6.4 与朋友模块的关键差异（改进点）
1. 身份用 **CardIdentity(type,id)** 而非 URL；type 映射抄朋友规则，id 用 aid/appCode（可从 NfcCardDetail/EntranceCard 读）。
2. 增加 **亮度/文字颜色** hook（H-E），解决自定义卡面文字可读性。
3. 增加 **主动刷新**（视图注册表），不强制用户重进卡包页（可选）。
4. 文件落地可直接复用朋友 `file://` 方案；若坚持 provider content:// 需设备验证 Glide 兼容性（静态无法证明）。
5. 图片上限/质量参数保持一致（JPEG q88、≤700KB、源≤1600px），避免钱包加载失败。

### 6.5 移植风险补充（复核发现）
1. **`A0(String)` 与 `setCompatibleSignature(true)` 的 GlideUrl 包装**：`CircleNetworkImageView.A0(str)` 在 `this.T==true`（`setCompatibleSignature(true)`，目前见 `MovieTicketCardFrameVH`、`DouyinGrouponCardFaceView` 使用）时会把字符串包成 `com.finshell.finui.widget.imageview.j extends q1.h`（GlideUrl 子类，`c()` 去掉 `?`）。若我们替换成 `file://` 路径，该包装器按 URL 语义处理本地文件可能失败。**电影票/抖音团购卡面**属少数特例，需设备验证；回退方案：对这两个 view 不替换（保持原样）或 hook `A0` 直接返回 `Glide.with(ctx).load(File)` 的 RequestBuilder（不推荐，依赖混淆 Glide API）。
2. **替换发生在参数层、不影响内部逻辑**：`setImageUrl` 内部仍会走 `CardCoverSettingUtil` 等逻辑；亮度计算仍按原 URL（见 §5 缺陷 2）。
3. **R8 内联**：朋友用 `deoptimizeCallers` 保 hook 命中；legacy 无该 API，建议 hook 低层公共漏斗（`e1/g1/f1/y0/x0/A0`）或 H-D bind 兜底。
4. **hook `A0` 的副作用**：`A0` 返回 Glide RequestBuilder，hook 内若替换其 String 参数，等价于改变最终 `Glide.load(String)` 的 model（与朋友模块一致）；我们只需改参数，不碰返回值。
5. **文件路径与卸载/清理**：图片在钱包私有 filesDir，用户卸载钱包即清理；我们移植若改用钱包 cacheDir 需注意系统清缓存导致丢失（建议 filesDir 或模块 provider + 钱包侧落地副本）。


---

## 7) 快速索引（源码位置）
- `friend-src/Hook.java`：TARGET_CLASSES/CARD_MODEL_GETTERS/NAME_GETTERS/locateTargets/interceptCall/interceptDataGetter/isCardFace/deoptimizeCallers/balanceOf/cardTypeOf/cardNameOf/resolve/onPackageReady/Target/Companion。
- `friend-src/lc.java`：钱包侧配置文件读写/图片目录/版本号 `j`。
- `friend-src/j4.java`：钱包侧 TCP 命令处理（PING/URLS/CLEAR/PUT/DEL）。
- `friend-src/ia.java`/`ja.java`：桥服务器启动与进程判断。
- `friend-src/xj.java`：App 侧 TCP 客户端（J=PUT, Q=DEL，端口 47121-3）。
- `friend-src/xc.java`：App 侧 SharedPreferences 配置/图片写入/推送/删除。
- `friend-src/k.java`：URLS 拉取+同步到 SharedPreferences。
- `friend-src/tc.java`：Compose 协程保存图片 → `xc.i`。
- `friend-src/ha.java`/`kc.java`：CardEntry/Entry 模型。
- `friend-src/fk0.java`：重置卡面；`of.java`：onResume 清理/重推；`fp.java`：调试写图。
- `friend-src/MainActivity.java`+`b.java`：入口/Compose 根 + 调试 intent。
- `friend-src/strings.xml`：全部 UI 文案（含控制说明）。
- `friend-src/AndroidManifest.xmltree.txt`：权限/queries/组件。
