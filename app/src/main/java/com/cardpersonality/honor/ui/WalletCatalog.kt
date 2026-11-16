package com.cardpersonality.honor.ui

import android.content.Context
import android.content.pm.PackageManager
import com.cardpersonality.honor.data.CardTypes

/**
 * Wallet packages the panel can recognise, plus their per-type "elements drawn by the wallet"
 * catalogs. The catalogs are keyed by **wallet package name + card type** so a multi-wallet device
 * can show only the toggles that belong to the wallet(s) actually installed/reporting.
 *
 * 荣耀 (Honor) ids come from work/re/transit/FINDINGS.md + work/re/cards/FINDINGS.md.
 * 欧加 (OPPO/OnePlus) placeholders are intentionally empty until work/re/oppo/FINDINGS.md is filled
 * in; the UI then simply shows "no elements analysed yet" for that wallet instead of wrong ids.
 */
data class WalletElementCatalog(
    val packageName: String,
    val displayName: String,
    val elementIdsByType: Map<String, List<String>>,
    val labels: Map<String, String>,
    /**
     * Fallback ids shown for any card type that has no type-specific entry. Used by OPPO, where the
     * RE pass certified one shared "information-only, safe to hide" set across pages instead of a
     * per-type split. Never contains functional/clickable views.
     */
    val defaultIds: List<String> = emptyList(),
)

object WalletPackages {
    const val HONOR = "com.hihonor.wallet"
    const val OPPO = "com.finshell.wallet"

    data class InstalledWallet(
        val packageName: String,
        val displayName: String,
        val versionName: String?,
    )

    private val KNOWN = listOf(
        HONOR to "荣耀钱包",
        OPPO to "欧加钱包",
    )

    /** Supported wallet versions this release is designed/tested against. */
    const val HONOR_SUPPORTED_VERSION = "11.0.3.305"
    const val OPPO_SUPPORTED_VERSION = "5.52.0"

    fun installed(ctx: Context): List<InstalledWallet> {
        val pm = ctx.packageManager
        return KNOWN.mapNotNull { (pkg, label) ->
            val info = try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } ?: return@mapNotNull null
            @Suppress("DEPRECATION")
            InstalledWallet(pkg, label, info.versionName)
        }
    }

    fun isInstalled(ctx: Context, packageName: String): Boolean =
        installed(ctx).any { it.packageName == packageName }

    /** "com.hihonor.wallet", "com.a / com.b", or a hint when none is installed. */
    fun detectedPackagesLabel(ctx: Context): String {
        val packages = installed(ctx).map { it.packageName }
        return if (packages.isEmpty()) {
            "未检测到已安装的受支持钱包"
        } else {
            packages.joinToString(" / ")
        }
    }

    fun displayName(packageName: String): String = when (packageName) {
        HONOR -> "荣耀钱包"
        OPPO -> "欧加钱包"
        else -> packageName
    }

    fun catalog(packageName: String): WalletElementCatalog = when (packageName) {
        OPPO -> OPPO_CATALOG
        else -> HONOR_CATALOG
    }

    // ------------------------------------------------------------------ catalogs

    private val HONOR_IDS: Map<String, List<String>> = mapOf(
        // Detail page station chip; home swipe has no separate face overlay for transit.
        CardTypes.TRANSIT to listOf("tv_card_label"),
        CardTypes.BANK to listOf(
            "tv_card_name",
            "tv_bank_code",
            "iv_honor_pay_badge",
        ),
        CardTypes.CAMPUS to listOf(
            "iv_overlay",
            "cl_campus_card_container",
        ),
        CardTypes.ACCESS to listOf(
            "gradient_view",
            "iv_home",
            "tv_card_name",
        ),
        CardTypes.CARKEY to listOf(
            "tv_card_name",
            "iv_home",
            "unactivated",
            "label_layout_nfc",
            "iv_tag_nfc",
            "tv_card_label_nfc",
            "label_layout_ble",
            "iv_tag_ble",
            "tv_card_label_ble",
        ),
        CardTypes.EBIKE to listOf(
            "iv_ebike_icon",
            "tv_card_name",
            "iv_home",
        ),
    )

    private val COMMON_LABELS: Map<String, String> = mapOf(
        "tv_card_name" to "卡片名称",
        "tv_card_label" to "站点状态标签",
        "tv_bank_code" to "银行卡号",
        "iv_honor_pay_badge" to "荣耀支付标识",
        "iv_overlay" to "校园卡花纹",
        "cl_campus_card_container" to "校园学生信息",
        "gradient_view" to "顶部渐变遮罩",
        "iv_home" to "主卡图标",
        "unactivated" to "未激活标签",
        "label_layout_nfc" to "NFC 标签区域",
        "iv_tag_nfc" to "NFC 图标",
        "tv_card_label_nfc" to "NFC 标签文字",
        "label_layout_ble" to "蓝牙标签区域",
        "iv_tag_ble" to "蓝牙图标",
        "tv_card_label_ble" to "蓝牙标签文字",
        "iv_ebike_icon" to "电动车图标",
        "hw_tip" to "卡片提示",
    )

    private val HONOR_CATALOG = WalletElementCatalog(
        packageName = HONOR,
        displayName = "荣耀钱包",
        elementIdsByType = HONOR_IDS,
        labels = COMMON_LABELS,
    )

    /**
     * OPPO/欧加 wallet overlay elements certified by RE v2 (work/re/oppo/FINDINGS.md) as
     * INFORMATION-ONLY and safe to hide. Default state is KEEP for every id; the UI only exposes a
     * KEEP/HIDE switch.
     *
     * Sources:
     *  - 详情大卡 FinBigCardFaceView: card_sign（默认卡标签）, card_status（进站状态）
     *  - 门禁/车钥匙 FinUINfcCardFaceView: iv_logo, tv_title, encrypt_label, tv_plate_numb,
     *    tv_card_sign, keyValidView（有效期信息）
     *  - 刷卡页卡框: nfc_consume_card_sign, nfc_consume_animation_view（Lottie 装饰）,
     *    nfc_consume_tv_digest, nfc_consume_tv_pay_text（信息）
     *  - 卡包列表 item: tv_label, tv_name, tv_last_digest, single_tag, tv_content
     *
     * FUNCTIONAL controls are forbidden here and filtered defensively by AppElements.FUNCTIONAL_IDS:
     * btn_function / iv_action / vClickable / nfc_consume_retry_active_view and any button/hotspot.
     */
    private val OPPO_SAFE_IDS: List<String> = listOf(
        // 详情大卡
        "card_sign",
        "card_status",
        // 门禁 / 车钥匙卡面
        "iv_logo",
        "tv_title",
        "encrypt_label",
        "tv_plate_numb",
        "tv_card_sign",
        "keyValidView",
        // 刷卡页卡框
        "nfc_consume_card_sign",
        "nfc_consume_animation_view",
        "nfc_consume_tv_digest",
        "nfc_consume_tv_pay_text",
        // 卡包列表 item
        "tv_label",
        "tv_name",
        "tv_last_digest",
        "single_tag",
        "tv_content",
    )

    private val OPPO_LABELS: Map<String, String> = mapOf(
        "card_sign" to "默认卡标签",
        "card_status" to "进站状态",
        "iv_logo" to "卡面 Logo",
        "tv_title" to "卡片标题",
        "encrypt_label" to "加密标签",
        "tv_plate_numb" to "车牌号",
        "tv_card_sign" to "卡片签名",
        "keyValidView" to "有效期信息",
        "nfc_consume_card_sign" to "刷卡页卡标签",
        "nfc_consume_animation_view" to "刷卡动画装饰",
        "nfc_consume_tv_digest" to "刷卡摘要",
        "nfc_consume_tv_pay_text" to "支付提示文字",
        "tv_label" to "卡包标签",
        "tv_name" to "卡包名称",
        "tv_last_digest" to "最近交易摘要",
        "single_tag" to "单卡标签",
        "tv_content" to "卡包内容",
    )

    private val OPPO_CATALOG = WalletElementCatalog(
        packageName = OPPO,
        displayName = "欧加钱包",
        elementIdsByType = emptyMap(),
        labels = OPPO_LABELS,
        defaultIds = OPPO_SAFE_IDS,
    )
}

/** Wallet status as reported by the module's new `wallets` provider call (fallback: PackageManager). */
data class WalletStatus(
    val packageName: String,
    val label: String,
    val kind: String,
    val installed: Boolean,
    val versionName: String?,
)

data class WalletsSnapshot(
    val wallets: List<WalletStatus>,
    val activeWalletPackage: String?,
) {
    val installedPackages: List<String>
        get() = wallets.filter { it.installed }.map { it.packageName }
}

/**
 * Queries the module for installed/supported wallets and the last wallet that reported cards.
 * The call is read-only and never launches/touches any wallet app; any failure falls back to
 * PackageManager-based detection in [WalletPackages.installed].
 */
object WalletProbe {
    fun query(ctx: Context): WalletsSnapshot? {
        return try {
            val bundle = ctx.contentResolver.call(
                android.net.Uri.parse("content://" + com.cardpersonality.honor.data.FaceRepository.AUTHORITY),
                "wallets",
                null,
                null,
            ) ?: return null
            val json = bundle.getString("json") ?: return null
            val obj = org.json.JSONObject(json)
            val array = obj.optJSONArray("wallets") ?: return null
            val wallets = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val pkg = item.optString("package")
                    if (pkg.isEmpty()) continue
                    add(
                        WalletStatus(
                            packageName = pkg,
                            label = item.optString("label", WalletPackages.displayName(pkg)),
                            kind = item.optString("kind"),
                            installed = item.optBoolean("installed", false),
                            versionName = item.optString("versionName").takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            }
            if (wallets.isEmpty()) return null
            WalletsSnapshot(
                wallets = wallets,
                activeWalletPackage = obj.optString("activeWalletPackage").takeIf { it.isNotEmpty() },
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Optional discovery of installed wallet-like apps that are NOT in the supported list
     * (provider call "scan_wallets"). Informational only — such packages are never auto-hooked.
     */
    fun scanUnknownWallets(ctx: Context): List<WalletStatus> {
        return try {
            val bundle = ctx.contentResolver.call(
                android.net.Uri.parse("content://" + com.cardpersonality.honor.data.FaceRepository.AUTHORITY),
                "scan_wallets",
                null,
                null,
            ) ?: return emptyList()
            val json = bundle.getString("json") ?: return emptyList()
            val obj = org.json.JSONObject(json)
            val array = obj.optJSONArray("wallets") ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val pkg = item.optString("package")
                    if (pkg.isEmpty() || item.optBoolean("known", true)) continue
                    add(
                        WalletStatus(
                            packageName = pkg,
                            label = item.optString("label", pkg),
                            kind = "unknown",
                            installed = true,
                            versionName = item.optString("versionName").takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}


