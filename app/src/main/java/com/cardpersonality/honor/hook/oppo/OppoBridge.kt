package com.cardpersonality.honor.hook.oppo

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

/**
 * Round-18 OPPO wallet-side loopback bridge (no Context, no provider, no ContentResolver).
 *
 * Why: on the OPPO wallet (targetSdk 36) the panel app is not visible to the wallet's package
 * visibility rules, so every `ContentResolver.call(panel provider)` fails with
 * "Unknown authority" — and non-main wallet processes (e.g. :plugin, :temp) have no Application
 * context at all. The friend module's TCP bridge works on the same device, so OPPO IPC uses the
 * same approach:
 *
 *   wallet  --HELLO/CARD/OPLOG/ORIGINAL-->  127.0.0.1:47131..47133 (panel PanelBridgeServer)
 *   wallet  <--CONFIG/PUT/DEL/REQ_ORIGINAL-- panel
 *
 * Local storage (same uid, no Context needed):
 *   /data/user/0/<walletPkg>/files/cardpersonality/
 *     config.json      (panel CONFIG payload: version/cards/types/hidden/typeHidden)
 *     faces/           (<sha256(fileKey)>_v<version>.jpg)  -> replacement file:// source
 *     originals/       (<sha256(type:id)>.jpg)             -> current-face snapshot
 *
 * The bridge reconnects forever (2 s), never throws into hook code, and is a complete no-op when
 * the panel is not running.
 */
object OppoBridge {
    const val BASE_PORT = 47131

    /** Round-19: unified explicit loopback ports (same literal list as PanelBridgeServer). */
    private val PORTS = intArrayOf(47131, 47132, 47133, 47134, 47135)

    /** Diagnostics literal: the complete loopback port list (visible in logs/HELLO). */
    private const val PORTS_LABEL = "47131,47132,47133,47134,47135"
    private const val RECONNECT_MS = 2_000L
    private const val MAX_LINE = 512 * 1024

    private val STARTED = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cardpersonality-oppo-bridge").apply { isDaemon = true }
    }
    private val downstream = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cardpersonality-oppo-bridge-rx").apply { isDaemon = true }
    }

    @Volatile private var walletPackage: String = OppoFaceHookEngine.WALLET_PACKAGE
    @Volatile private var engineStateSupplier: (() -> String)? = null
    @Volatile private var configListener: (() -> Unit)? = null
    @Volatile private var connectedListener: (() -> Unit)? = null
    @Volatile private var cardSnapshotSupplier: (() -> String)? = null
    @Volatile private var originalInvalidatedListener: ((String, String) -> Unit)? = null

    // Round-19: CARD/OPLOG frames produced while the panel is not running are queued and flushed
    // on (re)connect; cards are de-duplicated by wallet|type:identity.
    private const val MAX_PENDING_CARDS = 500
    private const val MAX_PENDING_OPLOGS = 200
    private val pendingCards = LinkedHashMap<String, String>()
    private val pendingOplogs = ArrayDeque<String>()

    @Volatile private var connected = false
    @Volatile private var bridgeHost: String = ""
    @Volatile private var bridgePort: Int = 0
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: OutputStreamWriter? = null

    /** in-memory config snapshot (raw JSON string) + parsed versions per fileKey. */
    @Volatile private var configJson: String = "{}"
    private val faceFiles = ConcurrentHashMap<String, File>()
    private val faceVersions = ConcurrentHashMap<String, Int>()

    /** Round-23: original wallet face URL -> rendered file (exact source URL matching). */
    private val urlFaces = ConcurrentHashMap<String, File>()
    private val urlFaceKeys = ConcurrentHashMap<String, String>()
    private val urlHitCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val urlMissCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val putOkCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val configOkCount = java.util.concurrent.atomic.AtomicLong(0L)

    // ================================================================ lifecycle

    fun start(walletPackage: String, stateSupplier: () -> String) {
        this.walletPackage = walletPackage
        this.engineStateSupplier = stateSupplier
        loadLocalConfig()
        if (!STARTED.compareAndSet(false, true)) return
        HLog.i("OPPO bridge: starting loopback client wallet=$walletPackage ports=${PORTS.joinToString(",")}")
        worker.execute { connectLoop() }
    }

    /** Engine installs this; CONFIG pushes from the panel clear caches and reload live views. */
    fun setConfigListener(listener: () -> Unit) {
        configListener = listener
    }

    /** Round-19: called after a socket (re)connect so the engine can force a full card re-send. */
    fun setConnectedListener(listener: () -> Unit) {
        connectedListener = listener
    }

    /** Round-19: full detected-card snapshot used to answer the panel's REQ_CARDS. */
    fun setCardSnapshotSupplier(supplier: () -> String) {
        cardSnapshotSupplier = supplier
    }

    fun setOriginalInvalidatedListener(listener: (String, String) -> Unit) {
        originalInvalidatedListener = listener
    }

    fun pendingCardCount(): Int = synchronized(pendingCards) { pendingCards.size }

    fun pendingOplogCount(): Int = synchronized(pendingOplogs) { pendingOplogs.size }

    fun isConnected(): Boolean = connected

    /**
     * Round-19: try the explicit IPv4 literal first (the panel binds 127.0.0.1), then IPv6 ::1,
     * across all ports in BASE..BASE+PORT_COUNT-1; reconnect every 2 s.
     */
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
                            HLog.i("OPPO bridge: connected to $host:$port")
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

    // ================================================================ downstream commands

    private fun handleDownstream(line: String) {
        val parts = line.split('\t', limit = 5)
        when (parts[0]) {
            "CONFIG" -> {
                if (parts.size >= 2) {
                    onConfig(parts[1])
                }
            }
            "PUT" -> {
                if (parts.size >= 4) {
                    val faceUrl = if (parts.size >= 5) decode(parts[4])?.toString(Charsets.UTF_8) else null
                    onPut(parts[1], parts[2].toIntOrNull() ?: 0, decode(parts[3]), faceUrl)
                }
            }
            "DEL" -> {
                if (parts.size >= 2) onDel(parts[1])
            }
            "REQ_ORIGINAL" -> {
                if (parts.size >= 3) {
                    val bytes = readLocalOriginal(parts[1], parts[2])
                    if (bytes != null) sendLine("ORIGINAL\t${parts[1]}\t${parts[2]}\t${encode(bytes)}")
                }
            }
            "DEL_ORIGINAL" -> {
                if (parts.size >= 3) {
                    try {
                        originalFile(parts[1], parts[2]).delete()
                        originalInvalidatedListener?.invoke(parts[1], parts[2])
                    } catch (_: Throwable) {
                    }
                }
            }
            "REQ_CARDS" -> sendLine("CARDS\t" + (cardSnapshotSupplier?.invoke() ?: "[]"))
            "PING" -> sendLine("PONG")
        }
    }

    private fun onConfig(json: String) {
        try {
            configJson = json
            faceVersions.clear()
            urlFaces.clear()
            urlFaceKeys.clear()
            logConfigOk()
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
            HLog.i("OPPO bridge: config received (${json.length} chars)")
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
            logPutOk(fileKey, faceUrl)
            HLog.i("OPPO bridge: face stored $fileKey v=$version ${bytes.size}B")
            configListener?.invoke()
        } catch (_: Throwable) {
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
                }
            }
            configListener?.invoke()
        } catch (_: Throwable) {
        }
    }

    // ================================================================ upstream

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

    fun oplog(source: String, action: String, detail: String = "") {
        val safeDetail = detail.replace('\t', ' ').replace('\n', ' ').take(400)
        val line = "OPLOG\t$source\t$action\t$safeDetail"
        if (connected) {
            sendLine(line)
            return
        }
        synchronized(pendingOplogs) {
            pendingOplogs.addLast(line)
            while (pendingOplogs.size > MAX_PENDING_OPLOGS) pendingOplogs.removeFirst()
        }
    }

    /** Round-19: flush queued CARD/OPLOG frames once a socket is up. */
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
                HLog.i("OPPO bridge: flushed queued cards=${cards.size} oplogs=${logs.size}")
            }
        } catch (_: Throwable) {
        }
    }

    private fun cardDedupKey(cardJson: String): String {
        return try {
            val o = JSONObject(cardJson)
            val type = o.optString("type")
            val identity = o.optString("identity")
            val wallet = o.optString("wallet", walletPackage)
            "$wallet|$type:$identity"
        } catch (_: Throwable) {
            Integer.toHexString(cardJson.hashCode())
        }
    }

    fun sendOriginal(type: String, id: String, jpeg: ByteArray) {
        try {
            writeLocalOriginal(type, id, jpeg)
            sendLine("ORIGINAL\t$type\t$id\t${encode(jpeg)}")
        } catch (_: Throwable) {
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

    // ================================================================ local resolution

    /** Candidate storage keys for an identity (wallet-scoped first, then legacy type:id). */
    private fun candidateKeys(type: String, id: String): List<String> {
        val legacyExact = "$type:$id"
        val legacyType = "type:$type"
        val out = ArrayList<String>(4)
        out.add("$walletPackage|$legacyExact")
        out.add("$walletPackage|$legacyType")
        out.add(legacyExact)
        out.add(legacyType)
        return out
    }

    /** Resolved local face (fileKey storage key + version + file). */
    data class Face(val fileKey: String, val version: Int, val file: File)

    /** Full local face resolution for an identity (used by the hook engines). */
    private val localConfigHits = java.util.concurrent.atomic.AtomicLong(0L)
    private val localConfigMisses = java.util.concurrent.atomic.AtomicLong(0L)

    fun resolveFace(identity: CardIdentity): Face? {
        for (key in candidateKeys(identity.type, identity.id)) {
            val cached = faceFiles[key]
            if (cached != null && cached.isFile && cached.length() > 0L) {
                return Face(key, faceVersions[key] ?: 0, cached)
            }
            val prefix = sha256Hex(key).take(24) + "_v"
            dirFaces().listFiles()?.forEach { f ->
                if (f.name.startsWith(prefix) && f.isFile && f.length() > 0L) {
                    val version = f.name.removePrefix(prefix).substringBefore('.').toIntOrNull() ?: 0
                    faceFiles[key] = f
                    faceVersions[key] = version
                    val n = localConfigHits.incrementAndGet()
                    if (n <= 12) oplog("oppo", "local-config-hit", "$key v=$version")
                    return Face(key, version, f)
                }
            }
        }
        val n = localConfigMisses.incrementAndGet()
        if (n <= 12) oplog("oppo", "local-config-miss", "${identity.type}:${identity.id}")
        return null
    }

    /** Round-22: delete + invalidate the current-face snapshot (reset / wallet face changed). */
    fun invalidateOriginal(type: String, id: String) {
        try {
            originalFile(type, id).delete()
            sendLine("ORIGINAL_INVALID\t$type\t$id")
        } catch (_: Throwable) {
        }
    }

    /** file:// path of our rendered face for this card, or null when the panel never pushed one. */
    fun faceUri(identity: CardIdentity): String? {
        val file = faceFileForRead(identity) ?: return null
        return "file://" + file.absolutePath
    }

    fun faceFile(identity: CardIdentity): File? = faceFileForRead(identity)

    /** Round-23: exact source-URL lookup (friend-module approach; survives identity mismatch). */
    fun resolveFaceByUrl(rawUrl: String?): Face? {
        for (alias in urlAliases(rawUrl)) {
            val file = urlFaces[alias] ?: continue
            if (file.isFile && file.length() > 0L) {
                val key = urlFaceKeys[alias] ?: alias
                val n = urlHitCount.incrementAndGet()
                if (n <= 20) oplog("oppo", "replace-hit", "url=${(rawUrl ?: "").take(80)}")
                return Face(key, faceVersions[key] ?: 0, file)
            }
        }
        val m = urlMissCount.incrementAndGet()
        if (m <= 20) oplog("oppo", "replace-miss", "url=${(rawUrl ?: "").take(80)}")
        return null
    }

    private fun registerUrlFace(rawUrl: String, file: File, fileKey: String) {
        for (alias in urlAliases(rawUrl)) {
            urlFaces[alias] = file
            urlFaceKeys[alias] = fileKey
        }
    }

    /** Normalized aliases: raw, URL-decoded, query/fragment stripped, scheme-stripped, basename. */
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
            val noQuery = v.substringBefore('?').substringBefore('#')
            out.add(noQuery)
            out.add(noQuery.removePrefix("file://"))
            out.add(noQuery.removePrefix("https://").removePrefix("http://"))
            out.add(noQuery.substringAfterLast('/'))
        }
        addVariants(rawUrl.trim())
        addVariants(decoded)
        out.add(decoded.substringBefore('?').substringBefore('#'))
        return out.filter { it.isNotBlank() }
    }

    private fun logPutOk(fileKey: String, faceUrl: String?) {
        val n = putOkCount.incrementAndGet()
        if (n <= 20) oplog("oppo", "put-ok", "$fileKey url=${(faceUrl ?: "").take(60)}")
    }

    private fun logConfigOk() {
        val n = configOkCount.incrementAndGet()
        if (n <= 20) oplog("oppo", "config-ok", "urls=${urlFaces.size}")
    }

    private fun faceFileForRead(identity: CardIdentity): File? {
        for (key in candidateKeys(identity.type, identity.id)) {
            val cached = faceFiles[key]
            if (cached != null && cached.isFile && cached.length() > 0L) return cached
            val version = faceVersions[key]
            if (version != null) {
                val f = faceFileForWrite(key, version)
                if (f.isFile && f.length() > 0L) {
                    faceFiles[key] = f
                    return f
                }
            }
            // fallback: scan the directory for any stored version of this key
            val prefix = sha256Hex(key).take(24) + "_v"
            dirFaces().listFiles()?.forEach { f ->
                if (f.name.startsWith(prefix) && f.isFile && f.length() > 0L) {
                    faceFiles[key] = f
                    return f
                }
            }
        }
        return null
    }

    /** Hidden wallet element ids from the pushed config (wallet-scoped + legacy aliases). */
    fun hiddenElements(type: String, id: String): List<String> {
        return try {
            val obj = JSONObject(configJson)
            val out = ArrayList<String>()
            val hidden = obj.optJSONObject("hidden")
            if (hidden != null) {
                for (key in candidateKeys(type, id)) {
                    hidden.optJSONArray(key)?.let { arr ->
                        for (i in 0 until arr.length()) out.add(arr.optString(i))
                    }
                }
            }
            val typeHidden = obj.optJSONObject("typeHidden")
            if (typeHidden != null) {
                for (key in candidateKeys(type, CardKeyDefault.ID)) {
                    typeHidden.optJSONArray(key)?.let { arr ->
                        for (i in 0 until arr.length()) out.add(arr.optString(i))
                    }
                }
            }
            out.distinct()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private object CardKeyDefault {
        const val ID = "*"
    }

    // ================================================================ local originals

    fun originalNeeded(type: String, id: String): Boolean {
        val f = originalFile(type, id)
        return !f.isFile || f.length() <= 0L
    }

    fun saveOriginal(type: String, id: String, jpeg: ByteArray): Boolean {
        return try {
            writeLocalOriginal(type, id, jpeg)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun readLocalOriginal(type: String, id: String): ByteArray? {
        return try {
            val f = originalFile(type, id)
            if (f.isFile && f.length() > 0L) f.readBytes() else null
        } catch (_: Throwable) {
            null
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

    private fun rootDir(): File = File("/data/user/0/$walletPackage/files/cardpersonality")

    private fun dirFaces(): File = File(rootDir(), "faces")

    private fun dirOriginals(): File = File(rootDir(), "originals")

    private fun configFile(): File = File(rootDir(), "config.json")

    private fun faceFileForWrite(fileKey: String, version: Int): File =
        File(dirFaces(), sha256Hex(fileKey).take(24) + "_v" + version + ".jpg")

    private fun loadLocalConfig() {
        try {
            val f = configFile()
            if (f.isFile) {
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
