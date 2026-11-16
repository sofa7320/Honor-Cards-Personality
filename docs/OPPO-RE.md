# 欧加钱包 (OPPO/OnePlus Wallet) 卡面链路静态分析 — v2（round10 / task-29）

目标：为「卡面工坊」增加 OPPO 钱包适配提供 LSPosed hook 依据。**纯静态分析；未执行 APK；未使用设备/adb。**
对象：`D:\Files\dsh work\card-personality\欧加钱包_5.52.0.apk`（115 MB）
- sha256 `b158b2f6f040a19ce047716399827f8201b3f6f4d1b6e7f9fd8d913feb6f3dd3`
- **package `com.finshell.wallet`**、versionName **5.52.0**、versionCode **552004**、minSdk 23 / targetSdk 36 / compileSdk 36（aapt2 badging 确认）
- 注意：REA/JADX 的 package summary 读到了 assets 内嵌插件 APK 的 manifest（version 1.0），**不要用**；以 aapt2 为准。
- launcher `com.nearme.wallet.SplashActivity`；`application com.nearme.wallet.WalletApplication`；label Wallet/钱包
- native 仅 arm64-v8a；9 dex（classes~classes9），35797 classes / 7639 resources

静态工作区（只读副本）：
- JADX CLI 1.5.6 全量源码：`work/re/oppo/jadx-out/sources`（35473 java，151 个类反编译失败）
- 资源：`work/re/oppo/jadx-res/resources`（1944 layout + `values/public.xml`）
- aapt2：`work/re/oppo/tools/badging.txt`、`manifest.xmltree.txt`
- 工具脚本：`work/re/oppo/tools/{scan_apk,idmap,plugin_scan,resolve_ids}.py`（id↔资源名解析）

---

## 0. 应用结构与安全性总览（影响 hook 方案）

单 APK 内 4 套代码：
| 包 | 角色 |
|---|---|
| `com.nearme.wallet.*` | 老钱包主壳、首页容器 WalletIndexActivity、公交/门禁/校园/车钥匙详情、Room DB(`com.nearme.wallet.db.*`) |
| `com.nearme.pay.*` | 卡包 UI（`business.cardpackage`）、详情（`business.carddetail`）、刷卡/消费页（`business.consume`）、桌面卡片 |
| `com.finshell.*` | 新 finui UI（`finui.widget.*` 卡面控件）、supercard、quickcardpkg、balance、setting、doorx、common 工具 |
| `com.oplus.cardwidget.*` / `com.nearme.pay.business.consume.seedling.*` | 桌面小组件（Seedling/Pantanal） |

- **插件化**：`assets/plugins/*.apk` 共 5 个（实测：4 个 `com.finshell.wallet.drouter.*` 支付路由插件 + 1 个 `com.finshell.shadow.manager`）；使用 **Tencent Shadow**（`com.nearme.wallet.shadow.*`）+ `com.finshell.module_manager` 生命周期管理。卡面相关类均在**主 dex**（已反编译确认），插件里没有卡面 UI。
- **热修/加固**：`libapkpatch.so`、`libhpatchz.so`（热补丁）；`libcomm-sign-lib.so`（签名/加签，支付链路）、`libnetsecsdk-4.1.3.so`（网络加密）、`libturingmfa.so`（Turing 设备指纹风控）；`libmmkv`、`libocstool` 等。
- **反注入/反调试**：全仓库静态检索 **未发现** Xposed/Frida/ptrace/TracerPid/`/proc/self/maps` 检测字符串；未发现针对自身 APK 签名的完整性校验（存在的签名校验都是"校验调用方 App"：`com.finshell.storage.remote.singature.SignatureUtils` ← `StorageContentProvider` 校验 `getCallingPackage()`；`androidx.credentials` 的 `CallingAppInfo` 等，属系统/库逻辑）。→ **LSPosed 注入的静态阻力低**；真正的风险是远程卡面 URL/网络签名（模块不碰网络即可）与插件化。
- **多进程**（manifest 实测）：`:remote`、`:icon`、`:temp`、`:syswork`、`:nfchost`、`:fenceTsk`、`:drxtemp`、`:media`、`:plugin`（多个）、`:pps1..10`、`:hlog`、`:worker`、`:downloader`、`:push`、`:bytelive`、`:awareness`。卡面渲染都在**主进程**的 Activity/Fragment（NFC 只读在 `:nfchost`，不渲染 UI）；自绘桌面小组件在钱包主进程 + launcher RemoteViews。→ hook 建议按 **package 全进程注入**（legacy Xposed 默认即全进程），但只有主进程会命中卡面代码。
- **网络 gate**：`FinBigCardFaceView.setCardFaceUrl` 内部有 `com.finshell.common.util.u.i(ctx)`（网络可用性判断）早退 → **离线时详情页大卡面不会被设置**（原生也不显示）。替换方案需在 H-B 层处理离线（跳过 gate），否则离线时自绘卡面也出不来。
- 资源/R 混淆：R 字段短名（如 `f.f86816x4`），但资源条目名可读；`res/values/public.xml` 可用于 id↔名解析（脚本已提供）。

---

## 1. 卡面加载链路（核心结论）

### 1.1 统一图片控件 `com.finshell.finui.widget.imageview.CircleNetworkImageView`
- 自定义 View：圆角裁切（`CornerClipLevel`/radius）、占位/错误图、WebP/GIF/SVG、Lottie 动画监听；内部 **Glide**。
- 当前 URL 字段 `f19430v`；`getUrl()` 返回。
- Glide 请求构建：`A0(String)` → `com.bumptech.glide.c.v(context).load(model)`；`model = str`，若 `this.T == true`（`setCompatibleSignature(true)`）则包成 `com.finshell.finui.widget.imageview.j extends q1.h`（GlideUrl 子类，`c()` 去掉 `?` 后做缓存 key）。**字符串直接交给 Glide.load(String)**，`content://` 理论上可被 Glide StringLoader 接管（需设备验证）。
- 对外的 String 加载方法（**H-A 目标**）：
  | 方法 | 内部 | 用途面 |
  |---|---|---|
  | `setImageUrl(String)` | `e1(str,-1,listener)` → `g1` → `x0/y0` → Glide | 详情页、门禁 doorx、卡面主题、分享、多数自定义 View |
  | `setImageBitmap(String)` | **等价 `setImageUrl(str)`（String 重载，不是 Bitmap）** | FinSmallCardFaceView、FinUINfcCardFaceView.showCardImg、SuperCard/SmartComposite frame、SchoolFrame 等 |
  | `setImageUrlByTemp(String)` | `i1(str,-1,listener)` → `x0`+圆角 transform | **卡包列表 CardPackageListAdapter** |
  | `f1(String,int,boolean)` | `g1(str, placeholderRes, flag, listener)` | **刷卡/消费页全部卡类型 FrameVH** |
  | `e1/g1/i1/j1/k1/l1/m1/n1` | 低层变体（可做精确 hook 但无需） | 少部分 |
  | `setSvgUrl(String)` | SVG 专用（`.svg` 判定 `I0(str)`） | 极少；我们的 content:// 不带 .svg 不会误入 |
- 尺寸/比例：`s1(int w, int h)` 只设置期望宽高（不加载图）；消耗页通过 `com.nearme.pay.utils.i`（CardSizeHelper）计算：`height = (screenWidth - sideMargins)/1.62 + shadow`，常量 **1.62**（`NfcCardFaceAutoSizeHelper.DEFAULT_CARD_RATIO=1.62`、`FinUINfcCardFaceView.updateCardViewSize(1.62f)`）。
- 默认卡面占位 drawable（原生"默认卡面"）：`xm.e.f86372a`(银行)、`nm.e.f75382b0`(公交/推荐)、`xm.e.f86384m`(门禁/车钥匙/eBike)、`xm.e.f86382k`(DCEP)、`f.f80610a`(EID)、`lm.d.f73553e`(卡包列表空)。

### 1.2 卡面 View 家族（H-B 目标，含 overlay）
| View | 布局 | 卡面控件 id | API | overlay id |
|---|---|---|---|---|
| `com.finshell.finui.widget.atomic.FinBigCardFaceView`(ConstraintLayout) | `big_card_face_view.xml`（root CardView `card_img_bg`，corner **14dp**，内部 328×202dp、`h,960:591`） | `@id/card_img` | `setCardFaceUrl(String)`（网络 gate `u.i`）、`getCardFaceView()`、`showDefaultCardTagView(bool)`、`showInStationView(bool)`、`setInStationStatus(@StringRes int)` | `@id/card_sign`(默认卡标签，gone)、`@id/card_status`(进站状态，gone) |
| `com.finshell.finui.widget.atomic.FinSmallCardFaceView` | `small_card_face_layout.xml`（**只有**CircleNetworkImageView） | `@id/smallCardFaceView`（80×50dp、fitCenter、radius 4dp） | `setImageBitmap(String)` → `mSmallCardFaceView.setImageUrl` | 无（父布局 item_card_package_list 里有 tv_label/tv_name 等） |
| `com.finshell.finui.widget.atomic.card.FinCustomCardView`(CardView) | 同 `big_card_face_view.xml` | `@id/card_img` | `setCardFaceUrl(String)`、`getCardFaceView()`、`showDefaultTagView(bool)` | `card_sign`/`card_status` |
| `com.finshell.finui.widget.atomic.card.FinUINfcCardFaceView`(AutoScaleCardView) | `finui_nfc_card_face_view.xml`（`h,328:202`、radius **14dp**、centerCrop） | `@id/iv_card_img` | `showCardImg(url,title)` / `(url,title,boolean)` / `(url,title,boolean,int)`；`showCardLogo(int)`；`showCardPlateNumb(String)`；`setSubTitleContent(String)`；`setCardSignVisibility`/`setEncryptLabelVisibility`/`setLogoVisibility`/`setTitleVisibility`；`updateCardViewSize(float)`；`adjustDisplayNameWidth(int)` | `rnTitleLogo`(容器)、`iv_logo`(36dp 品牌logo)、`tv_title`(16sp 卡名)、`encrypt_label`(加密卡标签)、`tv_plate_numb`(车牌/卡号)、`tv_card_sign`(默认卡)、`keyValidView`(有效期) |

`FinUINfcCardFaceView#showCardImg` 反编译（关键，决定 H-B/H-A 取舍）：
```java
public void showCardImg(String url, String title, boolean z, int brightnessIdx) {
  if (!s.m(getContext())) return;
  if (!TextUtils.isEmpty(title)) this.mThemeTitle.setText(title);
  if (TextUtils.isEmpty(url)) return;
  this.mThemeImg.setErrorDrawable(b.f84255a);
  this.mThemeImg.setImageBitmap(url);               // ← 进入 CircleNetworkImageView.setImageBitmap(String)（H-A）
  CardCoverSettingUtil.INSTANCE.fetchBrightnessUnderTextViewByObservable(getContext(), url, this.mThemeImg, this.mThemeTitle, cb);
  // 回调里按图片亮度设置 title/标签/logo 颜色（lambda$showCardImg$0）
}
```
→ **H-B 不应跳过原方法**（会丢标题/标签/亮度逻辑）；图像替换放在 H-A（原方法内部继续执行 overlay 逻辑）。H-B 仅用于"记录卡容器/离线补图/注册刷新"。

### 1.3 数据来源（卡面 URL / 身份字段）
| 类 | 卡面 getter | 身份字段（同一对象可读） |
|---|---|---|
| `com.nearme.wallet.db.NfcCardDetail`(Room) | `getCardImg()`（另有 `cardImgUrl`/`cardThumbUrl`/`logoUrl`/`textColor`/`frameColor`/`cardTag`/`cardLabel`/`isDefault`） | `getAid()`、`getAppCode()`、`getCardNo()`、`getCardName()`、`getTrafficType()`、`getSubCardType()`、`getStatus()` |
| `com.nearme.wallet.db.NfcCard` | `getCardImg()`（+`getNewCardImg()`） | `getAid()`、`getAppCode()`、`getCardType()`、`getDisplayName()`、`getBizId()`、`getCardExtDataMap()`(含 logo url) |
| `com.nearme.nfc.domain.transit.rsp.CardDetailRsp` | `getCardImg()` | `getAid()`、`getAppCode()`、`getCardName()`、`getCardTag()` |
| `com.nearme.pay.domain.rsp.CardDetailRspVo` | `getCardImg()` | `getLastDigits()`、`getNfcState()`、`getAccStatus()`、`getAppStatus()` |
| `com.nearme.pay.domain.rsp.PayCardInfo`（卡包列表） | `getCardImg()` | `getAid()`、`getAppCode()`、`getCardType()`、`getSubCardType()`、`getBizId()`、`getDisplayName()` |
| `com.finshell.wallet.supercard.net.rsp.CardInfo` | `getCardImg()` | `getCardName()` |
| `com.nearme.nfc.domain.door.rsp.CardTheme` / `com.nearme.wallet.entrance.bean.EntranceCard` | `getCardImg()`；**`getLocalImgPath()`/`setLocalImgPath()`**（原生卡面主题） | `getCardThemeId()`；EntranceCard `getAid()/getAppCode()` |
| 其他 | `CarInfo.getCardImg()`、`BackupCard.getCardImg()`、`VerifyIssuerCardRsp`、`DigitalKeyCarOwnerInfoBean`、`BankManagerDetailRspVO.getBankImgUrl()`/`getBankLogoUrl()`、`BankCardLimitRspVo.getBankCardDetailImg()`/`getLogo()` | 各自 aid/字段 |

> **getter hook 只读观测**（记录 url→身份），绝不能改返回值：`getCardImg()` 被用于统计上报、跨页 bundle、`CardContentProvider` 对外导出、备份/跨设备迁移。

---

## 2. 各 surface 精确绑定（回答"哪条链路覆盖最多"）

**结论先行**：**H-A（CircleNetworkImageView 的 4 个 String 加载方法）覆盖最多**——卡包列表、首页卡包 tab、刷卡/消费页全部卡类型、银行/校园/公交/门禁(部分)/车钥匙/SuperCard 等；因为所有卡面最终都落到该控件的 `setImageUrl/setImageBitmap/setImageUrlByTemp/f1`。H-B（Fin* 卡面控件）用于精确上下文/离线补图/刷新，不是替代点。

### 2.1 卡包列表（首页卡包 tab 同一套）
- `com.nearme.pay.business.cardpackage.adapter.CardPackageListAdapter#onBindViewHolder`（line 460-471）→ 内部类 `a#a0(holder, ModelData, boolean, int, String)` → **line 305 `aVar.f33470a.setImageUrlByTemp(modelData.imgUrl)`**；item 布局 `item_card_package_list.xml`（include `small_card_face_layout`，id `img_card`）。
- `ModelData`（`com.nearme.bean.ModelData`）由 `com.nearme.pay.business.cardpackage.contract.g` 从 `PayCardInfo` 构造：`imgUrl = payCardInfo.getCardImg()`（g.java:344 附近）。
- 其他卡包 tab adapter 同控件：`CardPackageAllAdapter`、`CardPackageIndexAdapter`（`CardPackageViewPagerLayout`）、`CardPackageCloudListAdapter`（`CardPackageCloudLayout`）、`CardPackageGroupLayout` 等。
- 首页：`WalletIndexActivity` 的 tab 由 `com.finshell.homepage.e0`（WalletMainManager）管理，tab type `CARDPACKAGE`（pageId `WALLET_TAB_CARD_PACKAGE_0527`）挂载卡包 Fragment（`CardPackageFragment`）；首页若使用 `item_index_card_package.xml`（`@id/cnivCardOne`/`@id/cnivCardTwo`，**比例 1.63、radius 13.33dp、双层叠卡+卡名**）也仍是 CircleNetworkImageView ⇒ 被 H-A 覆盖。
  - 备注：`item_index_card_package` 在主 dex 未见 inflater 引用（可能为旧版/插件资源），但控件类型决定覆盖关系成立。

### 2.2 刷卡/消费页（`com.nearme.wallet.nfc.ui.NfcConsumeActivity` + `NfcSwitchCardsFragment`）
基类 `com.nearme.pay.business.consume.cardframe.e`（extends `com.nearme.pay.widget.b.a.d`）：
- 卡面：`mCardImage : CircleNetworkImageView`（id **`nfc_consume_card_img`**，程序化创建：`createBaseCardView` → `NfcCardFrameLayout`(id `nfc_consume_card_area`) + `creatCircleImageView`：`setImgType(576)`、`setRoundPx(<dim d.X>)`、CENTER_CROP、`s1(w,h)`）。
- 绑定入口：`e#bindData(NfcCard,int)` → `onBindCommonData` + `onBindSpecialData`（子类）。
- overlay：`mSignTv`(id `nfc_consume_card_sign`，默认卡标签)、`mStationStatus`(进站状态)、Lottie 动画(`nfc_consume_animation_view`)、重试/维修视图(`nfc_consume_retry_active_view`，**功能**，含 FINUIButton)。
- 各卡类型子类 + 加载行：
  | 卡类型 | FrameVH | 卡面加载 |
  |---|---|---|
  | 银行卡 | `consume.card.bank.BankCardFrameVH` | line 148-149 `mCardImage.setPlaceHolderDrawable(xm.e.f86372a); mCardImage.f1(nfcCard.getCardImg(), xm.e.f86372a, false)` |
  | 公交 | `...bus.BusCardFrameVH` | line 96-97 同上（placeholder `nm.e.f75382b0`） |
  | 门禁/门锁 | `...door.DoorCardFrameVH` | line 107-114 `f1(getCardImg(), xm.e.f86384m, false)`；亮度调整 `CardCoverSettingUtil.fetchBrightnessUnderTextViewByObservable`（line 136）；overlay `titleInfoView/tvTitle/mEncryptLabel` |
  | 车钥匙 | `...carkey.CarKeyCardFrameVH` | line 216 `f1(getCardImg(), xm.e.f86384m,false)`；line 302 `f1(strF6,...)`；亮度 `fetchBrightnessByCallback`（line 217）；overlay `mCarCoverInfoView/mCarKeyValidView/titleInfoView/tvTitle` |
  | eBike | `...ebike.EBikeCardFrameVH` | line 79-86 `f1(getCardImg(), xm.e.f86384m,false)`；亮度 line 91；overlay `titleInfoView/tvTitle` |
  | DCEP 数字人民币 | `...dcep.DcepCardFrameVH` | line 130-131 `f1(getCardImg(), xm.e.f86382k,false)`；overlay `mBalanceIconTv/mBalanceTv/mWalletIdTv` |
  | EID 电子身份证 | `...eid.EidCardFrameVH` | line 26-27 `f1(getCardImg(), f.f80610a,false)` |
  | SuperCard | `...super.SuperCardFrameVH` | line 57/59/71 `circleNetworkImageView.setImageBitmap(nfcCard.getCardImg())`；line 80 `setImageUrl(j.g().d())` |
  | 智能组合卡 | `...smart.SmartCompositeCardFrameVH` | line 286/288/300 `setImageBitmap(getCardImg())`；overlay `tvTitle/tvLabel/tvSubDoorTitle/tvCarCardCount/busWarnIcon/carCountLayout` |
  | 推荐卡 | `...recommend.RecommendCardFrameVH` | line 80-81 `f1(getCardImg(), nm.e.f75382b0, false)` |
  | 切换卡 | `...change.ChangeCardFrameVH` | line 70-74 `O0(...); f1(nfcCard.getNewCardImg(), xm.e.f86372a,false)` |
  | 校园卡 | `...school.SchoolCardFrameVH` | line 44 `mSchoolView.h(nfcCard.getCardImg(), cardExtDataMap[logoUrl])` → `SchoolStudentView` 内 CircleNetworkImageView |
  | 抖音团购/电影票 | `DouyinGrouponCardFrameVH`/`MovieTicketCardFrameVH` | `faceBindOrder.Y(getCardImg())` 自定义卡面 View（内部亦为 CircleNetworkImageView/自定义绘制，待细查） |
  | 校园/学生视图 | `com.nearme.wallet.school.widget.SchoolStudentView` | 3×CircleNetworkImageView：`f47497b`(卡面,`setImageUrl(cardImg)` line 68/82)、`f47498c`(校徽/logo)、`f47499d`(背景)；入口 `d(cardImg, logo)` line 79-95；`setShowCardSign(bool)` 控制默认卡标签 |

### 2.3 详情页（按卡类型）
| 类型 | 详情绑定 | 卡面控件 / 调用 |
|---|---|---|
| 公交 | `com.nearme.wallet.nfc.ui.BusDetailActivity` | line 1430/2454/3008 `this.O.setCardFaceUrl(...)`（`O : FinBigCardFaceView`），e7: `X.getCardImg()`；`T : CircleNetworkImageView` 上取当前 URL 做去重 |
| 银行 | `com.nearme.pay.business.carddetail.ui.NfcCardDetailRvAdapter$CardViewHolder#X(CardDetailRspVo)` | line 280-281 `this.f33186c.setImageUrl(cardDetailRspVo.getCardImg())`（id `image_bank_logo`？实际 holder 里为头像/卡面小图），另有 `BankManageDetailActivity` line 331-332 `bankImgUrl`/`bankLogoUrl`，`BankCardDetailActivity` line 340-353 `bankCardDetailImg`/`logo` |
| 门禁 | `com.nearme.wallet.entrance.fragment.DoorDetailFragment` | 字段 `f42783w : FinUINfcCardFaceView`（id `themeImgLayout`，line 781）；显示 `l1(url, cardId)` line 562：`f42783w.showCardImg(url, f42781u, true, f42766f.q(cardId).intValue())`；`f42781u`=卡名、最后参数=亮度索引；`setEncryptLabelVisibility/setCardSignVisibility` 同步 |
| 门禁(ICCOA/大图) | `com.nearme.wallet.carkey.view.Iccoa.IccoaBigPicViewHolder` | line 94 `finUINfcCardFaceView.showCardImg(iccoaBigPicItem.c(), iccoaBigPicItem.b())` |
| 门禁(ODOOR) | `com.finshell.wallet.odoor.view.ODoorDetailFragment` | line 249 `this.f27476n.showCardImg(this.f27470h, "", false, 0)` |
| 车钥匙 | `com.nearme.wallet.entrance.fragment.NfcCarKeyDetailFragment` / `BlueCarKeyDetailFragment` | `f42850r`/`f42733r : FinUINfcCardFaceView`；`G0(url,name)` line 314 → `showCardImg(str,str2,false)`；Blue line 561 同；`showCardLogo(nm.e.C)` 设置品牌 logo |
| eBike | `com.nearme.wallet.entrance.fragment.`*EBike*KeyDetailFragment族（及 `BlueCarKey...` 同基类） | 同为 FinUINfcCardFaceView（`themeImgLayout`），待补精确类名（下一轮） |
| 校园 | `com.nearme.wallet.school.view.SchoolCardDetailActivity` | line 211-214：`schoolCardDetailRsp.getCardImg()` → `SchoolStudentView.d(cardImg, schoolLogo)`（内部 setImageUrl，见上） |
| SuperCard | `com.finshell.wallet.supercard.*` | `SuperCardGuideView#setCardImg(url)`（`cardImgView.setImageUrl`）；`com.finshell.wallet.supercard.ui.a0#d(view,url,...)` line 90-93 `setImageUrl`；其它详情走 `setImageBitmap(String)` |
| 刷卡位置设置（预览） | `SwitchPosSettingActivity` line 582 | `finUINfcCardFaceView.showCardImg(this.f38975h, str, z11, i11)` |
| 卡包云卡/分组 | `CardPackageCloudLayout`/`CardPackageGroupLayout`/`CardPackageViewPagerLayout` | 内部复用 CardPackage*Adapter → 同上 CircleNetworkImageView |

### 2.4 桌面小组件（**不被 H-A 覆盖**）
- `com.nearme.pay.business.consume.seedling.SwipeNfcCardWidgetProvider extends com.oplus.pantanal.seedling.SeedlingCardWidgetProvider`（Seedling 桌面卡片）；数据来自 `SeedlingConsumeManager`，卡面图由 `com.nearme.pay.business.consume.seedling.SwipeSeedlingUtilsKt` 自行下载：
  - `SwipeSeedlingUtilsKt$downloadImage$1`：`com.bumptech.glide.c.v(context).b().R0(imageUrl).Z0(...).get()` → Bitmap；
  - `SwipeSeedlingUtilsKt$drawableToLocalUrl$1`：压缩 PNG 写入本地 → 生成 `file://`/本地路径交给 RemoteViews。
- ⇒ 若需求包含桌面小组件，需要额外 H-F：hook `SwipeSeedlingUtilsKt.downloadImage`/`drawableToLocalUrl` 或用同 URL 映射在下载前替换 `imageUrl`（url 来自内部 `getCardImg()` 调用链，可用 H-C 的 url→身份映射）。静态可行但工作量高，建议二期。

---

## 3. Overlay 元素安全隐藏清单（回答元素组件需求）

原则：**功能/交互控件绝不隐藏**；信息类默认 KEEP、允许用户 HIDE（隐藏不影响业务）；纯装饰可 HIDE。以下 id 均已从布局 XML 或程序化代码确认。

### 3.1 卡包列表 item（`item_card_package_list.xml`）
| id | 类型/用途 | 可点击/功能 | 建议 |
|---|---|---|---|
| `img_card_stroke_wrap` | FrameLayout，卡面外框（80×50dp，`card_face_stroke_bg`） | 否 | 装饰，可 HIDE（隐藏会连卡面一起消失，慎用） |
| `img_card` / `smallCardFaceView` | **卡面**（include `small_card_face_layout`，CircleNetworkImageView 80×50/radius4） | 否 | 替换目标，KEEP |
| `tv_label` | 卡面左上标签（10sp 白底） | 否 | 信息，默认 KEEP |
| `tv_name` | 卡名（16sp） | 否 | 信息，默认 KEEP |
| `tv_last_digest` | 卡号后 4 位 | 否 | 信息，默认 KEEP（可 HIDE） |
| `single_tag` | `SingleTagView` 状态标签（如"未激活"） | 否 | 信息，默认 KEEP |
| `tv_content` | 摘要文案（14sp） | 否 | 信息，默认 KEEP |
| `btn_function` | `FINUIButton` 主操作按钮（如"去使用/去充值"） | **是（setOnClickListener）** | **绝不隐藏** |
| `iv_action` | 二维码/更多操作图标 | **是（setOnClickListener）** | **绝不隐藏** |
| `layout_text_area`/`layout_name`/`barrier`/`shadow_view`/`v_divider` | 布局辅助/分割线 | 否 | 装饰，可 HIDE |

### 3.2 详情大卡面（`big_card_face_view.xml`）
| id | 用途 | 可点击 | 建议 |
|---|---|---|---|
| `card_img_bg` | CardView 容器（corner 14dp，阴影） | 否 | KEEP（HIDE 会连卡面消失） |
| `card_img` | **卡面** CircleNetworkImageView（328×202、960:591、centerCrop） | 否 | 替换目标，KEEP |
| `card_sign` | "默认卡"标签（10sp 白，top-end，gone） | 否 | 信息，默认 KEEP |
| `card_status` | 进站状态（10sp，bottom-start，gone） | 否 | 信息，默认 KEEP（对公交重要，建议 KEEP） |

### 3.3 门禁/车钥匙 NFC 卡面（`finui_nfc_card_face_view.xml`）
| id | 用途 | 可点击 | 建议 |
|---|---|---|---|
| `iv_card_img` | **卡面** CircleNetworkImageView（328:202、radius 14dp、centerCrop） | 否 | 替换目标，KEEP |
| `rnTitleLogo` | 左上 logo+标题容器 | 否 | 容器，KEEP |
| `iv_logo` | 品牌/学校/车厂 logo（36dp） | 否 | 信息，默认 KEEP |
| `tv_title` | 卡名/学生名（16sp 白） | 否 | 信息，默认 KEEP |
| `encrypt_label` | "加密卡"标签（gone） | 否 | 安全信息，默认 KEEP；隐藏无功能影响（可 HIDE） |
| `tv_plate_numb` | 车牌号/卡号（gone，蓝底） | 否 | 信息，默认 KEEP（车钥匙建议 KEEP） |
| `tv_card_sign` | "默认卡"标签（gone，top-end） | 否 | 信息，默认 KEEP |
| `keyValidView` | 车钥匙有效期（gone，bottom-start，14dp） | 否 | 信息，默认 KEEP（车钥匙建议 KEEP） |

### 3.4 刷卡/消费页卡框（程序化创建，`cardframe/e.java` + 子类）
| id/字段 | 用途 | 可点击 | 建议 |
|---|---|---|---|
| `nfc_consume_card_area` | `NfcCardFrameLayout` 卡框容器（背景 `lm.d.f73570m0`） | 否 | KEEP |
| `nfc_consume_card_img` | **卡面** CircleNetworkImageView | 否 | 替换目标，KEEP |
| `nfc_consume_card_sign` | 默认卡标签（右上，`mSignTv`） | 否 | 信息，KEEP |
| `nfc_consume_animation_view` | Lottie 动画覆盖层 | 否 | 装饰，可 HIDE（注意不要影响刷卡动画功能） |
| `nfc_consume_retry_active_view` | 重试/维修/补卡激活视图（含 FINUIButton） | **是（功能）** | **绝不隐藏** |
| `nfc_consume_tv_digest` | 银行卡卡号后 4（Bank 子类动态添加） | 否 | 信息，KEEP |
| `nfc_consume_tv_pay_text` | "银联/支付"标识文案（Bank 子类） | 否 | 信息，KEEP |
| `mStationStatus` | 进站状态（公交等） | 否 | 信息，KEEP |
| `titleInfoView/tvTitle`（Door/EBike/CarKey/SmartComposite） | 卡名/子标题 | 否 | 信息，KEEP |
| `mCarKeyValidView`（CarKey） | 钥匙有效期 | 否 | 信息，KEEP |
| `mCarCoverInfoView`（CarKey） | 车辆信息容器 | 否 | 信息，KEEP |
| `tvLabel/tvSubDoorTitle/tvCarCardCount/busWarnIcon/carCountLayout`（SmartComposite） | 标签/门数/卡数/告警 | 部分点击? | 信息，KEEP；告警图标建议 KEEP |
| `mBalanceTv/mWalletIdTv/mBalanceIconTv`（DCEP） | 余额/钱包 ID | 否 | 信息，KEEP |
| `mEncryptLabel`（Door） | 加密卡标签 | 否 | 信息，KEEP |

### 3.5 首页叠卡（`item_index_card_package.xml`）
`clIndexCardPackageItem`(根/点击容器)、`cnivCardOne`/`cnivCardTwo`(**双层卡面**，比例 1.63、radius 13.33dp、scaleType fitXY)、`tv_card_name`/`tv_card_name_two`(卡名 16sp 白)、`vCardPackageBg`(阴影背景装饰)、`vClickable`(**点击热区→功能，绝不隐藏**)。
→ 若在首页替换，注意**双层卡面都要替换**（cnivCardOne 是上层主卡、cnivCardTwo 是下层露出卡）。

---

## 4. 卡面比例 / 圆角（回答确认）
| 场景 | 比例(宽:高) | 圆角 | 依据 |
|---|---|---|---|
| 刷卡/消费页卡框 | **1.62**（`width/1.62` 计算高度） | `d.X`（dimension，常规 14dp 级；`setRoundPx`+`setImgType(576)`） | `com.nearme.pay.utils.i` CardSizeHelper；`NfcCardFaceAutoSizeHelper.DEFAULT_CARD_RATIO=1.62f` |
| 详情大卡 `big_card_face_view` | **328:202 = 1.6238**（内层还有 `h,960:591`=1.6244） | **14dp**（CardView `cardCornerRadius`） | 布局 XML |
| 门禁/车钥匙 NFC 卡面 `finui_nfc_card_face_view` | **328:202 = 1.6238** | **14dp**（`app:radius`） | 布局 XML；`updateCardViewSize(1.62f)` |
| 首页叠卡 `item_index_card_package` | **1.63** | **13.33dp** | 布局 XML |
| 卡包列表缩略图 `small_card_face_layout` | 80×50 = **1.6**（fitCenter） | **4dp** | 布局 XML |
| 出图建议 | 统一按 **1.62~1.623** 出图（如 **984×606** 或 960×591×)，小图 480×300/240×150 | 由容器裁切，14dp | — |

> 与荣耀 336:208(=1.6154) 不同；**OPPO 卡面更宽（1.62~1.63）**，直接复用荣耀素材会被裁边，需要按 1.62 重新出图。

---

## 5. 原生"卡面主题"（CardTheme）覆盖风险（回答 Q6）
- 门禁卡：`com.nearme.wallet.entrance.view.CardThemeActivity` + `CardThemeAdapter` + `item_card_theme_show.xml`；主题来自 `com.nearme.nfc.domain.door.rsp.CardTheme`/`CardThemeGroup`/`CardThemeCoverRsp`，**服务端返回 `getCardImg()`（远程 URL）+ `getCardThemeId()`**；选中保存后写回 `EntranceCard.setLocalImgPath("<本地文件路径>")`（`CardThemeActivity` line 136-138 清空/设置 localImgPath；主题下载后落本地）。
- 公交卡：`com.nearme.wallet.bus.ui.BusCardThemeChooseActivity`（同一套 CardTheme 模型）。
- 显示侧：`AutoSwitchListAdapter`/`DefaultCardListAdapter`/`LatestCardListAdapter`/`AutoSystemSwitchListAdapter` 等会优先用 `entranceCard.getLocalImgPath()`（非空时）→ 再走 `CircleNetworkImageView.setImageUrl/local 路径`（读适配器可见 line 110-120）；即**原生主题会把卡面替换成本地文件**。
- 对模块的影响：
  1. 用户若先用原生主题，卡面 URL/本地路径会变化 → 我们的 url→身份映射（H-C 记录的旧 URL）可能失配 ⇒ **H-C 需同时观测 `EntranceCard.getLocalImgPath()`/`getCardImg()` 与 `CardTheme`**，或统一按身份（aid/appCode/cardType）匹配，不依赖 URL 唯一性。
  2. 门禁/车钥匙详情直接用 `FinUINfcCardFaceView#showCardImg`，其内部亮度分析 `CardCoverSettingUtil.loadBitmapSync(ctx,url)` 会**下载原图**计算文字颜色；若我们只替换显示图，文字颜色可能仍按原图（深/浅）→ 自定义深色卡面时文字可能不可读。**需要 H-E**。
  3. 结论：原生主题不会"阻止"我们的替换（H-A 在 CircleNetworkImageView 层生效），但为了正确性需按身份匹配 + 处理亮度颜色，并在用户切换原生主题后刷新我们的注册表。

---

## 6. 亮度/文字颜色（新发现，须纳入 hook 方案）
- 类：`com.finshell.finui.utils.CardCoverSettingUtil`（Kotlin，Companion）；常量 `BRIGHTNESS_THRESHOLD=230`、`BRIGHTNESS_TYPE_HSL=0`/`HSV=1`。
- 公开 API：`analyzeCarKeyCoverbright(...)`、`fetchBrightnessByCallback(ctx,url,rect,type,onResult)`、`fetchBrightnessUnderTextViewByCallback(...)`、`fetchBrightnessUnderTextViewByObservable(ctx,url,CircleNetworkImageView,TextView,onResult)`、`setCommonTvColor(tv,isWhite)`、`setDoorIcon(...)`。
- 内部关键私有：`loadBitmapSync(Context,String): Bitmap`（Glide 同步下载），`bitmap2Brightness(bitmap,w,h,rect,type)`，`isWhitePicture(int)`。
- 被以下卡面使用：`FinUINfcCardFaceView#showCardImg`（门禁/车钥匙/EID等）、`DoorCardFrameVH`(line 136)、`CarKeyCardFrameVH`(line 217)、`EBikeCardFrameVH`(line 91) 等 → 按卡面亮度决定标题/标签/logo 黑白色。
- **对模块**：替换显示图后必须让亮度分析用我们的图（否则文字颜色错），推荐 hook `CardCoverSettingUtil$Companion#loadBitmapSync`：url 命中已配置卡 → 返回我们的 Bitmap（不访问网络），其余原样。该点是 OPPO 版"源替换点"（类比荣耀 `mv1#a`），可同时解决离线/网络流量问题。

---

## 7. 推荐 hook 方案（legacy Xposed，fail-open）

### H-A（核心，优先实现）`CircleNetworkImageView` String 加载方法
目标类：`com.finshell.finui.widget.imageview.CircleNetworkImageView`
- `public void setImageUrl(java.lang.String)`
- `public void setImageBitmap(java.lang.String)`（String 重载；`FinSmallCardFaceView`/`FinUINfcCardFaceView.showCardImg`/SuperCard/SmartComposite 等走它）
- `public void setImageUrlByTemp(java.lang.String)`（卡包列表）
- `public void f1(java.lang.String, int, boolean)`（刷卡/消费页所有 FrameVH）
处理：`url` 不在"已配置卡 URL 集合"→ 调原方法；命中 → 跳过原方法，用 `ContentResolver` 读取模块 provider 的图片（按 1.62 比例、圆角由控件裁）→ `ImageView.setImageBitmap(Bitmap)`（继承自 ImageView，避开其 String 重载）；同时把该 ImageView 放入 WeakReference 注册表以便配置变更后重建。所有异常 → 调原方法 + 日志。
- 风险：content:// 交给原 Glide 的兼容性未验证（设备侧验证）；备用方案 `setImageBitmap(Bitmap)` 已可完全自控。

### H-B（辅助/上下文，不替代 H-A）Fin* 卡面控件
- `FinBigCardFaceView#setCardFaceUrl(String)`、`FinSmallCardFaceView#setImageBitmap(String)`、`FinCustomCardView#setCardFaceUrl(String)`、`FinUINfcCardFaceView#showCardImg(String,String)` / `(String,String,boolean)` / `(String,String,boolean,int)`
- 用途：记录"容器→卡片上下文"（便于按卡身份查配置/刷新）、**离线补图**（`setCardFaceUrl` 有网络 gate，离线不执行 → 在 H-B 内判断离线直接用本地图 setImageBitmap）、以及刷新时直接对容器内 `getCardFaceView()` 重载。
- 注意 **不要 skip `showCardImg`**（会丢标题/标签/亮度逻辑）。

### H-C（身份映射，只读观测，不改返回值）
hook 以下 getter，记录 `url → (aid, appCode, cardType, subCardType, displayName)`：
- `com.nearme.wallet.db.NfcCardDetail#getCardImg`、`com.nearme.wallet.db.NfcCard#getCardImg`
- `com.nearme.nfc.domain.transit.rsp.CardDetailRsp#getCardImg`、`com.nearme.pay.domain.rsp.CardDetailRspVo#getCardImg`、`com.nearme.pay.domain.rsp.PayCardInfo#getCardImg`
- `com.finshell.wallet.supercard.net.rsp.CardInfo#getCardImg`
- `com.nearme.wallet.entrance.bean.EntranceCard#getCardImg`/`getLocalImgPath`、`com.nearme.nfc.domain.door.rsp.CardTheme#getCardImg`
（在同一对象上调用其它 getter 安全；不要改动任何返回值。）

### H-E（必要，文字颜色/亮度）`CardCoverSettingUtil$Companion#loadBitmapSync`
- url 命中已配置卡 → 返回我们 Bitmap（尺寸按原图比例）；否则调原方法。避免文字颜色按原图计算、避免额外网络请求。

### H-D（兜底，若 H-A 在插件/混淆下失效）
在具体绑定处直接替换 holder 内控件：
- `CardPackageListAdapter#onBindViewHolder` 或 `a#a0`（拿到 ModelData.aid + holder.f33470a）
- `BusDetailActivity#e7`（FinBigCardFaceView#setCardFaceUrl）
- `NfcCardDetailRvAdapter$CardViewHolder#X(CardDetailRspVo)`
- `DoorDetailFragment#l1(url,cardId)` / `NfcCarKeyDetailFragment#G0(url,name)` / `BlueCarKeyDetailFragment` 同 / `SchoolStudentView#d(cardImg,logo)`
- `consume.cardframe.e#bindData(NfcCard,int)`（子类 onBindSpecialData 里 set）
- `SuperCardGuideView#setCardImg`、`com.finshell.wallet.supercard.ui.a0#d`

### H-F（可选，桌面小组件）
`com.nearme.pay.business.consume.seedling.SwipeSeedlingUtilsKt#downloadImage`/`drawableToLocalUrl` 或 `SeedlingConsumeManager` 数据侧替换 imageUrl。二期。

### 刷新与 fail-open
- 维护 `WeakReference<CircleNetworkImageView>` 注册表；配置变更 → 对每个 view 重新走 H-A 替换逻辑（`setImageUrl(原url)` 触发 or 直接 setBitmap）。
- 所有 hook `try/catch`，类找不到仅 `XposedBridge.log`；异常一律调原方法；不修改 getter 返回值；不读写钱包 DB；不伪造网络请求。
- 注入范围：按 package `com.finshell.wallet`，legacy Xposed 全进程注入即可（卡面在主进程）。

---

## 8. 测试计划（静态→动态交接）
1. 卡包列表：滚动/切换 tab/进入详情返回，卡面替换后无错位、圆角正确（列表 80×50，1.6 比例）、overlay 不遮挡。
2. 刷卡页：切换各卡类型（银行/公交/门禁/车钥匙/eBike/校园/SuperCard/DCEP/EID），卡面替换、默认卡标签/进站状态/有效期/余额等 overlay 正常、亮度文字颜色可读（H-E）。
3. 详情页：公交、门禁、车钥匙、校园、银行、SuperCard 分别验证；离线场景（H-B 补图）。
4. 原生卡面主题：先在原生设置主题（门禁/公交），再启用模块，确认替换仍生效且刷新正常（身份匹配而非 URL 匹配）。
5. 刷新：切换配置后不重启 App 能否刷新（注册表重载）；Activity 重建/进程重启后正常。
6. 风险回归：支付/刷卡主流程不异常（H-A 仅替换显示图，不触碰 nfcCard 数据）；检查日志无 hook 异常。
7. 兼容性：不同 ROM/colorOS 版本首页 tab 可能不同（tab 由服务端下发 `CARDPACKAGE`），确认首页卡面包是否命中。
8. 桌面小组件（若一期就做）：H-F 验证。

---

## 9. v2.1 勘误与补充（二轮复核后，**重要，请 module-dev 按此实现**）

1. **H-A 必须包含低层 `e1`**：部分卡面直接调用 `CircleNetworkImageView#e1(String,int,CircleNetworkImageView$i)`（绕过 setImageUrl）：
   - `com.nearme.wallet.entrance.adapter.eBikeAdapter.viewHolder.EBikeBigPicViewHolder#V(...)`（约 line 100-120）：`mThemeImg = findViewById(iv_card_img)` 后 **`mThemeImg.e1(((d30.a)bean).c(), -1, listener)`**；同 holder 内还有 `FinUINfcCardFaceView cardFaceView`（`adjustDisplayNameWidth`、`setCardIconAndName(name,...)`）。
   - `com.nearme.wallet.bus.ui.BusShifSelectActivity:402`：`this.f41098r.e1(this.f41096p.getCardImg(), -1, new b())`。
   - ⇒ H-A 目标方法清单补全为：`setImageUrl(String)`、`setImageBitmap(String)`、`setImageUrlByTemp(String)`、`f1(String,int,boolean)`、**`e1(String,int,CircleNetworkImageView$i)`**、`i1(String,int,CircleNetworkImageView$i)`；可选补 `j1(String,String)`、`g1(String,int,boolean,CircleNetworkImageView$i)`、`h1(String,CircleNetworkImageView$i)`。这些全部是 public，签名稳定。
2. **eBike 详情链路**：`com.nearme.wallet.entrance.fragment.EBikeKeyDetailFragment`（extends BaseCardDetailFragment，RecyclerView）→ `com.nearme.wallet.entrance.adapter.eBikeAdapter.EBikeKeyDetailAdapter` → `EBikeBigPicViewHolder#V(int, d30.a, int)`；卡面容器 `FinUINfcCardFaceView`（`qm.e.G0`）+ 实际图片 `CircleNetworkImageView`（id `iv_card_img`）+ `e1(url)` 加载；overlay 同 `finui_nfc_card_face_view`（iv_logo/tv_title/tv_card_sign/tv_plate_numb/keyValidView）。
3. **EID 特殊（非 H-A 覆盖，建议产品排除）**：`com.nearme.eid.EidMyCardActivity#H3()`：
   - 证照背景 `f32070y`（`pm.c.f77804e`）通过 `vk.b.a().w6(iv, "https://fast-wallet.finzfin.com/.../eid_bg_id_card.webp")` 加载 → 实现 `com.nearme.ocs.resources.OCSComponentProviderImpl#w6(ImageView,String)`，**内部新起 Glide 任务（g.m(new h(...))）直接进 ImageView，不经过 CircleNetworkImageView**；
   - 证件照 `f32068w` 是本地 `Bitmap`（`setImageBitmap(Bitmap)`），属敏感证件照。
   - ⇒ EID 若要换背景需额外 H-G：hook `OCSComponentProviderImpl#w6/#W6/#e7/#N9`（都在 `com.nearme.ocs.resources`），但 EID 为法定证件，建议**不做卡面替换**。
4. **`com.nearme.ocs.resources.OCSComponentProviderImpl`（IOCS 组件）是第二套图片加载器**：`w6/W6`(Glide→ImageView)、`e7`(带圆角/尺寸)、`N9`(双 URL 深浅色)、`l2(CircleNetworkImageView,String)`→`view.j1(url,"")`、`c6/k7/R1`(Lottie)。除 EID 外，部分活动页/运营图也走它（非卡面主链路）。若需求覆盖这些页面，H-G 一并加。
5. **`s.m(Context)` 语义修正**：`com.finshell.finui.utils.s.m` = "Activity 未 finishing/destroyed" 的守卫（并非功能开关）；卡面显示时恒为 true。`u.i(Context)`（FinBigCardFaceView 里）= 网络可用性，离线会跳过加载（见 §0 网络 gate）。
6. **原生卡面主题写回确认**：`CardThemeActivity#f4()` line 466：`entranceCardE.setLocalImgPath(this.f43539z.getCardImg())`（即把主题的 `cardImg` 写入 EntranceCard.localImgPath；line 138/521 清空）。`BusCardThemeChooseActivity` 预览用 `f40783v : CircleNetworkImageView`。⇒ H-C 需同时观测 `EntranceCard#getLocalImgPath()`（值可能是 URL 或本地路径）与 `CardTheme#getCardImg()`；按身份匹配，不依赖 URL。
7. **多进程/插件补充**：assets/plugins 实测 = 4×`com.finshell.wallet.drouter.*` + 1×`com.finshell.shadow.manager`；`com.nearme.wallet.shadow.*` 为 Shadow 插件框架；卡面类均在主 dex。
8. **精确路径修正**：`ODoorDetailFragment` = `com.finshell.wallet.odoor.ODoorDetailFragment`（line 295 findViewById(themeImgLayout)；line 240-249 `E0()` 内 `showCardLogo(0)/setEncryptLabelVisibility(false)/setCardSignVisibility(...)/showCardImg(f27470h,"",false,0)`）；`SwitchPosSettingActivity` = `com.nearme.wallet.autoswitch.SwitchPosSettingActivity`（line 404 findViewById(km.f.f69006sa) 即 FinUINfcCardFaceView；line 582 `showCardImg(f38975h,str,z11,i11)`）。
9. **H-A 覆盖不到的卡面 surface（明确清单）**：①桌面 Seedling 小组件（`SwipeSeedlingUtilsKt#downloadImage`）；②EID 背景（`OCSComponentProviderImpl#w6`）；③任何以 `Bitmap` 直接 `setImageBitmap(Bitmap)` 的卡面（EID 证件照、二维码/条码生成图）。其余 App 内卡面 surface 均可被 H-A 覆盖。
10. **H-E 精确签名**（`com.finshell.finui.utils.CardCoverSettingUtil$Companion`，Kotlin 内部类，均 public final）：
    - `private final android.graphics.Bitmap loadBitmapSync(android.content.Context, java.lang.String)` ← **首选 hook 点**（url→返回我们的 Bitmap）
    - `public final void fetchBrightnessByCallback(Context,int,String,Rect,Function1)`
    - `public final void fetchBrightnessUnderTextView(Context,int,String,CircleNetworkImageView,TextView,Function1)`
    - `public final void fetchBrightnessUnderTextViewByCallback(Context,String,CircleNetworkImageView,TextView,DoorCardBrightnessListener)`
    - `public final void fetchBrightnessUnderTextViewByObservable(Context,String,CircleNetworkImageView,TextView,Function1)`
    - 兜底：hook 上述 public 方法在命中自定义卡时调用 `onResult.invoke(<固定亮度或我们图片的亮度>)`（注意 Function1 参数类型 Boolean(Integer)/Integer，按方法而定）。
11. **`FinSmallCardFaceView` 在本版本中实际未被使用**：全仓库无代码引用、所有 layout XML 中也无该控件（列表卡面实际是 `item_card_package_list.xml` 里 include `small_card_face_layout` 的根 `CircleNetworkImageView`）。→ H-B 可省略该控件；保留它只为版本兼容冗余。

---

## 10. 未决/下一轮
1. eBike/EID/第三方码的详情页精确类（FrameVH 已确认；详情 Activity/School 类似需补）。
2. 门禁 ICCOA/ODOOR/BlueCarKey 的 `showCardImg` 参数语义（url/title/brightnessIdx）与 overlay 显隐完整映射。
3. 首页 `item_index_card_package` 的实际 inflater（主 dex 未见；可能是旧版/插件；若重要需在设备侧观察）。
4. `s.m(Context)` 等 gate 的具体含义（影响门禁/车钥匙卡面是否显示）。
5. content:// 在 OPPO Glide 的实际兼容性（设备侧 verifier）。
6. 原生主题把 URL 换成本地文件路径后，`CircleNetworkImageView` 对本地路径的加载方式（Glide `load(String)` 处理 `file://` vs 裸路径）需设备验证。
