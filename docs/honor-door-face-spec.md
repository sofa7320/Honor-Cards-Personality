# 门禁卡（AccessCard）卡面管线 RE + 模块 hook 规格

目标：修复"门禁卡（及同类非公交卡）在钱包里设置**原生自定义卡面**后 / 模块点击**重置卡面**后，编辑器预览丢失卡面"。
目标 APK：**荣耀钱包 11.0.3.305（versionCode 1110003305，targetSdk 33）**，与设备 `192.168.31.10:5555` 已安装版本完全一致（`dumpsys package` 实读），因此直接复用仓库内 `work/jadx-out/sources` 反编译产物，未重新解包。
行号均为 jadx 输出行号；`defpackage.*` = 混淆类。不确定项标 **[UNKNOWN]/[PARTIAL]**。

---

## 0. TL;DR（结论先行）

1. 门禁卡卡面 = **单个 ImageView 里的位图**，全 APK 只有 **一个加载入口**：
   `defpackage.xi#j(ImageView, AccessCard, Context, Integer, boolean, e11, int)`（static，`xi.java:518-546`）。
   没有自定义 View 绘制、没有本地文件/URI 分支。公交卡走 `nn0#p`，门禁卡走 `xi#j` —— 这是"公交正常、门禁异常"的根因。
2. 门禁卡的"钱包原生自定义卡面"= **`AccessCard.faceId >= 1000`（或 <= -1000 创意卡面）+ `AccessCard.cardPicUrl` 远端 presigned URL**（不是本地文件）。
   设备实测：`faceId=81369`，`cardPicUrl=https://wallet-cos-drcn.wallet.hihonorcloud.com/wallet/access_pic/.../HonorWallet_access_card_compress_....jpg?X-Amz-Expires=604800&X-Amz-Signature=...`（7 天过期，每次同步换代）。
3. 模块当前把门禁卡的 URL **写死丢弃**：`FaceHookEngine.normalizeFaceUrlFromResult()`（`FaceHookEngine.kt:1433-1448`）与 `CardIdentityExtractor.faceUrlOf()`（`CardIdentity.kt:101-121`）中 `ACCESS -> null`；
   而公交/车钥匙有 `faceUrl` 兜底（面板 `FaceRepository.ensureOriginalFace` 下载 CDN 图）。所以门禁卡只能依赖"从 ImageView 抓 drawable"，而该抓取对门禁卡不可靠（见 §4）。
4. 设备实测（模块数据，只读）：`files/originals/` **为空**；`files/cache/original/` 只有公交 + 车钥匙两张；门禁卡应有的快照 `0a8e2106…jpg` **不存在**；`report.json` 里门禁 `"faceUrl":null`。→ 面板拿不到任何"钱包当前卡面" → 预览丢失。公交卡正常（有 URL 下载）。
5. **最小修复（2 处，成本极低）**：门禁把 `cardPicUrl` 作为 `faceUrl` 上报（仅当 faceId 为非经典区间），面板即走 CDN 下载；同时补一条"钱包进程重新抓取"通道（现在 `refreshOriginal` 只删文件、不通知钱包进程，`FaceCapture.captured` 每进程只抓一次）。
6. **健壮修复（推荐）**：`xi#j` 改为 **before hook** 单一入口：读模型字段（faceId/cardPicUrl）→（a）有模块卡面则跳过原方法直接贴我们的位图；（b）无模块卡面则按来源确定性抓取（经典 faceId → `f06.a(faceId,0)` 资源；自定义 → 下载 `cardPicUrl`），不再抓 ImageView 的 drawable。

---

## 1. 门禁卡模型与"钱包原生自定义卡面"的存储

### 1.1 模型字段（`work/jadx-out/sources/com/hihonor/wallet/serviceinterface/accesscard/AccessCard.java`）

```java
@Keep public final class AccessCard implements Serializable {   // :44
    private String faceId;        // :58
    private String cardPicUrl;    // :49
    private String uid, aid; ...  // :46,:70
    public final String getFaceId()      { return this.faceId; }       // :383
    public final String getCardPicUrl()  { return this.cardPicUrl; }   // :343
    public final String getUid()         { ... }                       // :431
}
```
`AccessCard.copy(...)`（`:299`）会整体替换 `faceId`/`cardPicUrl` —— 原生换卡面就是"构造新 AccessCard"。

### 1.2 `faceId` 语义（`com/hihonor/cloud/common/utils/CardFaceType.java` + `defpackage/f06.java:8` + `defpackage/xi.java:508-510`）

| faceId 区间 | 类型 | 卡面来源 |
|---|---|---|
| 0..99 | ClassicType | 内置 drawable `honor_wallet_access_classic_cardface_0..5` |
| 100..199 | PersonalType | `honor_wallet_access_personalized_cardface_*` |
| 200..299 | RealisticType | `..._realistic_style_cardface_*` |
| 300..399 | SimpleBusinessType | `..._simple_business_cardface_*` |
| **>= 1000** | **CustomType（用户上传的"自定义卡面"）** | **`AccessCard.getCardPicUrl()` 远端 URL** |
| **<= -1000** | **CreativeType（服务端推送创意卡面）** | **`AccessCard.getCardPicUrl()` 远端 URL** |

判定：`xi.i(int i) { return i >= 0 && i < 1000; }`（`xi.java:508-510`）；`f06.a(faceId, 0)` 返回内置资源 id（`f06.java:10-24`），`f06.c(faceId)` 返回文字/图标 tint（`:32-40`）。

### 1.3 存储：卡片列表是一个 Gson JSON 数据文件（不是 SQLite/Room）

- `com/hihonor/cloud/common/datastore/a#getStoreFileName()`（`.../datastore/a.java:264-297`）→ `go2`/`fo2`：
  ```java
  return new File(context.getApplicationContext().getFilesDir(), "datastore/" + fileName);  // fo2.java:17-24
  ```
- `com/hihonor/wallet/cardmanager/kv/UnionCardDataStore.java:44`：
  ```java
  public String getStoreFileName() { return "com.hihonor.wallet.cardmanager.kv.UnionCardDataStore.dcs"; }
  ```
  → 设备路径：`/data/data/com.hihonor.wallet/files/datastore/com.hihonor.wallet.cardmanager.kv.UnionCardDataStore.dcs`
- `decodeFromDisk/encodeForDisk` 默认恒等（`datastore/a.java:298-306`；`UnionCardDataStore` 未覆盖）→ 文件内容就是 JSON：`{"gsonData":"{\"cardInfo\":\"{...unionCardList...}\"}"}`，`unionCardList[i]` 即每张卡的序列化字段。
- **设备实读**（只读 `su -c cat`，已脱敏）：
  ```
  {"faceId":"81369","cardPicUrl":"https://wallet-cos-drcn.wallet.hihonorcloud.com/wallet/access_pic/1769876474841/HonorWallet_access_card_compress_20260201_002114.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20261111T040048Z&X-Amz-Expires=604800&X-Amz-Signature=...", ...}
  ```
  → 原生自定义卡面**没有本地路径字段**；`cardPicUrl` 是对象存储**预签名 URL，7 天有效**，每次卡片同步会换新（`X-Amz-Date` 变化）。

### 1.4 原生"自定义卡面"的写入链路（`com/hihonor/cloud/business/accesscard/activity/AccessCardCustomActivity.java`）

1. 用户点"自定义卡面"→ 拍照/相册 → 裁剪：
   `AccessCardCustomActivity#d1(Uri)`（`:2440-2453`）：`HonorWalletCropImageActivity`，输出到 `getExternalFilesDir(null)/HonorWallet_access_card_cropOut_<yyyyMMdd_HHmmss>.jpg`，比例 **21:13**，JPEG 80。
2. 压缩：`defpackage.zi#m(Context,Uri,cb,...)`（`zi.java:198-275`）→ `HonorWallet_access_card_compress_<ts>.jpg`，限制 500KB；**这些是临时文件，不是最终卡面存储**。
3. 上传：`defpackage.fj`（`AccessCardViewModel$uploadImage$1`，`fj.java:18`）把文件 base64 放进 `AccessCardUploadPicReq` → 服务端返回 `AccessCardUploadPicRsp{id, cardPicUrl}`（`model/AccessCardUploadPicRsp.java`）；随后 `zi#p` 轮询审核状态（`zi.java:299+`）。
4. 审核通过 → `AccessCardCustomActivity` 把新 `AccessCardPic(id, name, cardPicFileUrl)` 插入 `zi.q` 列表（`defpackage/f9.java:60-90`）；自定义卡面列表来自服务端 `AccessCardGetCardPicV2Rsp.userCardPicList` + 创意卡面 `CardPushConfigDto.cardImages`。
5. 用户选中某张卡面 → `AccessCardCustomActivity#l1(String name,int faceId)`（`:2638-2682`）：
   ```java
   faceId  = 选中项 id（AccessCardPic#getId / ImageInfo#getId）
   cardPicUrl = 选中项 getCardPicFileUrl()（faceId<=-1000 时取 getImageUrl()）
   newCard = AccessCard.copy(old, name, String.valueOf(faceId), ..., cardPicUrl, ...)   // :2676
   Y0().s(uid, old, new h0(newCard)); W0().x(面板页, newCard)                            // :2677-2678
   ```
6. 点"保存/完成"（`CompatButton` `new z9`，`:2325-2327`）→ `defpackage.z9`（`z9.java:88-120`）再 copy 一次（`faceId=q0`、`cardPicUrl`=编辑态里的新 URL）→ `defpackage.aj` = `AccessCardViewModel$changeCardInfo$1`（`aj.java`，jadx 未反编译方法体 **[PARTIAL]**）→ 服务端 `AccessCardEditReq{uid, cardName, cardFaceId}`（`model/AccessCardEditReq.java`）+ 本地 `UnionCardDataStore` 落盘（`u55.a.d(OperateType.UPDATE, card, …)` 是 store 更新的通用入口，见 `xi.java:673` 等同类代码）。
7. **何时生效**：`faceId`/`cardPicUrl` 一旦写进 store，下一次任意 surface 绑定都会重新 `xi.j` 读新字段 → 用新 URL 加载；列表 diff 也会因 `faceId`/`cardPicUrl` 变化而重建（`ia8.java:106`、`d67.java:862`、`to6.java:1496`、`y72.java:154`）。详情页还会用服务端最新数据回写：`AccessCardDetailSuccessView.java:286-308`（`setFaceId`/`setCardPicUrl` + `u55.a.d(UPDATE, …)`）。

> **要点**：原生自定义门禁卡面 = **remote presigned URL in `AccessCard.cardPicUrl`**，`faceId>=1000`。没有本地文件、没有 SharedPreferences 字段。Glide 磁盘缓存是唯一本地副本，不可依赖（会清、会过期）。

---

## 2. 显示层管线（哪些类/视图/方法）

### 2.1 唯一加载入口 `defpackage.xi#j`（`xi.java:518-546`）

```java
public static void j(ImageView imageView, AccessCard accessCard, Context context, Integer num,
                     boolean z, e11 e11Var, int i) {                          // :518
    int faceId = Integer.parseInt(isEmpty(accessCard.getFaceId()) ? "0" : accessCard.getFaceId()); // :526
    Object source;
    if (i(faceId))                                   // 0 <= faceId < 1000
        source = Integer.valueOf(f06.a(faceId, 0));  // 内置 drawable 资源 id            :532-533
    else {
        source = accessCard.getCardPicUrl();         // 原生自定义/创意卡面 URL          :534-535
        if (source.length() == 0) source = Integer.valueOf(cza.c(accessCard)); // 兜底默认图 :536-539
    }
    nh5.a.c(imageView, context, source, num, null, cza.c(accessCard), 0, true, false,
            e11Var, false, false, null, false);      // :545  → Glide
}
```

### 2.2 全部调用点（= 门禁卡所有"会显示卡面"的地方）

| # | surface | 类/方法 | 布局与视图 | 说明 |
|---|---|---|---|---|
| S1 | 首页 swipe 卡片栈 | `defpackage.w59#c(…,SwipeCardStackView.m,…)`（`w59.java:187`），`xi.j` @`:200` | `honor_wallet_swipe_adapter_item_access_card.xml` → include `honor_wallet_include_swipe_access_card_info.xml`：`HwCardView#shadow_layout`(12dp 圆角) → `HwImageView#iv_card_bg`(fitXY) | 主 surface；`:205-215` 用 `f06.c(faceId)` tint、`gradient_view` 在 **非经典卡面（含原生自定义）时显示**（`xi.i(faceId)^true` → VISIBLE），`:220` 卡名 |
| S2 | Home/lyra 卡页 | `defpackage.la8#a(Context,Object,View,int)`（`la8.java:317-350`），`xi.j` @`:335` | `honor_wallet_adapter_home_card.xml` → include `honor_wallet_include_home_card_info.xml`：`HwCardView` → `RoundedImageView#iv_card_bg`(fitXY,12dp) | 首页卡列表 |
| S3 | 门禁详情页 | `defpackage.jg#u(dib.a,int)`（`jg.java:110-213`），`xi.j` @`:187` | `honor_wallet_activity_access_card_detail_picture_item.xml`：`HwCardView` → `HwImageView#iv_card_bg`；`jg` 由 `AccessCardDetailActivity` `new jg()`（`:153`）+ `HwViewPager.setAdapter`（`:303/:387`） | 详情 pager；`:208-212` 非激活状态加 `magic_mask_regular` colorFilter |
| S4 | "卡包/我的卡片"列表 | `defpackage.to6`（MyCardsAdapter）access 分支，`xi.j` @`:295` | `iv_icon`（HwImageView，77.5×48dp，角 6/8dp；`:222`） | 小缩略图 |
| S5 | 其它卡列表 | `defpackage.d67` access 分支 `xi.j` @`:148` / `:195`；`pm8.java:49`、`pu8.java:117/:158`、`z73.java:146`、`y72.java:66` | `iv_icon` | 卡片选择/详情/换卡面列表 |
| S6 | 云门禁详情 | `AccessCardCloudDetailActivity.java:276` | `iv_card_bg` | 云端门禁卡 |
| S7 | 门禁"修改卡面"页预览 | `AccessCardCustomActivity` 使用 `jg` pager（`T().e`，`:2262` `W0()`=`new jg()`），`xi.j` @`jg:187` | 同 S3 | **模块已通过 `EXCLUDED_CONTEXTS` 排除**，保持排除 |
| — | 通知/胶囊图标 | `defpackage.ye9.java:562` 自取 `xi.i(faceId)? f06.a : cardPicUrl` 再 Glide | 无 View | 不影响预览，可不管 |

**结论**：门禁卡没有"自定义 View 绘制"路径，也不存在本地文件加载分支；`gradient_view`（顶部 56dp 渐变 `honor_wallet_gradient_access_card_face_top`）和 `iv_home` 是覆盖层，不是卡面本体。

### 2.3 统一 Glide 封装 `defpackage.nh5#c(...)`（`nh5.java:68-142`）

```java
public final void c(ImageView, Context, Object source, int placeholderRes, Drawable placeholderDrawable,
                    int errorRes, int i3, boolean z, boolean z2, e11, boolean z3, boolean z4,
                    vw7<Drawable> listener, boolean z5)                    // :68
    ...
    if (i != 0)   dx7Var.n(i);        // placeholder(int)                :87-89
    if (drawable != null) dx7Var.o(drawable);  // placeholder(Drawable)
    if (i2 != 0)  dx7Var.g(i2);       // error(int)                       :93-95
    ... Glide.with(context).load(source).into(imageView);                  :113-124
```
（`gn0` = Glide `BaseRequestOptions`，字段/位掩码见 `gn0.java:13-40,213-291`；`lt8.java:222-246` 证实 `i()/h()` 分别取 placeholder/error。）

**门禁卡的关键差别**：`xi.j` 传入 `placeholderRes=0, placeholderDrawable=null, errorRes=cza.c(card)`（`cza.java:239-259` → `honor_wallet_access_classic_cardface_0`）。
⇒ **没有 loading 占位图**，加载期间 ImageView 保留**复用 holder 的上一张卡 drawable**（或 null）；加载失败才显示默认经典卡面。
对照公交 `nn0#p`（`nn0.java:484-515`）：先查 `hv1` 预加载位图缓存，命中则 **同步 `setImageBitmap`**（`:492-495`），未命中才 Glide；预加载由 `qc9` 页面预取填充 `hv1.c`。门禁 `xi.j` **完全不查 `hv1`**。

### 2.4 其它相关入口

- `defpackage.mv1#a(Object)`（`CardImageUtil`，`mv1.java:38-82`）：门禁分支与 `xi.j` 同语义（`faceId<1000` → 资源 int，否则 `cardPicUrl`）；被 `nn0#p` 的缓存 key、列表缩略图等复用。
- `defpackage.mv1#c(ImageView,Object)` 缩略图加载。
- 列表 diff：`ia8/d67/to6/y72` 都比较 `getFaceId()+getCardPicUrl()` → 原生换卡面后列表会重建/重绑（可利用来触发重抓）。

---

## 3. 推荐 hook 规格（给模块开发）

### H1（主）`defpackage.xi#j` **before** —— 单入口，同时解决"捕获"和"替换"

签名（obfuscated 名即 APK 内真实名）：
```java
defpackage.xi#j(android.widget.ImageView, com.hihonor.wallet.serviceinterface.accesscard.AccessCard,
                android.content.Context, java.lang.Integer, boolean, defpackage.e11, int)   // static
```
伪代码：
```kotlin
beforeHook(param) {
    val iv = args[0] as ImageView
    val card = args[1] ?: return          // AccessCard
    val id = CardIdentityExtractor.of(card) ?: return     // type=access, id=getUid()（本机 uid 8 位串）
    recordCard(id)

    // 1) 读取模型字段（反射 AccessCard 私有字段 faceId/cardPicUrl，避免触发被 hook 的 getter 重入）
    val faceId = readFieldIntString(card, "faceId")       // 可为 null/""（当 "0"）
    val url     = readFieldString(card, "cardPicUrl")     // 非经典时才是真实来源
    val custom  = faceId >= 1000 || faceId <= -1000

    // 2) 上报"钱包原生卡面"（替代当前 ACCESS->null）
    if (custom && url.isNotBlank()) reportFaceUrl(id, url, nativeFaceKey = faceId)

    // 3) 替换（模块有配置时）：跳过原方法，直接贴我们的位图
    val resolved = ProviderClient.resolve("access", id)
    if (resolved != null) {
        param.setResult(null)                              // 跳过原始 xi.j（void）
        FaceEnforcer.register(iv, resolved)
        FaceImageLoader.load(iv.context, resolved) { bmp -> bmp?.let { FaceEnforcer.applyBitmap(iv, it) } }
        return
    }

    // 4) 捕获（无配置 / 需要"钱包当前卡面"时）：确定性来源，不依赖 ImageView.drawable
    if (FaceCapture.sourceCaptureNeeded("access", id, faceId)) {
        executor.execute {
            val bytes = when {
                custom && url.isNotBlank() -> httpGet(url)              // 原生自定义/创意卡面
                !custom -> drawableResourceBytes(iv.context, resolveFaceRes(faceId)) // f06.a(faceId,0)
                else -> null
            }
            bytes?.let { FaceCapture.saveSnapshot("access", id, nativeFaceKey = faceId, it) }
        }
    }
}
```
辅助点：
- `resolveFaceRes(faceId)`：反射 `Class.forName("f06", false, walletClassLoader).getMethod("a", int, int).invoke(null, faceId, 0)`（`f06.java:10-24`）→ `Resources.getDrawable(resId, theme)`。
- 快照去重 key = `(type=access, id=getUid(), faceId)`；**不要把 presigned URL 当 key**（每次同步都变，但画面相同，`faceId` 才是稳定版本号）。
- `FaceCapture.MAX_ATTEMPTS`/`OnPreDraw` 路径可保留作兜底，但**门禁卡不要以它为主**（见 §4）。

### H2（最小改动，若只愿意改 2 处）

1. `FaceHookEngine.kt:1433-1448` `normalizeFaceUrlFromResult()`：
   ```kotlin
   CardTypes.ACCESS -> {
       val f = CardIdentityExtractor.faceIdOf(card)     // getFaceId() -> Long?
       if (f != null && f in 0..999L) null else v       // 经典内置卡面无 URL；自定义/创意卡面有
   }
   ```
2. `CardIdentity.kt:101-121` `faceUrlOf()` 的 `else -> null` 前对 ACCESS 做同样判断（或直接复用 `normalizeFaceUrlFromResult` 逻辑）。

配套（必须，否则"换原生卡面后"仍不刷新）：
- 当 `getCardPicUrl()` hook 观察到 URL 变化（同一 `faceId`、URL 变化 = 重新签名，不等于换卡面；`faceId` 变化才是换卡面）时：上报新 `faceUrl`；面板发现 `faceUrl`/`faceId` 变化 → 删除旧原文快照并重新下载。
- 面板对 presigned URL 失败（HTTP 403/过期）要视作 "URL 过期" 而非"无卡面"：触发一次钱包重绑/重新上报（例如打开钱包卡页后再取 report），不要长期缓存失败结果。
- `faceId` 也要上报（新增字段，如 `nativeFaceKey`/`nativeFaceId`），否则面板无法区分"URL 重新签名"与"用户换了卡面"。

### H3（替换，建议随之强化）跨进程"重抓/失效"通道

现状断链（实测/代码证据）：
- `FaceProvider` 的 `refreshOriginal` 只删除 `files/originals/<sha>.jpg`（`FaceProvider.kt:100-108`），**不发广播、不通知钱包进程**。
- 钱包进程内 `FaceCapture.captured` 是 `ConcurrentHashMap`，**每进程每卡只抓一次**（`FaceCapture.kt:61-63,129`），只有 config 变更监听里 `invalidateAll()`（`FaceHookEngine.kt:193-199`）或 `invalidate()` 会清。
⇒ 用户在钱包里换了原生自定义卡面（config 未变）后：面板删了快照文件，但钱包进程 `captured` 仍为 true → **永不重抓**；门禁又没有 URL 兜底 → 预览空白/仍是旧图。这就是"设置原生卡面后预览丢失"的直接机制。

修复：
- Provider 新增 call action（例：`recaptureOriginal(type,id,faceId)`），钱包进程 hook 侧注册对应 `ContentObserver`/广播接收：
  ```kotlin
  FaceCapture.forced += key; FaceCapture.captured.remove(key)
  FaceEnforcer.unregisterAllFor(key)                      // 防止旧的自定义位图仍被 preDraw 强制贴回
  reapply/reloadRegisteredViews(key, sourceCapture)       // 直接按模型字段重抓，不必等 rebind
  ```
- 或复用现有 `CONFIG_CHANGED` 广播，携带 `{type,id,action=recapture}`。

### H4（现有替换路径保留）
- `nh5#c` before 替换 source（`FaceHookEngine.kt:296-380`）对 **URL 来源**有效（`sourceToFace[url]` 由 `getCardPicUrl` after-hook 建立，`FaceHookEngine.kt:1489-1541`）；对 **经典内置卡面（source=Integer 资源）无效**，目前靠 `xi#j` after-hook（`:547-573`）补。若按 H1 把 `xi#j` 改成 before 跳过原方法，则不必再依赖 after-hook，且消除"原 Glide 异步回填覆盖我们的图"的竞态（`FaceEnforcer` 继续作为兜底）。
- 保持 `EXCLUDED_CONTEXTS`（`AccessCardCustomActivity`/卡面样式预览页等，`FaceHookEngine.kt:81-87`）。
- 模块"重置卡面"时：删除配置 → 同步 `FaceEnforcer` 反注册该卡所有 view + 触发一次 rebind/重抓，否则屏上仍是我们旧图、且下一次抓取可能抓到我们的图。

---

## 4. 为什么"从 ImageView drawable 抓一次"对门禁卡失败（证据链）

| # | 机制 | 证据 |
|---|---|---|
| F1 | 门禁面走 `xi#j`，不查 `hv1` 预加载缓存，没有"同步 setImageBitmap"路径；公交走 `nn0#p`，缓存命中时 bind 期间就已同步贴好位图 | `xi.java:518-546` vs `nn0.java:484-515`（`:492-495` cache hit → `setImageBitmap`） |
| F2 | `xi#j` 传 `placeholderRes=0, placeholderDrawable=null`，**无 loading 占位**；加载期间 ImageView 保留复用 holder 的**上一张卡** drawable。`FaceCapture` 在第一个 preDraw 后 `view.drawable` 非空即抓 → 抓到别的卡的面/旧图，并以本卡 key 存盘 | `xi.java:545`；`nh5.java:87-95`；`lt8.java:222-246`（placeholder/error 语义）；`FaceCapture.kt:131-169` |
| F3 | 冷启动/URL 未缓存时 Glide 网络加载常 > 30 帧；`MAX_ATTEMPTS=30`（`FaceCapture.kt:51`）后监听器被移除且不算抓取成功 → 静默丢失（`captured` 只在成功时置位） | `FaceCapture.kt:51,131-144,154-169` |
| F4 | 模块有自定义卡面时（正常使用态）**根本不会抓**：`xi#j` after-hook 只在 `resolved==null` 才 `schedule`；`processCapture` 还会 `isConfigured` 直接 return。但"钱包当前卡面"预览/底图恰恰在有自定义卡面时最需要 | `FaceHookEngine.kt:558-561`；`FaceCapture.kt:177-181` |
| F5 | 跨进程失效断链：`refreshOriginal` 只删文件，不通知钱包进程；`FaceCapture.captured` 每进程只抓一次 → 钱包原生换卡面后不重抓（除非 config 变更或杀进程） | `FaceProvider.kt:100-108`；`FaceCapture.kt:61-63`；`ProviderClient.kt:487-500` |
| F6 | 门禁无 CDN `faceUrl` 兜底（代码写死 null），公交/车钥匙有 → 公交在"快照缺失"时依然靠下载 URL 出图，门禁没有第二条腿 | `FaceHookEngine.kt:1433-1448`；`CardIdentity.kt:101-121`；`FaceRepository.kt:656-717` |
| F7 | 设备实测：`/data/data/com.cardpersonality.honor/files/originals/` 为空；`files/cache/original/` 仅 `cbcfbd…`(transit) 与 `641154…`(carkey)；门禁应为 `0a8e2106…(access:F62…F3)` 不存在；`report.json` 里 access `"faceUrl":null` | 设备只读检查（见 §0.4） |

> 附加风险：presigned URL **7 天过期**且每次同步变化 → 面板若长期缓存 URL/失败结果会永久失败；应"重新上报 + 立即下载"。

---

## 5. 期望效果 / 验收

1. 钱包内给门禁卡设置原生自定义卡面 → 回模块打开编辑器：预览/“以当前卡面为底图”出现钱包当前卡面（来自 `faceUrl` 下载）；`report.json` 中 access 的 `faceUrl` 非 null 且随钱包同步更新。
2. 模块"重置卡面" → 钱包恢复原生卡面；模块编辑器预览重新抓到/下载到该原生卡面（不是空白、不是旧图、不是我们的旧图）。
3. 无自定义卡面的门禁卡（faceId 0..999）：预览显示内置经典卡面（来自 `f06.a` 资源快照，PNG 无损）。
4. 有模块自定义卡面时：钱包/首页/详情仍显示我们的卡面（`xi#j` before 跳过原加载，无 placeholder 闪断、无异步覆盖）；`AccessCardCustomActivity` 预览页不受影响。
5. 不写钱包文件、不改钱包 DB；仅显示层 hook + 模块自有 provider 存储。
6. 回归：公交/车钥匙/银行卡/校园卡行为不变（它们的 faceUrl/加载路径未改）。

### 设备验证命令（只读为主）
```bash
adb -s 192.168.31.10:5555 shell su -c 'cat /data/data/com.cardpersonality.honor/files/report.json'   # access faceUrl 应非 null
adb -s 192.168.31.10:5555 shell su -c 'ls -la /data/data/com.cardpersonality.honor/files/cache/original /data/data/com.cardpersonality.honor/files/originals'
adb -s 192.168.31.10:5555 logcat -s CARD_PERSONALITY | grep -E 'access|capture|face_url|xi_'   # HLog TAG
```

---

## 6. 不确定项 / 风险

- **[PARTIAL]** `defpackage.aj#invokeSuspend`（`AccessCardViewModel$changeCardInfo$1` 保存协程）jadx 反编译失败（`UnsupportedOperationException: Method not decompiled`）。"服务端 EditReq + 本地 store 更新"由字段/调用点（`z9.java:96,120`、`AccessCardEditReq`、`u55.a.d(UPDATE,…)` 通用路径、`.dcs` 文件 mtime）推断；不影响 hook 方案（模型字段在 `xi.j` 处一定是最新值）。
- **[UNKNOWN]** `AccessCardCustomActivity` 的卡面候选列表/预览页内部 adapter 细节未逐个展开（模块已排除该 Activity，不影响）。
- Glide 版本占位符语义按 `gn0`/`lt8` 反编译推断（`lt8.java:222-246` 已确认 placeholder vs error 的字段对）；若未来版本参数顺序变化，H1 方案不依赖它（不读 drawable）。
- `getFaceId`/`getCardPicUrl` 是 `final` 单行 getter，存在被 AOT 内联的风险；因此 H1 建议**反射读字段**（`AccessCard.faceId/cardPicUrl`）或把捕获放在 `xi#j` 的 args 上，而不是依赖 getter hook。
- presigned URL 可能因过期/被服务端换代导致 403；面板必须按"过期"处理（重报/重绑），不能当作"无卡面"永久缓存。
- 模块 report/存储 schema 若要加 `nativeFaceId` 字段，需兼容旧面板（缺字段=未知，不触发删除）。

---

## 7. 证据索引（jadx 源码 `work/jadx-out/sources/`）

- `defpackage/xi.java:504-546`（`i/h/j`）、`defpackage/f06.java:8-40`、`defpackage/cza.java:239-259`
- `defpackage/nh5.java:68-142`、`defpackage/gn0.java:13-40,213-291`、`defpackage/lt8.java:222-246`、`defpackage/nn0.java:484-515`、`defpackage/hv1.java`
- `defpackage/w59.java:187-238`、`defpackage/la8.java:317-350`、`defpackage/jg.java:110-213`
- `com/hihonor/wallet/serviceinterface/accesscard/AccessCard.java:44-71,299,343,383,482,508`
- `com/hihonor/cloud/business/accesscard/activity/AccessCardCustomActivity.java:507,511-526,2250-2262,2325-2327,2440-2453,2638-2682`
- `com/hihonor/cloud/business/accesscard/activity/AccessCardDetailActivity.java:153,303`；`AccessCardDetailSuccessView.java:286-308`
- `com/hihonor/cloud/business/accesscard/model/AccessCardUploadPicRsp.java`、`AccessCardPic.java`、`AccessCardEditReq.java`、`AccessCardGetCardPicV2Rsp.java`
- `defpackage/zi.java:198-275,299+`、`defpackage/fj.java:18+`、`defpackage/f9.java:60-90`、`defpackage/z9.java:88-120`、`defpackage/aj.java`
- `com/hihonor/wallet/cardmanager/kv/UnionCardDataStore.java:44`、`com/hihonor/cloud/common/datastore/a.java:264-306`、`defpackage/fo2.java:17-24`、`defpackage/go2.java`
- `com/hihonor/cloud/common/utils/CardFaceType.java`
- 模块侧：`app/src/main/java/com/cardpersonality/honor/hook/FaceHookEngine.kt:275-278,296-380,547-573,1433-1448,1489-1541,1559-1562`；`hook/FaceCapture.kt:49,61-63,125-202`；`hook/CardIdentity.kt:42-49,79-86,101-121`；`hook/ProviderClient.kt:469-500`；`FaceProvider.kt:100-108`；`data/FaceRepository.kt:58-62,635-717,793-815`；`ui/EditorScreen.kt:251-264,353-369,852-897`；`ui/util/OriginalSnapshotRefresh.kt`
