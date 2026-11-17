package com.cardpersonality.honor.hook.xiaomi

import com.cardpersonality.honor.hook.CardIdentity
import com.cardpersonality.honor.hook.HLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Xiaomi smart-card (com.miui.tsmclient) wallet-side loopback bridge — NEW in 0.2.13.
 *
 * WHY A SOCKET BRIDGE (same reasoning as the OPPO engine, round-18):
 *  - the panel's ContentProvider is not reliably resolvable from a targetSdk-36 wallet process on
 *    recent HyperOS builds (package visibility), and secondary wallet processes have no Application
 *    Context at all, so `ContentResolver.call()` is not a dependable channel;
 *  - the proven reference module (see 参考仓库/cardface-miuix-main, core/HookPrefsProvider.kt +
 *    xposed/ProviderPrefs.kt) uses a provider/remote-prefs channel, but that needs an extra exported
 *    provider + LSPosed remote-prefs opt-in. We already own a proven loopback protocol on this
 *    device family (PanelBridgeServer / 127.0.0.1:47131..47135), so Xiaomi reuses it instead of
 *    growing a second IPC mechanism.
 *
 * This file is intentionally self-contained (it does NOT call into the OPPO bridge object): the OPPO
 * engine is under active parallel development and sharing its internals would couple the two ports.
 * The wire protocol is identical, so the panel needs no changes:
 *
 *   wallet  --HELLO/CARD/CARDS/OPLOG/ORIGINAL/PONG-->  127.0.0.1:47131..47135 (panel)
 *   wallet  <--CONFIG/PUT/DEL/REQ_CARDS/REQ_ORIGINAL/DEL_ORIGINAL/PING-- panel
 *
 * Local storage (the injected hook runs INSIDE the wallet process, i.e. as the wallet's own uid, so
 * plain `File` writes are allowed — NO ROOT is needed; the reference module needed root only because
 * its panel app copied files across uids):
 *
 *   /data/user/0/<walletPkg>/files/cardpersonality/
 *     config.json   panel CONFIG payload (version / cards / types)
 *     faces/        <sha256(fileKey)[0..24]>_v<version>.jpg  -> replacement file:// source
 *     faces/urls.json  original face URL -> {file, key, version} (stable URL matching)
 *
 * Everything is fail-open: no panel -> no config -> the hook keeps the wallet's original face.
 */
internal object XiaomiBridge {
    /** Same literal port list as PanelBridgeServer. */
    private val PORTS = intArrayOf(47131, 47132, 47133, 47134, 47135)
    private const val PORTS_LABEL = "47131,47132,47133,47134,47135"
    private const val RECONNECT_MS = 2_000L
    private const val MAX_LINE = 512 * 1024
    private const val STORAGE_DIR = "cardpersonality"

    private val STARTED = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cardpersonality-xiaomi-bridge").apply { isDaemon = true }
    }
    private val downstream = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cardpersonality-xiaomi-bridge-rx").apply { isDaemon = true }
    }

    @Volatile private var walletPackage: String = XiaomiFaceHookEngine.WALLET_PACKAGE
    @Volatile private var engineStateSupplier: (() -> String)? = null
    @Volatile private var configListener: (() -> Unit)? = null
    @Volatile private var connectedListener: (() -> Unit)? = null
    @Volatile private var cardSnapshotSupplier: (() -> String)? = null
    @Volatile private var originalInvalidatedListener: ((String, String) -> Unit)? = null

    private const val MAX_PENDING_CARDS = 500
    private const val MAX_PENDING_OPLOGS = 200
    private val pendingCards = LinkedHashMap<String, String>()
    private val pendingOplogs = ArrayDeque<String>()

    @Volatile private var connected = false
    @Volatile private var bridgeHost: String = ""
    @Volatile private var bridgePort: Int = 0
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: OutputStreamWriter? = null

    /** Raw CONFIG JSON snapshot (for hidden-element lookups / diagnostics). */
    @Volatile private var configJson: String = "{}"
    private val faceFiles = ConcurrentHashMap<String, File>()
    private val faceVersions = ConcurrentHashMap<String, Int>()

    /** Fed by PUT frames that carry the wallet's original face URL (exact URL matching). */
    private val urlFaces = ConcurrentHashMap<String, File>()
    private val urlFaceKeys = ConcurrentHashMap<String, String>()
    private val urlPersist = ConcurrentHashMap<String, String>()

    private val urlHitCount = AtomicLong(0L)
    private val urlMissCount = AtomicLong(0L)
    private val putOkCount = AtomicLong(0L)
    private val configOkCount = AtomicLong(0L)

    // ================================================================ lifecycle

    fun start(walletPackage: String, stateSupplier: () -> String) {
        this.walletPackage = walletPackage
        this.engineStateSupplier = stateSupplier
        loadLocalConfig()
        loadUrlMap()
        if (!STARTED.compareAndSet(false, true)) return
        HLog.i("Xiaomi bridge: starting loopback client wallet=$walletPackage ports=$PORTS_LABEL")
        worker.execute { connectLoop() }
    }

    fun setConfigListener(listener: () -> Unit) {
        configListener = listener
    }

    fun setConnectedListener(listener: () -> Unit) {
        connectedListener = listener
    }

    fun setCardSnapshotSupplier(supplier: () -> String) {
        cardSnapshotSupplier = supplier
    }

    fun setOriginalInvalidatedListener(listener: (String, String) -> Unit) {
        originalInvalidatedListener = listener
    }

    fun isConnected(): Boolean = connected
    fun port(): Int = bridgePort
    fun pendingCardCount(): Int = synchronized(pendingCards) { pendingCards.size }
    fun pendingOplogCount(): Int = synchronized(pendingOplogs) { pendingOplogs.size }

    private fun connectLoop() {
        val hosts = listOf("127.0.0.1", "::1") // IPv4 literal first: the panel binds it explicitly
        while (true) {
            if (!connected) {
                for (host in hosts) {
                    if (connected) break
                    for (port in PORTS) {
                        if (connected) break
                        try {
                            val candidate = Socket()
                            candidate.tcpNoDelay = true
                            candidate.soTimeout = 0
                            candidate.connect(
                                java.net.InetSocketAddress(InetAddress.getByName(host), port),
                                1_500,
                            )
                            socket = candidate
                            writer = OutputStreamWriter(candidate.getOutputStream(), Charsets.UTF_8)
                            connected = true
                            bridgeHost = host
                            bridgePort = port
                            HLog.i("Xiaomi bridge: connected to $host:$port")
                            sendHello("connected")
                            downstream.execute { readLoop(candidate) }
                            flushPending()
                            try {
                                connectedListener?.invoke()
                            } catch (_: Throwable) {
                            }
                        } catch (_: Throwable) {
                            connected = false
                        }
                    }
                }
            }
            try {
                Thread.sleep(RECONNECT_MS)
            } catch (_: Throwable) {
            }
        }
    }

    private fun readLoop(client: Socket) {
        try {
            BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    try {
                        handleDownstream(line)
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            connected = false
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    // ================================================================ downstream (panel -> wallet)

    private fun handleDownstream(line: String) {
        val parts = line.split('\t', limit = 5)
        when (parts[0]) {
            "CONFIG" -> if (parts.size >= 2) onConfig(parts[1])
            "PUT" -> if (parts.size >= 4) {
                val faceUrl = if (parts.size >= 5) decode(parts[4])?.toString(Charsets.UTF_8) else null
                onPut(parts[1], parts[2].toIntOrNull() ?: 0, decode(parts[3]), faceUrl)
            }
            "DEL" -> if (parts.size >= 2) onDel(parts[1])
            "REQ_CARDS" -> sendLine("CARDS\t" + (cardSnapshotSupplier?.invoke() ?: "[]"))
            "REQ_ORIGINAL" -> if (parts.size >= 3) {
                val bytes = readLocalOriginal(parts[1], parts[2])
                if (bytes != null) sendLine("ORIGINAL\t${parts[1]}\t${parts[2]}\t${encode(bytes)}")
            }
            "DEL_ORIGINAL" -> if (parts.size >= 3) {
                try {
                    originalFile(parts[1], parts[2]).delete()
                    originalInvalidatedListener?.invoke(parts[1], parts[2])
                } catch (_: Throwable) {
                }
            }
            "PING" -> sendLine("PONG")
        }
    }

    private fun onConfig(json: String) {
        try {
            configJson = json
            faceVersions.clear()
            urlFaces.clear()
            urlFaceKeys.clear()
            val obj = JSONObject(json)
            obj.optJSONObject("cards")?.let { cards ->
                for (key in cards.keys()) {
                    faceVersions[key] = cards.optJSONObject(key)?.optInt("version", 0) ?: 0
                }
            }
            obj.optJSONObject("types")?.let { types ->
                for (key in types.keys()) {
                    faceVersions[key] = types.optJSONObject(key)?.optInt("version", 0) ?: 0
                }
            }
            persistConfig(json)
            rebuildUrlFacesFromDisk()
            val n = configOkCount.incrementAndGet()
            if (n <= 20) oplog("config-ok", "faces=${faceVersions.size} urls=${urlFaces.size}")
            HLog.i("Xiaomi bridge: config received (${json.length} chars)")
            configListener?.invoke()
        } catch (_: Throwable) {
        }
    }

    private fun onPut(fileKey: String, version: Int, bytes: ByteArray?, faceUrl: String? = null) {
        if (bytes == null || bytes.isEmpty()) return
        try {
            val file = faceFileForWrite(fileKey, version)
            file.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            faceFiles[fileKey] = file
            faceVersions[fileKey] = version
            if (!faceUrl.isNullOrBlank()) registerUrlFace(faceUrl, file, fileKey)
            val n = putOkCount.incrementAndGet()
            if (n <= 20) oplog("put-ok", "$fileKey v=$version ${bytes.size}B url=${(faceUrl ?: "").take(60)}")
            HLog.i("Xiaomi bridge: face stored $fileKey v=$version ${bytes.size}B")
            configListener?.invoke()
        } catch (t: Throwable) {
            if (putOkCount.get() <= 20) oplog("put-fail", "${fileKey.take(60)}::${t.javaClass.simpleName}")
        }
    }

    private fun onDel(fileKey: String) {
        try {
            val prefix = sha256Hex(fileKey).take(24) + "_v"
            dirFaces().listFiles()?.forEach { f ->
                if (f.name.startsWith(prefix)) f.delete()
            }
            faceFiles.remove(fileKey)
            faceVersions.remove(fileKey)
            val iter = urlFaces.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (urlFaceKeys[entry.key] == fileKey) {
                    urlFaceKeys.remove(entry.key)
                    iter.remove()
                    urlPersist.remove(entry.key)
                }
            }
            persistUrlMap()
            val n = putOkCount.incrementAndGet()
            if (n <= 20) oplog("del-ok", fileKey.take(80))
            configListener?.invoke()
        } catch (_: Throwable) {
        }
    }

    // ================================================================ upstream (wallet -> panel)

    fun sendHello(reason: String) {
        try {
            val engine = try {
                engineStateSupplier?.invoke() ?: "{}"
            } catch (_: Throwable) {
                "{}"
            }
            val hello = JSONObject(engine)
            hello.put("reason", reason)
            hello.put("walletPackage", walletPackage)
            hello.put("walletKind", "xiaomi")
            hello.put("bridgeHost", bridgeHost)
            hello.put("bridgePort", bridgePort)
            hello.put("portsHint", PORTS_LABEL)
            hello.put("connectedAt", System.currentTimeMillis())
            sendLine("HELLO\t$hello")
        } catch (_: Throwable) {
        }
    }

    fun sendCard(cardJson: String) {
        val key = cardDedupKey(cardJson)
        if (connected) {
            sendLine("CARD\t$cardJson")
            return
        }
        synchronized(pendingCards) {
            pendingCards.remove(key)
            pendingCards[key] = cardJson
            while (pendingCards.size > MAX_PENDING_CARDS) {
                val eldest = pendingCards.keys.firstOrNull() ?: break
                pendingCards.remove(eldest)
            }
        }
    }

    fun sendCards(jsonArray: String) {
        try {
            val arr = JSONArray(jsonArray)
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { sendCard(it.toString()) }
            }
        } catch (_: Throwable) {
        }
    }

    fun oplog(action: String, detail: String = "") {
        val safeDetail = detail.replace('\t', ' ').replace('\n', ' ').take(400)
        val line = "OPLOG\txiaomi\t$action\t$safeDetail"
        if (connected) {
            sendLine(line)
            return
        }
        synchronized(pendingOplogs) {
            pendingOplogs.addLast(line)
            while (pendingOplogs.size > MAX_PENDING_OPLOGS) pendingOplogs.removeFirst()
        }
    }

    fun sendOriginal(type: String, id: String, jpeg: ByteArray) {
        try {
            writeLocalOriginal(type, id, jpeg)
            sendLine("ORIGINAL\t$type\t$id\t${encode(jpeg)}")
        } catch (_: Throwable) {
        }
    }

    private fun flushPending() {
        try {
            val cards = synchronized(pendingCards) {
                val copy = pendingCards.values.toList()
                pendingCards.clear()
                copy
            }
            for (json in cards) sendLine("CARD\t$json")
            val logs = synchronized(pendingOplogs) {
                val copy = pendingOplogs.toList()
                pendingOplogs.clear()
                copy
            }
            for (line in logs) sendLine(line)
            if (cards.isNotEmpty() || logs.isNotEmpty()) {
                HLog.i("Xiaomi bridge: flushed queued cards=${cards.size} oplogs=${logs.size}")
            }
        } catch (_: Throwable) {
        }
    }

    private fun cardDedupKey(cardJson: String): String {
        return try {
            val o = JSONObject(cardJson)
            "$walletPackage|${o.optString("type")}:${o.optString("identity")}"
        } catch (_: Throwable) {
            Integer.toHexString(cardJson.hashCode())
        }
    }

    private fun sendLine(line: String) {
        if (!connected) return
        try {
            val w = writer ?: return
            val payload = if (line.length > MAX_LINE) line.substring(0, MAX_LINE) else line
            synchronized(this) {
                w.write(payload)
                w.write("\n")
                w.flush()
            }
        } catch (_: Throwable) {
            connected = false
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    // ================================================================ face resolution

    /** Storage keys tried for an identity: wallet-scoped exact, wallet-scoped type default. */
    private fun candidateKeys(type: String, id: String): List<String> {
        val out = ArrayList<String>(2)
        out.add("$walletPackage|$type:$id")
        out.add("$walletPackage|type:$type")
        return out
    }

    data class Face(val fileKey: String, val version: Int, val file: File) {
        /** `file://` URL handed to the wallet's Glide chain (reference-proven scheme). */
        val fileUrl: String get() = "file://" + file.absolutePath
    }

    fun resolveFace(identity: CardIdentity): Face? {
        // Fast negative path: nothing configured / nothing persisted -> avoid directory scans on the
        // (hot) Glide hook path.
        if (faceVersions.isEmpty() && faceFiles.isEmpty()) return null
        for (key in candidateKeys(identity.type, identity.id)) {
            val cached = faceFiles[key]
            if (cached != null && cached.isFile && cached.length() > 0L) {
                return Face(key, faceVersions[key] ?: 0, cached)
            }
            val version = faceVersions[key]
            if (version != null) {
                val f = faceFileForWrite(key, version)
                if (f.isFile && f.length() > 0L) {
                    faceFiles[key] = f
                    return Face(key, version, f)
                }
            }
            val prefix = sha256Hex(key).take(24) + "_v"
            dirFaces().listFiles()?.forEach { f ->
                if (f.name.startsWith(prefix) && f.isFile && f.length() > 0L) {
                    val v = f.name.removePrefix(prefix).substringBefore('.').toIntOrNull() ?: 0
                    faceFiles[key] = f
                    faceVersions[key] = v
                    return Face(key, v, f)
                }
            }
        }
        return null
    }

    /** Exact/normalized/basename URL matching against faces the panel pushed WITH a faceUrl. */
    fun resolveFaceByUrl(rawUrl: String?): Face? {
        for (alias in urlAliases(rawUrl)) {
            val file = urlFaces[alias] ?: continue
            if (!file.isFile || file.length() <= 0L) continue
            val key = urlFaceKeys[alias] ?: alias
            val n = urlHitCount.incrementAndGet()
            if (n <= 20) oplog("replace-hit", "url=${(rawUrl ?: "").take(80)}")
            return Face(key, faceVersions[key] ?: 0, file)
        }
        val m = urlMissCount.incrementAndGet()
        if (m <= 20) oplog("replace-miss", "url=${(rawUrl ?: "").take(80)}")
        return null
    }

    fun faceUri(identity: CardIdentity): String? = resolveFace(identity)?.fileUrl

    private fun registerUrlFace(rawUrl: String, file: File, fileKey: String) {
        for (alias in urlAliases(rawUrl)) {
            urlFaces[alias] = file
            urlFaceKeys[alias] = fileKey
            urlPersist[alias] = "$fileKey|${file.name}"
        }
        persistUrlMap()
    }

    /**
     * Aliases for robust URL matching: raw, URL-decoded, query/fragment stripped, scheme stripped,
     * and (unique) file basename. Mirrors the reference module's `UrlMapper.lookupMapping` tiers.
     */
    private fun urlAliases(rawUrl: String?): List<String> {
        if (rawUrl.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        val decoded = try {
            java.net.URLDecoder.decode(rawUrl.trim(), "UTF-8")
        } catch (_: Throwable) {
            rawUrl.trim()
        }
        fun addVariants(v: String) {
            if (v.isBlank()) return
            out.add(v)
            val noWhere = v.substringBefore('?').substringBefore('#')
            out.add(noWhere)
            out.add(noWhere.removePrefix("file://"))
            out.add(noWhere.removePrefix("https://").removePrefix("http://"))
            out.add(noWhere.trimEnd('/').substringAfterLast('/'))
        }
        addVariants(rawUrl.trim())
        addVariants(decoded)
        return out.filter { it.isNotBlank() }
    }

    // ================================================================ local originals (optional)

    fun originalNeeded(type: String, id: String): Boolean {
        val f = originalFile(type, id)
        return !f.isFile || f.length() <= 0L
    }

    fun saveOriginal(type: String, id: String, jpeg: ByteArray): Boolean = try {
        writeLocalOriginal(type, id, jpeg)
        true
    } catch (_: Throwable) {
        false
    }

    fun readLocalOriginal(type: String, id: String): ByteArray? = try {
        val f = originalFile(type, id)
        if (f.isFile && f.length() > 0L) f.readBytes() else null
    } catch (_: Throwable) {
        null
    }

    fun invalidateOriginal(type: String, id: String) {
        try {
            originalFile(type, id).delete()
            sendLine("ORIGINAL_INVALID\t$type\t$id")
        } catch (_: Throwable) {
        }
    }

    private fun originalFile(type: String, id: String): File =
        File(dirOriginals(), sha256Hex("$type:$id") + ".jpg")

    private fun writeLocalOriginal(type: String, id: String, jpeg: ByteArray) {
        val f = originalFile(type, id)
        f.parentFile?.mkdirs()
        val tmp = File(f.absolutePath + ".tmp")
        tmp.writeBytes(jpeg)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    // ================================================================ local files

    /** Wallet files dir resolved from the wallet's own Application when available (user-aware). */
    @Volatile private var appFilesDir: File? = null

    private fun filesBaseDir(): File {
        appFilesDir?.let { return it }
        val ctxFiles = try {
            com.cardpersonality.honor.hook.ProviderClient.appContext()?.filesDir
        } catch (_: Throwable) {
            null
        }
        if (ctxFiles != null) {
            appFilesDir = ctxFiles
            return ctxFiles
        }
        return File("/data/user/0/$walletPackage/files")
    }

    private fun rootDir(): File = File(filesBaseDir(), STORAGE_DIR)

    private fun dirFaces(): File = File(rootDir(), "faces")

    private fun dirOriginals(): File = File(rootDir(), "originals")

    private fun configFile(): File = File(rootDir(), "config.json")

    private fun urlMapFile(): File = File(dirFaces(), "urls.json")

    private fun faceFileForWrite(fileKey: String, version: Int): File =
        File(dirFaces(), sha256Hex(fileKey).take(24) + "_v" + version + ".jpg")

    private fun loadLocalConfig() {
        try {
            val f = configFile()
            if (!f.isFile) return
            configJson = f.readText(Charsets.UTF_8)
            val obj = JSONObject(configJson)
            obj.optJSONObject("cards")?.let { cards ->
                for (key in cards.keys()) {
                    faceVersions[key] = cards.optJSONObject(key)?.optInt("version", 0) ?: 0
                }
            }
            obj.optJSONObject("types")?.let { types ->
                for (key in types.keys()) {
                    faceVersions[key] = types.optJSONObject(key)?.optInt("version", 0) ?: 0
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun persistConfig(json: String) {
        try {
            val f = configFile()
            f.parentFile?.mkdirs()
            val tmp = File(f.absolutePath + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: Throwable) {
        }
    }

    private fun loadUrlMap() {
        try {
            val f = urlMapFile()
            if (!f.isFile) return
            val obj = JSONObject(f.readText(Charsets.UTF_8))
            val keys = obj.keys()
            while (keys.hasNext()) {
                val alias = keys.next()
                val value = obj.optString(alias, "")
                if (value.isEmpty()) continue
                val fileKey = value.substringBefore('|')
                val name = value.substringAfter('|', "")
                if (fileKey.isEmpty() || name.isEmpty()) continue
                urlPersist[alias] = value
                val file = File(dirFaces(), name)
                if (file.isFile && file.length() > 0L) {
                    urlFaces[alias] = file
                    urlFaceKeys[alias] = fileKey
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun rebuildUrlFacesFromDisk() {
        // After a CONFIG the versions may have moved; re-point persisted URL aliases at the file
        // that exists for their fileKey (content is version-suffixed).
        for ((alias, value) in urlPersist) {
            val fileKey = value.substringBefore('|')
            if (fileKey.isEmpty()) continue
            val face = resolveFaceByKey(fileKey) ?: continue
            urlFaces[alias] = face.file
            urlFaceKeys[alias] = fileKey
        }
    }

    private fun resolveFaceByKey(fileKey: String): Face? {
        faceFiles[fileKey]?.let { cached ->
            if (cached.isFile && cached.length() > 0L) return Face(fileKey, faceVersions[fileKey] ?: 0, cached)
        }
        val version = faceVersions[fileKey]
        if (version != null) {
            val f = faceFileForWrite(fileKey, version)
            if (f.isFile && f.length() > 0L) return Face(fileKey, version, f)
        }
        val prefix = sha256Hex(fileKey).take(24) + "_v"
        dirFaces().listFiles()?.forEach { f ->
            if (f.name.startsWith(prefix) && f.isFile && f.length() > 0L) {
                val v = f.name.removePrefix(prefix).substringBefore('.').toIntOrNull() ?: 0
                return Face(fileKey, v, f)
            }
        }
        return null
    }

    private fun persistUrlMap() {
        try {
            val obj = JSONObject()
            for ((alias, value) in urlPersist) obj.put(alias, value)
            val f = urlMapFile()
            f.parentFile?.mkdirs()
            val tmp = File(f.absolutePath + ".tmp")
            tmp.writeText(obj.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: Throwable) {
        }
    }

    // ================================================================ helpers

    fun configSnapshot(): String = configJson

    private fun encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun decode(text: String): ByteArray? = try {
        android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
    } catch (_: Throwable) {
        null
    }

    private fun sha256Hex(text: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            Integer.toHexString(text.hashCode())
        }
    }
}
