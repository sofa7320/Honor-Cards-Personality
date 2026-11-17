package com.cardpersonality.honor.hook.oppo

import android.util.Base64
import com.cardpersonality.honor.hook.HLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Round-24: wallet-process loopback SERVER (reference-repo architecture, proven on device).
 *
 * The reference module (`opcardcustomizer`) does not push from the panel into the wallet; instead
 * the wallet runs a tiny request/response server and the panel app connects as a client:
 *
 *   panel  -> wallet:  URLS  /  PUT <url>\n<b64 JPEG>  /  DEL <url>  /  CLEAR  /  PING
 *   wallet -> panel:   card lines + END  /  OK  /  ERR ...  /  PONG
 *
 * This avoids the one-way "fire and forget" push path whose PUT frames silently never landed.
 * Server is started fail-open in every wallet process; the first one to bind a port serves all
 * commands, and all processes share the same APP-private files directory, so a face written by the
 * serving process is visible to the process that actually renders the cards.
 */
internal object OppoFaceServer {
    val PORTS = intArrayOf(47121, 47122, 47123)

    /** Diagnostics literal (visible in logs/dex checks). */
    const val PORTS_LABEL = "47121,47122,47123"
    private const val HOST = "127.0.0.1"
    private const val CMD_URLS = "URLS"
    private const val CMD_PUT = "PUT"
    private const val CMD_DEL = "DEL"
    private const val CMD_CLEAR = "CLEAR"
    private const val CMD_PING = "PING"
    private const val END = "END"
    private const val OK = "OK"

    private val started = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null
    @Volatile var port: Int = -1
        private set

    /** Wallet-side card list supplier (url \t name \t type \t balance lines). */
    @Volatile private var urlsSupplier: (() -> String)? = null

    fun start(urlsSupplier: () -> String) {
        this.urlsSupplier = urlsSupplier
        if (!started.compareAndSet(false, true)) return
        Thread({ runServer() }, "cardpersonality-face-server").apply {
            isDaemon = true
            start()
        }
    }

    fun isRunning(): Boolean = port > 0

    private fun runServer() {
        val socket = bindFirstFreePort()
        if (socket == null) {
            started.set(false)
            HLog.w("OPPO face server: no free port (47121..47123)")
            return
        }
        server = socket
        port = socket.localPort
        HLog.i("OPPO face server listening 127.0.0.1:$port")
        OppoBridge.oplog("oppo", "server-up", "port=$port ports=$PORTS_LABEL")
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                break
            }
            Thread({ handle(client) }, "cardpersonality-face-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun bindFirstFreePort(): ServerSocket? {
        val loopback = InetAddress.getByName(HOST)
        for (port in PORTS) {
            try {
                return ServerSocket(port, 4, loopback)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 8_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8), false)
            try {
                val line = reader.readLine() ?: return
                when {
                    line == CMD_PING -> writer.println("PONG")

                    line == CMD_URLS -> {
                        val text = try {
                            urlsSupplier?.invoke() ?: ""
                        } catch (_: Throwable) {
                            ""
                        }
                        if (text.isNotBlank()) writer.println(text)
                        writer.println(END)
                    }

                    line == CMD_CLEAR -> {
                        OppoBridge.clearAllFaces()
                        writer.println(OK)
                    }

                    line.startsWith("$CMD_PUT ") -> {
                        val url = line.substring(CMD_PUT.length + 1).trim()
                        val b64 = reader.readLine()
                        if (url.isEmpty() || b64.isNullOrEmpty()) {
                            writer.println("ERR bad request")
                        } else {
                            val bytes = try {
                                Base64.decode(b64, Base64.NO_WRAP)
                            } catch (_: Throwable) {
                                null
                            }
                            if (bytes == null || bytes.isEmpty()) {
                                writer.println("ERR bad base64")
                            } else {
                                val ok = OppoBridge.storeFaceForUrl(url, bytes)
                                writer.println(if (ok) OK else "ERR write failed")
                            }
                        }
                    }

                    line.startsWith("$CMD_DEL ") -> {
                        val url = line.substring(CMD_DEL.length + 1).trim()
                        OppoBridge.removeFaceForUrl(url)
                        writer.println(OK)
                    }

                    else -> writer.println("ERR unknown command")
                }
            } catch (t: Throwable) {
                try {
                    writer.println("ERR ${t.javaClass.simpleName}")
                } catch (_: Throwable) {
                }
            } finally {
                try {
                    writer.flush()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        } finally {
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    /** Engine supplies the URLS list from its detected cards. */
    fun urlsFromCards(cardsJson: String): String {
        return try {
            val arr = JSONArray(cardsJson)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("faceUrl").ifBlank {
                    o.optString("url").ifBlank { "id:" + o.optString("identity") }
                }
                if (url.isBlank() || url == "null") continue
                val name = o.optString("name").replace('\t', ' ')
                val type = o.optString("type")
                val balance = o.optString("balance")
                sb.append(url).append('\t').append(name).append('\t').append(type).append('\t')
                    .append(balance).append('\n')
            }
            sb.toString()
        } catch (_: Throwable) {
            ""
        }
    }
}
