package com.cardpersonality.honor.data.bridge

import android.util.Base64
import android.util.Log
import com.cardpersonality.honor.data.OpLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Round-24 panel-side client for the wallet's loopback face server
 * (reference-repo `BridgeClient` architecture: request/response, so a PUT cannot silently vanish).
 *
 * All calls are blocking; run them on an IO thread. Connect timeout is short so UI paths can retry
 * later without stalling.
 */
object WalletFaceClient {
    private const val TAG = "CardPersonality"
    private val PORTS = intArrayOf(47121, 47122, 47123)
    private const val PORTS_LABEL = "47121,47122,47123"
    private const val HOST = "127.0.0.1"
    private const val CONNECT_TIMEOUT_MS = 600
    private const val READ_TIMEOUT_MS = 8_000

    private fun <T> withConnection(fallback: T, block: (BufferedReader, PrintWriter) -> T): T {
        for (port in PORTS) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), false)
                return block(reader, writer)
            } catch (_: Throwable) {
            } finally {
                try {
                    socket?.close()
                } catch (_: Throwable) {
                }
            }
        }
        return fallback
    }

    fun ping(): Boolean = withConnection(false) { reader, writer ->
        writer.println("PING")
        writer.flush()
        reader.readLine() == "PONG"
    }

    /** Push one rendered face keyed by the wallet's original face URL. */
    fun put(url: String, jpeg: ByteArray): Boolean {
        if (url.isBlank() || jpeg.isEmpty()) return false
        val encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        return withConnection(false) { reader, writer ->
            writer.println("PUT $url")
            writer.println(encoded)
            writer.flush()
            val reply = reader.readLine()
            val ok = reply == "OK"
            if (!ok) Log.w(TAG, "wallet PUT failed: $reply")
            ok
        }
    }

    fun del(url: String): Boolean = withConnection(false) { reader, writer ->
        writer.println("DEL $url")
        writer.flush()
        reader.readLine() == "OK"
    }

    fun clear(): Boolean = withConnection(false) { reader, writer ->
        writer.println("CLEAR")
        writer.flush()
        reader.readLine() == "OK"
    }

    /** Optional diagnostics: wallet URL list lines (without trailing END). */
    fun urls(): List<String>? = withConnection(null) { reader, writer ->
        writer.println("URLS")
        writer.flush()
        val out = ArrayList<String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line == "END") return@withConnection out
            if (line.isNotBlank()) out.add(line)
            if (out.size > 5000) break
        }
        null
    }

    private fun isOppoWallet(walletPackage: String?): Boolean {
        val pkg = walletPackage?.trim().orEmpty()
        if (pkg.isEmpty()) return true // legacy callers: OPPO-only helper context
        return com.cardpersonality.honor.data.WalletScope.isOppo(pkg)
    }

    /** Push with a few bounded retries (wallet may be starting/warming up). */
    fun putWithRetry(
        ctx: android.content.Context,
        url: String,
        fileKey: String,
        jpeg: ByteArray,
        attempts: Int = 4,
        walletPackage: String? = null
    ): Boolean {
        if (!isOppoWallet(walletPackage)) {
            OpLog.record(ctx, "bridge", "push-face-skip", "key=$fileKey wallet=$walletPackage (not OPPO)")
            return false
        }
        var delay = 400L
        repeat(attempts) { index ->
            if (put(url, jpeg)) {
                OpLog.record(ctx, "bridge", "push-face-ok", "key=$fileKey try=${index + 1} url=${url.take(80)}")
                return true
            }
            try {
                Thread.sleep(delay)
            } catch (_: Throwable) {
            }
            delay = (delay * 2).coerceAtMost(2_000L)
        }
        OpLog.record(ctx, "bridge", "push-face-fail", "key=$fileKey url=${url.take(80)}")
        return false
    }
}
