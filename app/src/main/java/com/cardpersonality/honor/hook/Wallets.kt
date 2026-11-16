package com.cardpersonality.honor.hook

/**
 * Round-11 multi-wallet registry.
 *
 * Candidate wallet packages are all listed in AndroidManifest `<queries>` (targetSdk 36 package
 * visibility: without it PackageManager cannot see other apps). Only Honor has a native hook
 * engine; every other candidate is treated as "oppo" (欧加/OPPO/realme family) and uses the OPPO
 * engine (fail-open; classes only exist in the real OPPO wallet, so unknown candidates simply don't
 * match and nothing changes).
 *
 * Storage keys: legacy (Honor) `"<type>:<identity>"` and wallet-scoped
 * `"<package>|<type>:<identity>"` for other wallets.
 */
data class WalletTarget(
    val pkg: String,
    val label: String,
    val kind: String,
)

object Wallets {
    const val HONOR = "com.hihonor.wallet"

    // OPPO / 欧加 family candidates (the real one is com.finshell.wallet; others are historical
    // or region variants and are only tracked for detection/visibility).
    const val OPPO = "com.finshell.wallet"
    const val OPPO_COLOROS = "com.coloros.wallet"
    const val OPPO_PLUS = "com.oplus.wallet"
    const val ONEPLUS = "com.oneplus.wallet"
    const val NEARME = "com.nearme.wallet"

    const val KIND_HONOR = "honor"
    const val KIND_OPPO = "oppo"

    const val KEY_SEPARATOR = "|"

    /** Packages whose card faces use the OPPO/欧加 engine (and the 328:202 geometry). */
    val OPPO_PACKAGES: Set<String> = setOf(OPPO, OPPO_COLOROS, OPPO_PLUS, ONEPLUS, NEARME)

    val HONOR_TARGET = WalletTarget(HONOR, "荣耀钱包", KIND_HONOR)
    val OPPO_TARGET = WalletTarget(OPPO, "欧加钱包", KIND_OPPO)
    val COLOR_OS_TARGET = WalletTarget(OPPO_COLOROS, "ColorOS 钱包", KIND_OPPO)
    val OPLUS_TARGET = WalletTarget(OPPO_PLUS, "OPPO 钱包", KIND_OPPO)
    val ONEPLUS_TARGET = WalletTarget(ONEPLUS, "OnePlus 钱包", KIND_OPPO)
    val NEARME_TARGET = WalletTarget(NEARME, "欧加钱包（旧版）", KIND_OPPO)

    /** All candidate wallets, known even when not installed (show as not-installed in the panel). */
    val ALL: List<WalletTarget> = listOf(
        HONOR_TARGET,
        OPPO_TARGET,
        COLOR_OS_TARGET,
        OPLUS_TARGET,
        ONEPLUS_TARGET,
        NEARME_TARGET,
    )

    fun target(pkg: String?): WalletTarget? = ALL.firstOrNull { it.pkg == pkg }

    fun isKnown(pkg: String?): Boolean = target(pkg) != null

    fun isHonor(pkg: String?): Boolean = pkg == HONOR

    fun isOppoFamily(pkg: String?): Boolean = pkg != null && pkg in OPPO_PACKAGES

    fun kindOf(pkg: String?): String = when {
        isHonor(pkg) -> KIND_HONOR
        isOppoFamily(pkg) -> KIND_OPPO
        else -> "unknown"
    }

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
