package com.cardpersonality.honor.ui

import com.cardpersonality.honor.data.CardTypes

/**
 * Wallet-drawn overlay elements ("元素组件") per card family, keyed by wallet package.
 *
 * Source: RE findings (work/re/transit/FINDINGS.md, work/re/cards/FINDINGS.md,
 * work/re/oppo/FINDINGS.md) and the hook's enforced KEEP/HIDE whitelist. `hiddenAppElements`
 * stores these WALLET VIEW ID NAMES verbatim — the hook matches by id name inside the wallet
 * views, so do not localize them.
 *
 * Only these views are separate from the card-face bitmap (the face art itself is one server
 * image). Hiding is a view-level switch, default = KEEP (保持原样). Functional controls
 * (buttons / clickable hotspots) are filtered out unconditionally.
 */
object AppElements {

    /**
     * Never offered as a KEEP/HIDE switch: buttons, action icons, clickable containers and retry
     * hotspots. Filtered even if a wallet reports them or a catalog accidentally lists them.
     */
    private val FUNCTIONAL_IDS: Set<String> = setOf(
        "btn_function",
        "iv_action",
        "vClickable",
        "nfc_consume_retry_active_view",
    )

    fun known(packageName: String, type: String): List<String> {
        val catalog = WalletPackages.catalog(packageName)
        val ids = catalog.elementIdsByType[type] ?: catalog.defaultIds
        return ids.filterNot { it in FUNCTIONAL_IDS }
    }

    /** Known ids for the type first, then anything the hook reported (deduplicated). */
    fun forType(packageName: String, type: String, reported: List<String>): List<String> =
        (reported.filterNot { it in FUNCTIONAL_IDS } + known(packageName, type)).distinct()

    fun label(packageName: String, id: String): String =
        WalletPackages.catalog(packageName).labels[id] ?: CardTypeUi.appElementLabel(id)
}

/** Card families that have at least one element id in the Honor catalog (kept for docs/tests). */
internal val HONOR_ELEMENT_TYPES: List<String> = CardTypes.ALL.filter { type ->
    WalletPackages.catalog(WalletPackages.HONOR).elementIdsByType[type].orEmpty().isNotEmpty()
}
