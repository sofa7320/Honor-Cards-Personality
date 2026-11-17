package com.cardpersonality.honor.ui.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.cardpersonality.honor.ui.WalletProbe
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-tap diagnostic report used by the About page.
 *
 * Everything is best-effort: a missing root binary, denied command, missing LSPosed directory or a
 * missing OpLog file only produces an explanatory line in the report — collection never throws to
 * the caller and the exported file is always plain UTF-8 text (< 2 MB).
 */
object DiagnosticExporter {

    data class Result(val path: String?, val error: String? = null) {
        val ok: Boolean get() = path != null
    }

    private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    private const val SECTION_BYTES = 400 * 1024
    private const val RELATIVE_DIR = "Download/honor_cards_personality/logs"

    private val PROP_KEYS = listOf(
        "ro.build.version.release",
        "ro.build.version.sdk",
        "ro.build.version.security_patch",
        "ro.debuggable",
        "ro.secure",
        "ro.build.type",
        "ro.build.tags",
        "ro.product.brand",
        "ro.product.manufacturer",
        "ro.product.model",
        "ro.product.device",
        "ro.product.name",
        "ro.boot.verifiedbootstate",
        "ro.boot.flash.locked",
        "ro.boot.veritymode",
        "persist.sys.timezone",
    )

    suspend fun export(ctx: Context): Result = withContext(Dispatchers.IO) {
        try {
            val text = buildReport(ctx)
            writeReport(ctx, text)
        } catch (t: Throwable) {
            Result(null, t.message?.take(200) ?: t.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------ report body

    private fun buildReport(ctx: Context): String {
        val sb = StringBuilder()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.appendLine("荣耀卡面工坊 · 诊断报告")
        sb.appendLine("生成时间：$stamp")
        sb.appendLine()

        section(sb, "1. 基本信息") {
            appendLine("应用版本：${com.cardpersonality.honor.BuildConfig.VERSION_NAME}（versionCode ${com.cardpersonality.honor.BuildConfig.VERSION_CODE}）")
            appendLine("包名：${ctx.packageName}")
            appendLine("构建类型：${com.cardpersonality.honor.BuildConfig.BUILD_TYPE}")
            appendLine("Android 版本：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("品牌 / 厂商：${Build.BRAND} / ${Build.MANUFACTURER}")
            appendLine("型号 / 设备 / 产品：${Build.MODEL} / ${Build.DEVICE} / ${Build.PRODUCT}")
            appendLine("指纹：${Build.FINGERPRINT}")
            appendLine("系统 ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("语言 / 时区：${Locale.getDefault()} / ${TimeZone.getDefault().id}")
            val dm = ctx.resources.displayMetrics
            appendLine("屏幕：${dm.widthPixels}×${dm.heightPixels} px，密度 ${dm.density}（${dm.densityDpi} dpi）")
        }

        section(sb, "2. Root 环境") {
            val suPaths = listOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/data/adb/ksu/bin/su",
            )
            val suFound = suPaths.filter { File(it).exists() }
            if (suFound.isEmpty()) {
                appendLine("未检测到 su 可执行文件（未获取 root 权限）。")
            } else {
                appendLine("su 路径：" + suFound.joinToString(", "))
            }
            appendLine("Magisk 目录 /data/adb/magisk：" + File("/data/adb/magisk").exists())
            appendLine("KernelSU 目录 /data/adb/ksu：" + File("/data/adb/ksu").exists())
            appendLine("APatch 目录 /data/adb/ap：" + File("/data/adb/ap").exists())
            appendLine("which su：" + runCommand(listOf("which", "su"), 2000))
            appendLine("su -c id（超时 3s）：" + runCommand(listOf("su", "-c", "id"), 3000))
            appendLine("getenforce：" + runCommand(listOf("getenforce"), 2000))
            appendLine("getprop:")
            for (key in PROP_KEYS) {
                appendLine("  $key = " + runCommand(listOf("getprop", key), 1500))
            }
        }

        section(sb, "3. LSPosed / 模块状态") {
            appendLine("/data/adb/modules/zygisk_lsposed 存在：" + File("/data/adb/modules/zygisk_lsposed").exists())
            appendLine("/data/adb/lspd 存在：" + File("/data/adb/lspd").exists())
            appendLine("/data/adb/modules 列表：" + listDir("/data/adb/modules"))
            val lspctl = runCommand(listOf("su", "-c", "lspctl status"), 5000)
            appendLine("lspctl status（前 20 行）：")
            lspctl.lineSequence().take(20).forEach { appendLine("  " + it) }
            appendLine("provider ping：" + providerCall(ctx, "ping"))
            val walletsJson = providerCall(ctx, "wallets")
            appendLine("provider wallets：")
            appendLine(walletsJson)
            appendLine("provider getReport（截断）：")
            appendLine(providerCall(ctx, "getReport").take(4000))
            appendLine("OPPO 引擎状态（provider engine_state）：")
            appendLine("--- OPPO 状态（bridge 为准，v44+）---")
            val bridge = com.cardpersonality.honor.data.bridge.PanelBridgeServer
            val bridgeJson = runCatching { bridge.engineStateJson(ctx) }.getOrDefault("{}")
            val bridgeConnected = runCatching { bridge.isConnected() }.getOrDefault(false)
            val bridgePort = runCatching { bridge.port() }.getOrDefault(0)
            val bridgeHelloAt = runCatching { bridge.lastHelloAt() }.getOrDefault(0L)
            val bridgeHelloProcess = runCatching { bridge.lastHelloProcess() }.getOrDefault("")
            appendLine(
                "bridge 状态：isConnected=$bridgeConnected port=$bridgePort " +
                    "lastHelloAt=$bridgeHelloAt lastHelloProcess=${bridgeHelloProcess.ifEmpty { "（无）" }}",
            )
            val bridgeReport = runCatching { bridge.reportJson(ctx) }.getOrDefault("{}")
            val bridgeCardCount = try {
                org.json.JSONObject(bridgeReport).optJSONArray("cards")?.length() ?: 0
            } catch (_: Throwable) {
                0
            }
            appendLine("bridge report 卡片数：$bridgeCardCount")
            appendLine("bridge processes 原始 JSON：")
            appendLine(processesJson(bridgeJson).take(4000))
            appendLine("bridge engine_state 原始 JSON：")
            appendLine(bridgeJson.take(4000))
            appendLine()
            appendLine("--- 旧 provider 字段（历史参考，bridge 为准）---")
            val engineJson = providerCallExtra(ctx, "engine_state", "engine")
            appendLine("OPPO 引擎摘要：" + summarizeEngine(engineJson))
            appendLine("钱包注入判定：" + injectionVerdict(ctx, engineJson))
            appendLine("注入进程摘要：" + processSummary(engineJson))
            appendLine("processes 原始 JSON：")
            appendLine(processesJson(engineJson).take(4000))
            appendLine("engine_state 原始 JSON：")
            appendLine(engineJson.take(4000))
            appendLine("OPPO 引擎状态（getReport extra engine，截断）：")
            appendLine(providerCallExtra(ctx, "getReport", "engine").take(2000))
        }

        section(sb, "4. 钱包与开关状态") {
            val snapshot = WalletProbe.query(ctx)
            if (snapshot == null) {
                appendLine("wallets provider 未返回数据。")
            } else {
                appendLine("activeWalletPackage：${snapshot.activeWalletPackage ?: "（无）"}")
                snapshot.wallets.forEach { wallet ->
                    appendLine(
                        "钱包：${wallet.label} / ${wallet.packageName} / " +
                            "kind=${wallet.kind} / installed=${wallet.installed} / " +
                            "version=${wallet.versionName ?: "未知"}",
                    )
                }
            }
            appendLine("自动吸附开关：" + com.cardpersonality.honor.ui.util.EditorPrefs.isSnapEnabled(ctx))
            val cards = runCatching { com.cardpersonality.honor.data.FaceRepository.cards(ctx) }.getOrDefault(emptyList())
            appendLine("上报卡片数量：" + cards.size)
            cards.take(100).forEach { card ->
                val cfg = runCatching {
                    com.cardpersonality.honor.data.FaceRepository.resolveConfig(
                        ctx,
                        card.key.type,
                        card.key.id,
                    )
                }.getOrNull()
                val hidden = runCatching {
                    com.cardpersonality.honor.data.FaceRepository.hiddenAppElements(
                        ctx,
                        card.key.type,
                        card.key.id,
                    )
                }.getOrDefault(emptyList())
                appendLine(
                    "卡片：type=${card.type} / identity=${maskIdentity(card.key.id)} / " +
                        "wallet=${card.walletPackage} / name=${card.name.take(40)} / " +
                        "hasCustomFace=${cfg?.hasCustomFace ?: false} / " +
                        "hidden=${hidden.size} / stickers=${cfg?.stickers?.size ?: 0}",
                )
            }
        }

        section(sb, "5. 模块操作记录（最近 ≤300 条）") {
            val log = readOpLog(ctx)
            appendLine(if (log.isEmpty()) "（暂无操作记录）" else log)
        }

        section(sb, "6. 异常文件摘要") {
            val crashDir = File(ctx.filesDir, "crash")
            val crashFiles = crashDir.listFiles()?.map { it.name }?.sorted().orEmpty()
            appendLine("应用 crash 目录（${crashDir.absolutePath}）：" + if (crashFiles.isEmpty()) "（空）" else crashFiles.take(30).joinToString(", "))
            appendLine("最近 ANR trace（需 root）：" + runCommand(listOf("su", "-c", "ls -t /data/anr | head -5"), 3000))
        }

        val text = sb.toString()
        return if (text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
            text
        } else {
            text.take(MAX_FILE_BYTES / 4)
        }
    }

    private inline fun section(sb: StringBuilder, title: String, body: StringBuilder.() -> Unit) {
        sb.appendLine("==================================================================")
        sb.appendLine(title)
        sb.appendLine("==================================================================")
        val sectionBuilder = StringBuilder()
        sectionBuilder.body()
        val text = sectionBuilder.toString()
        sb.append(if (text.toByteArray(Charsets.UTF_8).size <= SECTION_BYTES) text else text.take(SECTION_BYTES / 4))
        sb.appendLine()
    }

    // ------------------------------------------------------------------ helpers

    private fun maskIdentity(identity: String?): String {
        if (identity.isNullOrEmpty()) return "-"
        return if (identity.length <= 4) "****" else "****" + identity.takeLast(4)
    }

    private fun listDir(path: String): String {
        return try {
            val dir = File(path)
            if (!dir.isDirectory) return "（不可读）"
            dir.list()?.sorted()?.take(30)?.joinToString(", ") ?: "（空）"
        } catch (_: Throwable) {
            "（不可读）"
        }
    }

    private fun providerCall(ctx: Context, method: String): String {
        return try {
            val bundle = ctx.contentResolver.call(
                Uri.parse("content://" + com.cardpersonality.honor.data.FaceRepository.AUTHORITY),
                method,
                null,
                null,
            ) ?: return "（无返回）"
            val json = bundle.getString("json")
            if (!json.isNullOrBlank()) json.take(4000) else bundle.keySet().joinToString(", ")
        } catch (t: Throwable) {
            "调用失败：" + (t.message?.take(120) ?: t.javaClass.simpleName)
        }
    }

    /** Human summary of engine.processes (per-process injection evidence). */
    private fun processSummary(json: String): String {
        return try {
            val obj = org.json.JSONObject(json)
            val array = obj.optJSONArray("processes") ?: return "（hook 未提供 processes 字段）"
            if (array.length() == 0) return "（无进程记录）"
            buildString {
                append("共 ${array.length()} 个进程：")
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val name = item.optString("process")
                        .ifBlank { item.optString("processName") }
                        .ifBlank { "未知" }
                    val main = item.optBoolean(
                        "isMain",
                        item.optBoolean("isMainProcess", false),
                    )
                    val heartbeatAt = item.optLong(
                        "lastHookReportAt",
                        item.optLong("lastHeartbeat", 0L),
                    )
                    val age = if (heartbeatAt > 0L) {
                        (System.currentTimeMillis() - heartbeatAt).coerceAtLeast(0L)
                    } else {
                        null
                    }
                    append("\n  - $name")
                    append(if (main) "（主进程）" else "（子进程）")
                    append(" armed=${item.optBoolean("armed", false)}")
                    append(" hooks=${item.optInt("installedCount", item.optInt("hooks", 0))}")
                    append("/${item.optInt("attemptedCount", 0)}")
                    append(" 心跳=")
                    append(
                        when {
                            age == null -> "无"
                            age <= 60_000L -> "${age / 1000}s 前"
                            else -> "${age / 60_000L} 分钟前"
                        },
                    )
                }
            }
        } catch (_: Throwable) {
            "（无法解析 processes）"
        }
    }

    /** Exact engine.processes JSON array (or "[]"). */
    private fun processesJson(json: String): String {
        return try {
            val obj = org.json.JSONObject(json)
            obj.optJSONArray("processes")?.toString() ?: "[]"
        } catch (_: Throwable) {
            "[]"
        }
    }

    /** "Injected / not injected" verdict for the OPPO wallet process (heartbeat based). */
    private fun injectionVerdict(ctx: Context, json: String): String {
        return try {
            val obj = org.json.JSONObject(json)
            val cardCount = obj.optInt("cardCount", 0)
            var heartbeatAt = obj.optLong("lastHookReportAt", 0L)
            if (heartbeatAt <= 0L) {
                val file = File(ctx.filesDir, "oppo_engine.json")
                if (file.isFile) heartbeatAt = file.lastModified()
            }
            when {
                heartbeatAt <= 0L -> "未注入（无引擎心跳）"
                cardCount > 0 -> "已注入（已有卡片上报）"
                System.currentTimeMillis() - heartbeatAt <= 10 * 60_000L ->
                    "已注入（心跳 ${(System.currentTimeMillis() - heartbeatAt) / 1000}s 前，0 卡片）"
                else -> "未注入（心跳已过期：${(System.currentTimeMillis() - heartbeatAt) / 60_000L} 分钟前）"
            }
        } catch (_: Throwable) {
            "未知（无法解析 engine_state）"
        }
    }

    /** Human summary of the OPPO engine JSON used for troubleshooting the read pipeline. */
    private fun summarizeEngine(json: String): String {
        return try {
            val obj = org.json.JSONObject(json)
            val pair = obj.optInt("pairCount", -1)
            val capture = obj.optInt("captureCount", -1)
            val source = obj.optJSONObject("faceSource")
            val sourceText = if (source == null) {
                "无"
            } else {
                "url:" + source.optInt("url", 0) +
                    " file:" + source.optInt("file", 0) +
                    " bitmap:" + source.optInt("bitmap", 0) +
                    " none:" + source.optInt("none", 0)
            }
            "配对 ${if (pair < 0) "?" else pair} / 抓图 ${if (capture < 0) "?" else capture} / 来源 $sourceText"
        } catch (_: Throwable) {
            "（无法解析 engine JSON）"
        }
    }

    /** Reads one Bundle extra from a provider call (e.g. engine_state -> "engine"). */
    private fun providerCallExtra(ctx: Context, method: String, key: String): String {
        return try {
            val bundle = ctx.contentResolver.call(
                Uri.parse("content://" + com.cardpersonality.honor.data.FaceRepository.AUTHORITY),
                method,
                null,
                null,
            ) ?: return "（无返回）"
            val value = bundle.getString(key)
            if (!value.isNullOrBlank()) value.take(4000) else "（无 $key 字段）"
        } catch (t: Throwable) {
            "调用失败：" + (t.message?.take(120) ?: t.javaClass.simpleName)
        }
    }

    /** Runs a command with a hard timeout; never throws. */
    private fun runCommand(command: List<String>, timeoutMs: Long): String {
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val deadline = System.currentTimeMillis() + timeoutMs
                while (true) {
                    val line = reader.readLine() ?: break
                    sb.appendLine(line)
                    if (System.currentTimeMillis() > deadline) return@use sb.toString().trim()
                }
                sb.toString().trim()
            }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return (output.ifEmpty { "" } + "（超时 ${timeoutMs}ms，已终止）").trim()
            }
            output.ifEmpty { "（无输出，exit=${process.exitValue()}）" }
        } catch (t: Throwable) {
            "（执行失败：" + (t.message?.take(120) ?: t.javaClass.simpleName) + "）"
        }
    }

    private fun readOpLog(ctx: Context): String {
        // Module-provided ring buffer API (last <=300 entries, "时间|来源|操作|详情").
        val entries = runCatching {
            com.cardpersonality.honor.data.OpLog.read(ctx, 300)
        }.getOrDefault(emptyList())
        if (entries.isNotEmpty()) return entries.joinToString("\n").take(SECTION_BYTES)
        // Fallback: read the on-disk journal directly (e.g. older hook build).
        val file = File(ctx.filesDir, "oplog.txt")
        if (!file.isFile) return ""
        return try {
            file.readLines(Charsets.UTF_8).takeLast(300).joinToString("\n").take(SECTION_BYTES)
        } catch (_: Throwable) {
            ""
        }
    }

    // ------------------------------------------------------------------ writing

    private fun writeReport(ctx: Context, text: String): Result {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            return Result(null, "报告超过 2MB 上限（${bytes.size} bytes）")
        }
        val fileName = "diagnostic_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val written = writeViaMediaStore(ctx, fileName, bytes)
            if (written != null) return Result(written)
        }
        val legacy = writeToPublicDownload(fileName, bytes)
        if (legacy != null) return Result(legacy)
        return writeToAppFiles(ctx, fileName, bytes)
    }

    private fun writeViaMediaStore(ctx: Context, fileName: String, bytes: ByteArray): String? {
        return try {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes); it.flush() } ?: return null
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                "/sdcard/$RELATIVE_DIR/$fileName"
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeToPublicDownload(fileName: String, bytes: ByteArray): String? {
        return try {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(base, "honor_cards_personality/logs").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            file.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeToAppFiles(ctx: Context, fileName: String, bytes: ByteArray): Result {
        return try {
            val dir = File(ctx.filesDir, "diagnostics").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            Result(file.absolutePath, "已回退到应用私有目录")
        } catch (t: Throwable) {
            Result(null, "写入失败：" + (t.message?.take(120) ?: t.javaClass.simpleName))
        }
    }
}







