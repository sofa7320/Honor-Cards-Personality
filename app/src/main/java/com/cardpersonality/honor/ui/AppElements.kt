package com.cardpersonality.honor.ui

import com.cardpersonality.honor.data.CardTypes

/**
 * Wallet-drawn overlay elements ("元素组件") per card family.
 *
 * Source: RE findings (work/re/transit/FINDINGS.md, work/re/cards/FINDINGS.md) and the hook's
 * enforced KEEP/HIDE whitelist (module-dev v3). `hiddenAppElements` stores these WALLET VIEW ID
 * NAMES verbatim — the hook matches by id name inside the wallet views, so do not localize them.
 *
 * Only these views are separate from the card-face bitmap (the face art itself is one server
 * image). Hiding is a view-level switch, default = KEEP (保持原样).
 */
object AppElements {

    private val KNOWN: Map<String, List<String>> = mapOf(
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

    private val LABELS: Map<String, String> = mapOf(
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

    fun known(type: String): List<String> = KNOWN[type].orEmpty()

    /** Known ids for the type first, then anything the hook reported (deduplicated). */
    fun forType(type: String, reported: List<String>): List<String> =
        (reported + known(type)).distinct()

    fun label(id: String): String = LABELS[id] ?: CardTypeUi.appElementLabel(id)
}
