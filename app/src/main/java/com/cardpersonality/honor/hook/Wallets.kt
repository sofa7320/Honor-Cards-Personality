package com.cardpersonality.honor.hook

/**
 * Round-10 multi-wallet registry.
 *
 * The module supports several wallet apps. Each wallet package gets its own hook engine and its own
 * config-key namespace so the same `type:identity` on different wallets cannot collide:
 *
 *   legacy (Honor, backward compatible)   "transit:A000…"
 *   new (wallet-scoped)                    "com.hihonor.wallet|transit:A000…"
 *                                          "com.finshell.wallet|transit:<id>"
 *
 * Unprefixed config keys are always treated as Honor wallet keys.
 */
data class WalletTarget(
    val pkg: String,
    val label: String,
    val kind: String,
    val engineClass: String,
)

object Wallets {
    const val HONOR = "com.hihonor.wallet"
    const val OPPO = "com.finshell.wallet"

    const val KIND_HONOR = "honor"
    const val KIND_OPPO = "oppo"

    /** Config-key separator between wallet package and the legacy "<type>:<identity>" fileKey. */
    const val KEY_SEPARATOR = "|"

    val HONOR_TARGET = WalletTarget(HONOR, "荣耀钱包", KIND_HONOR, "FaceHookEngine")
    val OPPO_TARGET = WalletTarget(OPPO, "欧加钱包", KIND_OPPO, "OppoFaceHookEngine")

    val ALL: List<WalletTarget> = listOf(HONOR_TARGET, OPPO_TARGET)

    fun target(pkg: String?): WalletTarget? = ALL.firstOrNull { it.pkg == pkg }

    fun isKnown(pkg: String?): Boolean = target(pkg) != null

    fun kindOf(pkg: String?): String = target(pkg)?.kind ?: "unknown"

    fun labelOf(pkg: String?): String = target(pkg)?.label ?: (pkg ?: "?")

    /** Wallet-scoped config key: "<package>|<type>:<identity>". */
    fun configKey(walletPackage: String, innerFileKey: String): String =
        if (walletPackage.isEmpty()) innerFileKey else "$walletPackage$KEY_SEPARATOR$innerFileKey"

    /** Splits a possibly wallet-scoped key; legacy keys map to the Honor wallet. */
    fun splitKey(key: String): Pair<String, String> {
        val idx = key.indexOf(KEY_SEPARATOR)
        if (idx <= 0 || idx >= key.length - 1) return HONOR to key
        val pkg = key.substring(0, idx)
        return if (isKnown(pkg)) pkg to key.substring(idx + 1) else HONOR to key
    }

    /** True when [key] already carries an explicit wallet package prefix. */
    fun hasWalletPrefix(key: String): Boolean {
        val idx = key.indexOf(KEY_SEPARATOR)
        return idx > 0 && isKnown(key.substring(0, idx))
    }
}
