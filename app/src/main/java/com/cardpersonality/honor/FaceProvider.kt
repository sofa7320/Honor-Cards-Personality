package com.cardpersonality.honor

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import com.cardpersonality.honor.data.CardKey
import com.cardpersonality.honor.data.FaceRepository
import com.cardpersonality.honor.data.Hashing
import com.cardpersonality.honor.data.OpLog
import com.cardpersonality.honor.hook.Wallets
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only bridge between the app (config/UI) and the hook inside the wallet process.
 *
 * URI contract:
 *   content://com.cardpersonality.honor.face/card/<sha256hex(fileKey)>.jpg?v=<version>
 *   content://com.cardpersonality.honor.face/card/<type>/<urlEncodedIdentity>?v=<version>
 *     -> served from <filesDir>/exports/<sha256hex(fileKey)>.jpg  (1008x624 JPEG, no rounding)
 *   content://com.cardpersonality.honor.face/original/<type>/<urlEncodedIdentity>
 *     -> served from <filesDir>/originals/<sha256hex(fileKey)>.jpg  (hook-captured wallet face)
 *
 * ContentResolver.call() contract:
 *   "config" / "getAllConfigs" -> {json: config.json content, version: N}
 *   "getFaceConfig"(type,id)   -> {json, version, hasCustomFace}
 *   "report"(json)             -> {ok, version}
 *   "getReport"                -> {json: report.json}
 *   "saveOriginalFace"(type,id,data:ByteArray) -> {ok, skipped}  (hook snapshots the real wallet face)
 *   "originalExists"(type,id)  -> {exists: Boolean}
 *   "ping"                     -> {pong: true, version}
 *
 * The provider does no bitmap composition: exports are rendered by the UI at save time and
 * original-face snapshots are captured by the hook inside the wallet process.
 */
class FaceProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        val ctx = context ?: return out.apply { putBoolean(EXTRA_OK, false) }
        return try {
            when (method) {
                CALL_CONFIG, CALL_GET_ALL_CONFIGS -> putConfig(ctx, out)
                CALL_GET_CONFIG -> putFaceConfig(ctx, extras, out)
                CALL_REPORT -> {
                    val json = extras?.getString(EXTRA_JSON)
                    val ok = FaceRepository.reportCards(ctx, json)
                    // Round-10: remember which wallet package reported cards (active wallet).
                    extras?.getString(EXTRA_WALLET)?.takeIf { it.isNotBlank() }?.let { pkg ->
                        rememberActiveWallet(ctx, pkg)
                    }
                    out.putBoolean(EXTRA_OK, ok)
                    out.putInt(EXTRA_VERSION, FaceRepository.storeVersion(ctx))
                }
                CALL_WALLETS -> putWallets(ctx, out)
                CALL_SCAN_WALLETS -> putScanWallets(ctx, out)
                CALL_OPLOG -> {
                    val source = extras?.getString(EXTRA_SOURCE).orEmpty()
                    val action = extras?.getString(EXTRA_ACTION).orEmpty()
                    val detail = extras?.getString(EXTRA_DETAIL).orEmpty()
                    com.cardpersonality.honor.data.OpLog.record(ctx, source, action, detail)
                    out.putBoolean(EXTRA_OK, true)
                }
                CALL_GET_REPORT -> {
                    out.putString(EXTRA_JSON, FaceRepository.reportJson(ctx))
                    out.putBoolean(EXTRA_OK, true)
                }
                CALL_SAVE_ORIGINAL -> putSaveOriginalFace(ctx, extras, out)
                CALL_ORIGINAL_EXISTS -> {
                    val type = extras?.getString(EXTRA_TYPE).orEmpty()
                    val id = extras?.getString(EXTRA_ID).orEmpty()
                    val exists = type in ORIGINAL_TYPES && id.isNotEmpty() &&
                        originalFile(ctx, type, id).isFile
                    out.putBoolean(EXTRA_EXISTS, exists)
                    out.putBoolean(EXTRA_OK, true)
                }
                CALL_PING -> {
                    out.putBoolean(EXTRA_PONG, true)
                    out.putInt(EXTRA_VERSION, FaceRepository.storeVersion(ctx))
                }
                else -> {
                    out.putBoolean(EXTRA_OK, false)
                    out.putString(EXTRA_ERROR, "unknown method: $method")
                }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "call($method) failed", t)
            out.putBoolean(EXTRA_OK, false)
            out.putString(EXTRA_ERROR, t.toString())
            out
        }
    }

    /**
     * Round-10 wallet detection for the panel: known wallet packages with install state, version
     * name and kind, plus the active wallet package (last package that reported cards).
     * Returning this does not launch or touch the other wallet app.
     */
    private fun putWallets(ctx: Context, out: Bundle) {
        val pm = ctx.packageManager
        val arr = JSONArray()
        for (wallet in Wallets.ALL) {
            val info = try {
                pm.getPackageInfo(wallet.pkg, 0)
            } catch (_: Throwable) {
                null
            }
            arr.put(
                JSONObject()
                    .put("package", wallet.pkg)
                    .put("label", wallet.label)
                    .put("kind", wallet.kind)
                    .put("installed", info != null)
                    .put("versionName", info?.versionName ?: ""),
            )
        }
        val json = JSONObject()
            .put("wallets", arr)
            .put("activeWalletPackage", readActiveWallet(ctx))
        out.putString(EXTRA_JSON, json.toString())
        out.putBoolean(EXTRA_OK, true)
        val installedCount = (0 until arr.length()).count { arr.getJSONObject(it).optBoolean("installed") }
        OpLog.record(ctx, "wallet", "detect", "installed=$installedCount/${Wallets.ALL.size}")
    }

    /**
     * Round-11 optional: scan installed apps whose label mentions 钱包/Wallet but are NOT in the
     * supported registry — the panel can show "检测到未适配钱包". Read-only; nothing is hooked.
     */
    private fun putScanWallets(ctx: Context, out: Bundle) {
        val pm = ctx.packageManager
        val known = Wallets.ALL.map { it.pkg }.toSet()
        val arr = JSONArray()
        try {
            val apps = pm.getInstalledApplications(0)
            for (app in apps) {
                val pkg = app.packageName ?: continue
                if (pkg == ctx.packageName) continue
                val label = try {
                    pm.getApplicationLabel(app)?.toString().orEmpty()
                } catch (_: Throwable) {
                    ""
                }
                val looksWallet = label.contains("钱包") ||
                    label.contains("wallet", ignoreCase = true) ||
                    label.contains("欧加", ignoreCase = true) ||
                    label.contains("荣耀", ignoreCase = true)
                if (!looksWallet) continue
                arr.put(
                    JSONObject()
                        .put("package", pkg)
                        .put("label", label)
                        .put("known", pkg in known)
                        .put("versionName", try {
                            pm.getPackageInfo(pkg, 0).versionName ?: ""
                        } catch (_: Throwable) {
                            ""
                        }),
                )
            }
        } catch (_: Throwable) {
        }
        val json = JSONObject()
            .put("wallets", arr)
            .put("activeWalletPackage", readActiveWallet(ctx))
        out.putString(EXTRA_JSON, json.toString())
        out.putBoolean(EXTRA_OK, true)
    }

    private fun activeWalletFile(ctx: Context): File = File(ctx.filesDir, ACTIVE_WALLET_FILE)

    private fun rememberActiveWallet(ctx: Context, pkg: String) {
        try {
            val file = activeWalletFile(ctx)
            if (file.isFile && file.readText(Charsets.UTF_8).trim() == pkg) return
            file.writeText(pkg, Charsets.UTF_8)
            Log.i(TAG, "active wallet package: $pkg")
        } catch (_: Throwable) {
        }
    }

    private fun readActiveWallet(ctx: Context): String {
        return try {
            val file = activeWalletFile(ctx)
            if (file.isFile) file.readText(Charsets.UTF_8).trim() else ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun putConfig(ctx: Context, out: Bundle) {
        val json = try {
            FaceRepository.configFile(ctx).readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
        out.putString(EXTRA_JSON, json ?: EMPTY_CONFIG)
        out.putInt(EXTRA_VERSION, try {
            FaceRepository.storeVersion(ctx)
        } catch (_: Throwable) {
            0
        })
        out.putBoolean(EXTRA_OK, true)
    }

    private fun putFaceConfig(ctx: Context, extras: Bundle?, out: Bundle) {
        val type = extras?.getString(EXTRA_TYPE)
        val id = extras?.getString(EXTRA_ID)
        if (type.isNullOrEmpty() || id.isNullOrEmpty()) {
            out.putBoolean(EXTRA_OK, false)
            out.putString(EXTRA_ERROR, "type/id required")
            return
        }
        val wallet = extras?.getString(EXTRA_WALLET)
        out.putString(EXTRA_JSON, FaceRepository.configJson(ctx, type, id, wallet))
        out.putInt(EXTRA_VERSION, FaceRepository.cardVersion(ctx, type, id, wallet))
        out.putBoolean(EXTRA_HAS_CUSTOM_FACE, FaceRepository.hasCustomFace(ctx, type, id, wallet))
        out.putBoolean(EXTRA_OK, true)
    }

    /**
     * Persists an original-face snapshot captured by the hook.
     * Constraints: type whitelist, JPEG magic, <= 512 KB, idempotent (same-size file is kept),
     * atomic replace. Never throws to the wallet process (result bundle reports failure).
     */
    private fun putSaveOriginalFace(ctx: Context, extras: Bundle?, out: Bundle) {
        val type = extras?.getString(EXTRA_TYPE).orEmpty()
        val id = extras?.getString(EXTRA_ID).orEmpty()
        val data = extras?.getByteArray(EXTRA_DATA)
        if (type !in ORIGINAL_TYPES || id.isEmpty() || data == null || data.isEmpty()) {
            out.putBoolean(EXTRA_OK, false)
            out.putString(EXTRA_ERROR, "bad args")
            return
        }
        if (data.size > ORIGINAL_MAX_BYTES) {
            out.putBoolean(EXTRA_OK, false)
            out.putString(EXTRA_ERROR, "too large: ${data.size}")
            return
        }
        if (data.size < 4 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) {
            out.putBoolean(EXTRA_OK, false)
            out.putString(EXTRA_ERROR, "not a JPEG")
            return
        }
        val file = originalFile(ctx, type, id)
        if (file.isFile && file.length() == data.size.toLong()) {
            out.putBoolean(EXTRA_OK, true)
            out.putBoolean(EXTRA_SKIPPED, true)
            return
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            Log.i(TAG, "saveOriginalFace $type bytes=${data.size} (key=${file.name})")
            out.putBoolean(EXTRA_OK, true)
            out.putBoolean(EXTRA_SKIPPED, false)
        } catch (t: Throwable) {
            Log.w(TAG, "saveOriginalFace write failed", t)
            out.putBoolean(EXTRA_OK, false)
            out.putString(EXTRA_ERROR, t.toString())
        }
    }

    private fun originalFile(ctx: Context, type: String, id: String): File =
        File(
            File(ctx.filesDir, ORIGINAL_DIR),
            Hashing.sha256Hex(CardKey(type, id).fileKey) + ".jpg",
        )

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("no provider context")
        val segments = uri.pathSegments
        if (segments.isEmpty()) {
            throw FileNotFoundException("unsupported uri: $uri")
        }
        val file: File? = when (segments[0]) {
            PATH_ORIGINAL -> {
                if (segments.size < 3) {
                    null
                } else {
                    val candidate = originalFile(ctx, segments[1], Uri.decode(segments[2]))
                    if (candidate.isFile && candidate.length() > 0L) candidate else null
                }
            }
            PATH_CARD -> if (segments.size >= 3) {
                // /card/<type>/<identity>
                FaceRepository.ensureExport(ctx, segments[1], Uri.decode(segments[2]))
            } else if (segments.size == 2) {
                // /card/<sha256hex(fileKey)>[.jpg]
                var key = segments[1]
                if (key.endsWith(".jpg", ignoreCase = true)) key = key.dropLast(4)
                val candidate = File(File(ctx.filesDir, FaceRepository.EXPORT_DIR), "$key.jpg")
                if (candidate.isFile && candidate.length() > 0L) candidate else null
            } else {
                null
            }
            else -> null
        }
        if (file == null || !file.isFile) {
            throw FileNotFoundException("no image for $uri")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only provider")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only provider")

    companion object {
        const val AUTHORITY = FaceRepository.AUTHORITY
        const val TAG = "CARD_PERSONALITY_PROVIDER"

        const val PATH_CARD = "card"
        const val PATH_ORIGINAL = "original"

        const val CALL_CONFIG = "config"
        const val CALL_GET_CONFIG = "getFaceConfig"
        const val CALL_GET_ALL_CONFIGS = "getAllConfigs"
        const val CALL_REPORT = "report"
        const val CALL_GET_REPORT = "getReport"
        const val CALL_SAVE_ORIGINAL = "saveOriginalFace"
        const val CALL_ORIGINAL_EXISTS = "originalExists"
        const val CALL_PING = "ping"
        const val CALL_WALLETS = "wallets"
        const val CALL_SCAN_WALLETS = "scan_wallets"
        const val CALL_OPLOG = "oplog"

        const val EXTRA_JSON = "json"
        const val EXTRA_TYPE = "type"
        const val EXTRA_ID = "id"
        const val EXTRA_DATA = "data"
        const val EXTRA_VERSION = "version"
        const val EXTRA_HAS_CUSTOM_FACE = "hasCustomFace"
        const val EXTRA_EXISTS = "exists"
        const val EXTRA_SKIPPED = "skipped"
        const val EXTRA_OK = "ok"
        const val EXTRA_ERROR = "error"
        const val EXTRA_PONG = "pong"
        const val EXTRA_WALLET = "wallet"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_ACTION = "action"
        const val EXTRA_DETAIL = "detail"

        /** Only these card types may store original-face snapshots. */
        private val ORIGINAL_TYPES = setOf("transit", "bank", "access", "campus", "carkey", "ebike")

        /** Directory under filesDir holding hook-captured original faces. */
        const val ORIGINAL_DIR = "originals"

        /** File remembering which wallet package last reported cards (active wallet). */
        private const val ACTIVE_WALLET_FILE = "active_wallet.txt"

        /** Hard cap for one stored snapshot (hook sends long-side<=640 JPEG q85). */
        private const val ORIGINAL_MAX_BYTES = 3 * 1024 * 1024

        private const val EMPTY_CONFIG =
            "{\"version\":0,\"updatedAt\":0,\"cards\":{},\"types\":{}}"
    }
}
