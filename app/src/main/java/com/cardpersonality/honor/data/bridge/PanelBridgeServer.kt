package com.cardpersonality.honor.data.bridge

import android.content.Context
import com.cardpersonality.honor.data.FaceRepository
import com.cardpersonality.honor.data.OpLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest

/**
 * Round-18 panel-side loopback bridge (server).
 *
 * The OPPO wallet (targetSdk 36) cannot resolve our ContentProvider ("Unknown authority") and its
 * non-main processes have no Application Context, so the OPPO wallet talks to the panel over a
 * loopback TCP bridge instead (same approach as the friend module):
 *
 *   127.0.0.1:47131..47135
 *   wallet -> panel: HELLO / CARD / OPLOG / ORIGINAL / PONG
 *   panel -> wallet: CONFIG / PUT / DEL / REQ_ORIGINAL / PING
 *
 * This file keeps the state the UI needs (report.json-compatible cards, per-process heartbeats,
 * original-face snapshots) and pushes rendered faces + the scoped config back to the wallet; the
 * snapshot is kept on disk so a reconnecting wallet gets everything re-pushed.
 *
 * The Honor wallet keeps using the existing ContentProvider path untouched.
 */
object PanelBridgeServer {
    const val BASE_PORT = 47131

    /** Round-19: unified explicit loopback ports (panel + wallet use the same literal list). */
    private val PORTS = intArrayOf(47131, 47132, 47133, 47134, 47135)

    /** Diagnostics literal: the complete loopback port list (visible in logs/HELLO). */
    const val PORTS_LABEL = "47131,47132,47133,47134,47135"

    private const val PROCESS_STATE_FILE = "oppo_engine_processes.json"
    private const val ORIGINALS_DIR = "originals"
    private const val SNAPSHOT_FILE = "bridge_snapshot.json"

    private val STARTED = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cardpersonality-bridge-accept").apply { isDaemon = true }
    }
    private val rxExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cardpersonality-bridge-rx").apply { isDaemon = true }
    }
    private val clients = CopyOnWriteArrayList<Client>()

    @Volatile private var appContext: Context? = null
    @Volatile private var listenPort: Int = 0
    @Volatile private var lastHelloAt: Long = 0L
    @Volatile private var lastHelloProcess: String = ""
    private val reportVersion = java.util.concurrent.atomic.AtomicLong(0L)
    private val boundPorts = CopyOnWriteArrayList<Int>()

    private class Client(val socket: Socket, val writer: OutputStreamWriter)

    // ================================================================ lifecycle

    /** Starts the loopback listener once (safe to call from Activity/Provider onCreate). */
    fun ensureStarted(ctx: Context) {
        appContext = ctx.applicationContext ?: ctx
        if (!STARTED.compareAndSet(false, true)) return
        acceptExecutor.execute { acceptLoop() }
    }

    fun isConnected(): Boolean = clients.isNotEmpty()
    fun lastHelloAt(): Long = lastHelloAt
    fun lastHelloProcess(): String = lastHelloProcess

    /** Round-19: increments whenever cards are merged from the wallet (UI watcher can refresh). */
    fun reportVersion(): Long = reportVersion.get()
    fun port(): Int = listenPort

    /**
     * Round-19: bind EVERY port in 47131..47135 explicitly on the IPv4 loopback 127.0.0.1.
     * `ServerSocket(port, backlog, InetAddress.getLoopbackAddress())` resolved to IPv6-only [::1]
     * on the test device, so the wallet's 127.0.0.1 client always got "Connection refused".
     * IPv4 is tried first and `::1` is kept as a fallback.
     */
    private fun acceptLoop() {
        for (port in PORTS) {
            val server = bindLoopback(port) ?: continue
            synchronized(boundPorts) {
                if (boundPorts.isEmpty()) listenPort = port
                boundPorts.add(port)
            }
            val thread = Thread({ acceptOn(server, port) }, "cardpersonality-bridge-$port").apply {
                isDaemon = true
            }
            thread.start()
        }
        if (boundPorts.isEmpty()) {
            OpLog.record(appContext, "bridge", "bind-failed", "ports=${PORTS.joinToString(",")}")
        } else {
            OpLog.record(
                appContext,
                "bridge",
                "listening",
                "bind=127.0.0.1(ipv4-first)/::1-fallback ports=$PORTS_LABEL bound=$boundPorts",
            )
        }
    }

    /** Binds 127.0.0.1 first (the wallet always uses the IPv4 literal), then ::1. */
    private fun bindLoopback(port: Int): ServerSocket? {
        for (host in listOf("127.0.0.1", "::1")) {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(java.net.InetSocketAddress(InetAddress.getByName(host), port), 8)
                return server
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun acceptOn(server: ServerSocket, port: Int) {
        while (true) {
            try {
                val socket = server.accept()
                socket.tcpNoDelay = true
                val client = Client(socket, OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                clients.add(client)
                rxExecutor.execute { readLoop(client) }
            } catch (_: Throwable) {
                try {
                    Thread.sleep(500L)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun readLoop(client: Client) {
        try {
            BufferedReader(InputStreamReader(client.socket.getInputStream(), Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    try {
                        handleUpstream(line)
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            clients.remove(client)
            try {
                client.socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    // ================================================================ upstream (wallet -> panel)

    private fun handleUpstream(line: String) {
        val ctx = appContext ?: return
        val parts = line.split('\t')
        when (parts[0]) {
            "HELLO" -> {
                if (parts.size >= 2) {
                    val json = parts[1]
                    mergeProcessHeartbeat(ctx, json)
                    lastHelloAt = System.currentTimeMillis()
                    lastHelloProcess = try {
                        JSONObject(json).optString("processName")
                    } catch (_: Throwable) {
                        ""
                    }
                    pushSnapshotTo(clientOfLine(line))
                    // Round-19 pull protocol: ask the wallet to dump its full detected-card snapshot.
                    sendTo(clientOfLine(line), "REQ_CARDS")
                }
            }
            "CARDS" -> {
                if (parts.size >= 2) {
                    try {
                        val arr = JSONArray(parts[1])
                        if (arr.length() > 0) {
                            FaceRepository.reportCards(ctx, arr.toString())
                            reportVersion.incrementAndGet()
                            OpLog.record(ctx, "bridge", "cards-merge", "n=${arr.length()}")
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
            "CARD" -> {
                if (parts.size >= 2) {
                    try {
                        FaceRepository.reportCards(ctx, JSONArray().put(JSONObject(parts[1])).toString())
                        reportVersion.incrementAndGet()
                    } catch (_: Throwable) {
                    }
                }
            }
            "OPLOG" -> {
                if (parts.size >= 4) {
                    OpLog.record(ctx, parts[1], parts[2], parts[3])
                }
            }
            "ORIGINAL" -> {
                if (parts.size >= 4) {
                    val bytes = decode(parts[3]) ?: return
                    writeOriginal(ctx, parts[1], parts[2], bytes)
                }
            }
            "ORIGINAL_INVALID" -> {
                if (parts.size >= 3) {
                    try {
                        File(File(ctx.filesDir, ORIGINALS_DIR), sha256Hex("${parts[1]}:${parts[2]}") + ".jpg").delete()
                        OpLog.record(ctx, "oppo", "original-invalid", "${parts[1]}:${parts[2]}")
                    } catch (_: Throwable) {
                    }
                }
            }
            "PONG" -> {
            }
        }
    }

    private fun clientOfLine(line: String): Client? = clients.lastOrNull()

    private fun mergeProcessHeartbeat(ctx: Context, json: String) {
        try {
            val obj = JSONObject(json)
            val processName = obj.optString("processName").ifBlank { "default" }
            obj.put("processName", processName)
            obj.put("lastSeen", System.currentTimeMillis())
            val map = readProcessMap(ctx)
            map.put(processName, obj)
            writeFileAtomically(File(ctx.filesDir, PROCESS_STATE_FILE), map.toString())
            OpLog.record(
                ctx,
                "oppo",
                "heartbeat-ok",
                "proc=$processName armed=${obj.optBoolean("armed")} hooks=${obj.optInt("installedCount")}",
            )
        } catch (_: Throwable) {
        }
    }

    private fun writeOriginal(ctx: Context, type: String, id: String, bytes: ByteArray) {
        try {
            val dir = File(ctx.filesDir, ORIGINALS_DIR).apply { mkdirs() }
            val file = File(dir, sha256Hex("$type:$id") + ".jpg")
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            OpLog.record(ctx, "oppo", "original-saved", "$type:$id ${bytes.size}B")
        } catch (_: Throwable) {
        }
    }

    // ================================================================ downstream (panel -> wallet)

    fun pushConfig(ctx: Context, configJson: String) {
        val snapshot = readSnapshot(ctx)
        snapshot.put("config", configJson)
        writeFileAtomically(File(ctx.filesDir, SNAPSHOT_FILE), snapshot.toString())
        broadcast("CONFIG\t$configJson")
    }

    fun pushFace(ctx: Context, fileKey: String, version: Int, jpeg: ByteArray, faceUrl: String? = null) {
        val snapshot = readSnapshot(ctx)
        val faces = snapshot.optJSONObject("faces") ?: JSONObject().also { snapshot.put("faces", it) }
        faces.put(
            fileKey,
            JSONObject()
                .put("version", version)
                .put("bytes", jpeg.size)
                .put("encoded", encode(jpeg))
                .put("faceUrl", faceUrl ?: ""),
        )
        writeFileAtomically(File(ctx.filesDir, SNAPSHOT_FILE), snapshot.toString())
        val urlSegment = encode((faceUrl ?: "").toByteArray(Charsets.UTF_8))
        broadcast("PUT\t$fileKey\t$version\t${encode(jpeg)}\t$urlSegment")
    }

    fun pushFaceDelete(ctx: Context, fileKey: String) {
        broadcast("DEL\t$fileKey")
    }

    fun requestOriginal(ctx: Context, type: String, id: String) {
        broadcast("REQ_ORIGINAL\t$type\t$id")
    }

    /** Round-22: reset/native face change — delete the cached original and ask the wallet to drop it. */
    fun refreshOriginal(ctx: Context, type: String, id: String) {
        try {
            File(File(ctx.filesDir, ORIGINALS_DIR), sha256Hex("$type:$id") + ".jpg").delete()
        } catch (_: Throwable) {
        }
        broadcast("DEL_ORIGINAL\t$type\t$id")
        OpLog.record(ctx, "bridge", "refresh-original", "$type:$id")
    }

    private fun pushSnapshotTo(client: Client?) {
        val ctx = appContext ?: return
        val snapshot = readSnapshot(ctx)
        val config = snapshot.optString("config", "")
        if (config.isNotEmpty()) {
            sendTo(client, "CONFIG\t$config")
        }
        snapshot.optJSONObject("faces")?.let { faces ->
            for (key in faces.keys()) {
                val entry = faces.optJSONObject(key) ?: continue
                val encoded = entry.optString("encoded", "")
                if (encoded.isEmpty()) continue
                val urlSegment = encode(entry.optString("faceUrl", "").toByteArray(Charsets.UTF_8))
                sendTo(client, "PUT\t$key\t${entry.optInt("version", 0)}\t$encoded\t$urlSegment")
            }
        }
    }

    private fun broadcast(line: String) {
        for (client in clients) sendTo(client, line)
    }

    private fun sendTo(client: Client?, line: String) {
        client ?: return
        try {
            val keep = if (line.length > 4 * 1024 * 1024) line.substring(0, 4 * 1024 * 1024) else line
            synchronized(this) {
                client.writer.write(keep)
                client.writer.write("\n")
                client.writer.flush()
            }
        } catch (_: Throwable) {
            clients.remove(client)
        }
    }

    // ================================================================ data access (UI / adapters)

    /** Existing report.json (cards include wallet/balance for OPPO cards). */
    fun reportJson(ctx: Context): String = FaceRepository.reportJson(ctx)

    /** Merged engine state: per-processes heartbeats + bridge connection state. */
    fun engineStateJson(ctx: Context): String {
        return try {
            val map = readProcessMap(ctx)
            val entries = ArrayList<JSONObject>()
            val keys = map.keys()
            while (keys.hasNext()) entries.add(map.optJSONObject(keys.next()))
            entries.sortByDescending { it.optLong("lastSeen", 0L) }
            val processes = JSONArray()
            entries.forEach { processes.put(it) }
            val merged = JSONObject()
            entries.firstOrNull()?.let { last ->
                val lastKeys = last.keys()
                while (lastKeys.hasNext()) {
                    val k = lastKeys.next()
                    merged.put(k, last.get(k))
                }
            }
            merged.put("processes", processes)
            merged.put("lastHeartbeatProcess", entries.firstOrNull()?.optString("processName").orEmpty())
            merged.put("heartbeatSent", entries.isNotEmpty())
            merged.put("reportVersion", reportVersion.get())
            merged.put("bridgeConnected", isConnected())
            merged.put("bridgePort", listenPort)
            merged.put("bridgePorts", org.json.JSONArray(boundPorts.toList()))
            merged.put("lastHelloAt", lastHelloAt)
            merged.toString()
        } catch (_: Throwable) {
            "{}"
        }
    }

    fun processes(ctx: Context): JSONArray {
        return try {
            JSONObject(engineStateJson(ctx)).optJSONArray("processes") ?: JSONArray()
        } catch (_: Throwable) {
            JSONArray()
        }
    }

    /** Current-face snapshot bytes (hook-captured original) or null. */
    fun originalBytes(ctx: Context, type: String, id: String): ByteArray? {
        return try {
            val file = File(File(ctx.filesDir, ORIGINALS_DIR), sha256Hex("$type:$id") + ".jpg")
            if (file.isFile && file.length() > 0L) file.readBytes() else null
        } catch (_: Throwable) {
            null
        }
    }

    // ================================================================ files / helpers

    private fun readProcessMap(ctx: Context): JSONObject {
        return try {
            val f = File(ctx.filesDir, PROCESS_STATE_FILE)
            if (f.isFile) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject()
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    private fun readSnapshot(ctx: Context): JSONObject {
        return try {
            val f = File(ctx.filesDir, SNAPSHOT_FILE)
            if (f.isFile) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject()
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    private fun writeFileAtomically(file: File, text: String) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
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
