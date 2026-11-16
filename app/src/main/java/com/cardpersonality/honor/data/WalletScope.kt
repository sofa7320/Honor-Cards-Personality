package com.cardpersonality.honor.data

/**
 * Round-10 multi-wallet storage helpers.
 *
 * Config/report storage uses the legacy `<type>:<identity>` fileKey for the Honor wallet (backward
 * compatible), and a wallet-scoped `"<package>|<type>:<identity>"` key for other wallets (e.g.
 * com.finshell.wallet). The hook resolves the scoped key first and falls back to the legacy key for
 * Honor only.
 */
object WalletScope {
    const val HONOR = "com.hihonor.wallet"
    const val OPPO = "com.finshell.wallet"
    const val SEPARATOR = "|"

    fun normalize(walletPackage: String?): String =
        walletPackage?.takeIf { it.isNotBlank() } ?: HONOR

    fun isHonor(walletPackage: String?): Boolean = normalize(walletPackage) == HONOR

    fun isOppo(walletPackage: String?): Boolean = normalize(walletPackage) == OPPO

    /** Storage key: Honor keeps the legacy unprefixed key; other wallets get a package prefix. */
    fun configFileKey(key: CardKey, walletPackage: String?): String {
        val pkg = normalize(walletPackage)
        return if (pkg == HONOR) key.fileKey else "$pkg$SEPARATOR${key.fileKey}"
    }

    /** Wallet package encoded in a storage key (legacy unprefixed keys are Honor keys). */
    fun walletOfFileKey(fileKey: String): String {
        val idx = fileKey.indexOf(SEPARATOR)
        if (idx <= 0) return HONOR
        val pkg = fileKey.substring(0, idx)
        return if (pkg == HONOR || pkg == OPPO) pkg else HONOR
    }

    /** Legacy part of a storage key (`<type>:<identity>`). */
    fun innerFileKey(fileKey: String): String {
        val idx = fileKey.indexOf(SEPARATOR)
        if (idx <= 0) return fileKey
        return if (walletOfFileKey(fileKey) != HONOR) fileKey.substring(idx + 1) else fileKey
    }

    /** Human label for a wallet package. */
    fun labelOf(walletPackage: String?): String = when (normalize(walletPackage)) {
        HONOR -> "荣耀钱包"
        OPPO -> "欧加钱包"
        else -> normalize(walletPackage)
    }

    fun kindOf(walletPackage: String?): String = when (normalize(walletPackage)) {
        HONOR -> "honor"
        OPPO -> "oppo"
        else -> "unknown"
    }
}
