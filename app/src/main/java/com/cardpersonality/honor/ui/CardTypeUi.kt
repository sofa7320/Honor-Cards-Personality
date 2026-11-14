package com.cardpersonality.honor.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.cardpersonality.honor.data.CardTypes
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.ContactsBook
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Tune

/** UI mapping for card families: icon + label + friendly name for wallet-drawn element ids. */
object CardTypeUi {
    fun icon(type: String): ImageVector = when (type) {
        CardTypes.BANK -> MiuixIcons.BankCards
        CardTypes.ACCESS -> MiuixIcons.Lock
        CardTypes.CAMPUS -> MiuixIcons.ContactsBook
        CardTypes.CARKEY -> MiuixIcons.Link
        CardTypes.EBIKE -> MiuixIcons.Pin
        CardTypes.TRANSIT -> MiuixIcons.BankCards
        else -> MiuixIcons.Tune
    }

    fun label(type: String): String = CardTypes.displayName(type)

    /** Friendly Chinese name for wallet overlay element ids once RE provides them. */
    fun appElementLabel(id: String): String = when {
        id.contains("name", true) -> "卡名称"
        id.contains("number", true) || id.contains("no", true) -> "卡号"
        id.contains("balance", true) || id.contains("money", true) -> "余额"
        id.contains("logo", true) -> "卡片 Logo"
        id.contains("status", true) -> "状态标签"
        id.contains("title", true) -> "标题"
        id.contains("time", true) -> "时间信息"
        else -> id
    }
}
