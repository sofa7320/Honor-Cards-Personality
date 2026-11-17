package com.cardpersonality.honor.ui.util

import android.content.Context
import com.cardpersonality.honor.data.CardKey
import com.cardpersonality.honor.data.FaceRepository
import com.cardpersonality.honor.data.WalletScope

/**
 * Round 23: OPPO cards are replaced by matching the wallet's original face URL (the reported
 * identity may differ from the NFC identity the wallet renders). [FaceRepository.saveCard] already
 * looks the faceUrl up from the latest report and includes it in the bridge PUT; this helper is an
 * explicit, UI-originated fallback that re-sends the exact [faceUrl] carried by the edited card so
 * a stale/missing report entry cannot silently drop the URL-fallback match.
 */
object OppoFaceUrlPush {

    fun push(ctx: Context, key: CardKey, walletPackage: String?, faceUrl: String?) {
        if (faceUrl.isNullOrBlank()) return
        val wallet = WalletScope.normalize(walletPackage)
        if (!WalletScope.isOppo(wallet)) return
        try {
            val version = FaceRepository.cardVersion(ctx, key.type, key.id, wallet)
            if (version <= 0) return
            val export = FaceRepository.exportFileFor(ctx, key.type, key.id, wallet)
                ?: return
            if (!export.isFile || export.length() == 0L) return
            val scoped = WalletScope.configFileKey(key, wallet)
            com.cardpersonality.honor.data.bridge.PanelBridgeServer.ensureStarted(ctx)
            com.cardpersonality.honor.data.bridge.PanelBridgeServer.pushFace(
                ctx,
                scoped,
                version,
                export.readBytes(),
                faceUrl,
            )
            com.cardpersonality.honor.data.OpLog.record(
                ctx,
                "ui",
                "push-face-url",
                "key=$scoped v=$version url=${faceUrl.take(120)}",
            )
        } catch (_: Throwable) {
        }
    }
}
