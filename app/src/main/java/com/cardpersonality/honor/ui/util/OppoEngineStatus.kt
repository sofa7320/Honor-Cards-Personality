package com.cardpersonality.honor.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * One OPPO wallet process as reported by the loopback bridge (`engine_state.processes`).
 * Every field is optional/defensive because the hook may be older or a probe may be missing.
 */
data class EngineProcess(
    val name: String,
    val isMain: Boolean,
    val armed: Boolean,
    val installedHooks: Int,
    val attemptedHooks: Int,
    val missed: List<String>,
    /** Class simple name -> found by the engine's probe. */
    val classProbes: Map<String, Boolean>,
    val heartbeatAgeMs: Long?,
) {
    val heartbeatFresh: Boolean
        get() = heartbeatAgeMs != null && heartbeatAgeMs <= OppoEngineStatus.FRESH_MS

    val heartbeatText: String
        get() = when {
            heartbeatAgeMs == null -> "无心跳"
            heartbeatAgeMs <= 60_000L -> "${heartbeatAgeMs / 1000}s 前"
            heartbeatAgeMs <= 60 * 60_000L -> "${heartbeatAgeMs / 60_000L} 分钟前"
            else -> "${heartbeatAgeMs / 3_600_000L} 小时前"
        }

    val classesSummary: String
        get() = if (classProbes.isEmpty()) {
            "类探测：无数据"
        } else {
            "类探测：" + classProbes.entries.joinToString(" ") { (name, found) ->
                name + "=" + if (found) "✓" else "✗"
            }
        }
}

/**
 * Panel-side interpretation of the OPPO bridge/hook engine state (round 15→18).
 *
 * Round 18: the OPPO wallet cannot reach our ContentProvider (targetSdk 36 package visibility), so
 * the wallet pushes HELLO/CARD/OPLOG/ORIGINAL over a loopback socket to
 * [com.cardpersonality.honor.data.bridge.PanelBridgeServer]. The bridge merges per-process
 * heartbeats + connection state into `engineStateJson()`, which is the primary data source here.
 */
data class OppoEngineStatus(
    val rawJson: String,
    val heartbeatAgeMs: Long?,
    val hooks: Int,
    val attempted: Int,
    val observedModels: Int,
    val reportedCards: Int,
    val cardCount: Int,
    val processName: String?,
    val armed: Boolean,
    val processes: List<EngineProcess> = emptyList(),
    // ---- round 18 bridge fields ----
    val bridgeConnected: Boolean = false,
    val bridgePort: Int = 0,
    val lastHelloAt: Long = 0L,
    val lastHelloProcess: String? = null,
    val lastHeartbeatProcess: String? = null,
) {
    /** Fresh heartbeat for any process / bridge HELLO. */
    val hasAnyHeartbeat: Boolean
        get() = processes.any { it.heartbeatFresh } ||
            (bridgeConnected && heartbeatAgeMs != null && heartbeatAgeMs <= FRESH_MS) ||
            (processes.isEmpty() && heartbeatAgeMs != null && heartbeatAgeMs <= FRESH_MS)

    val mainProcess: EngineProcess?
        get() = processes.firstOrNull { it.isMain }

    val hasMainHeartbeat: Boolean
        get() = mainProcess?.heartbeatFresh == true ||
            (processes.isEmpty() && bridgeConnected &&
                heartbeatAgeMs != null && heartbeatAgeMs <= FRESH_MS)

    val freshProcesses: List<EngineProcess>
        get() = processes.filter { it.heartbeatFresh }

    /** Bridge connected or cards already reported means the wallet side is alive. */
    val injected: Boolean
        get() = cardCount > 0 || bridgeConnected || hasAnyHeartbeat

    val heartbeatText: String
        get() = when {
            heartbeatAgeMs == null -> "无心跳"
            heartbeatAgeMs <= 60_000L -> "${heartbeatAgeMs / 1000}s 前"
            heartbeatAgeMs <= 60 * 60_000L -> "${heartbeatAgeMs / 60_000L} 分钟前"
            else -> "${heartbeatAgeMs / 3_600_000L} 小时前"
        }

    companion object {
        /** A heartbeat older than 10 minutes means the hook is not running (or was killed). */
        const val FRESH_MS = 10 * 60_000L
    }
}

object OppoEngineProbe {

    private val CLASS_PROBE_KEYS = listOf(
        "CircleNetworkImageView",
        "NfcCard",
        "NfcCardRoomCard_Impl",
        "NfcCardRoomDao_Impl",
    )

    /**
     * Primary source: loopback bridge (`PanelBridgeServer.engineStateJson`). Falls back to the
     * legacy provider `engine_state` for older module builds / Honor-only environments.
     */
    fun query(ctx: Context): OppoEngineStatus? {
        val bridgeJson = runCatching {
            com.cardpersonality.honor.data.bridge.PanelBridgeServer.engineStateJson(ctx)
        }.getOrNull()
        if (!bridgeJson.isNullOrBlank() && bridgeJson != "{}") {
            runCatching { parseBridge(bridgeJson) }.getOrNull()?.let { return it }
        }
        val providerJson = providerEngineJson(ctx) ?: return null
        return runCatching { parseLegacy(ctx, providerJson) }.getOrNull()
    }

    // ------------------------------------------------------------------ bridge

    private fun parseBridge(json: String): OppoEngineStatus {
        val obj = JSONObject(json)
        val processes = parseProcesses(obj.optJSONArray("processes"), obj)
        val lastHelloAt = obj.optLong("lastHelloAt", 0L)
        val rootHeartbeat = when {
            lastHelloAt > 0L -> lastHelloAt
            processes.isNotEmpty() -> processes.maxOfOrNull { p ->
                p.heartbeatAgeMs?.let { System.currentTimeMillis() - it } ?: 0L
            } ?: 0L
            else -> 0L
        }
        return OppoEngineStatus(
            rawJson = json,
            heartbeatAgeMs = ageOf(if (lastHelloAt > 0L) lastHelloAt else rootHeartbeat),
            hooks = obj.optInt("installedCount", obj.optInt("hooks", 0)),
            attempted = obj.optInt("attemptedCount", 0),
            observedModels = obj.optInt("observedModels", obj.optInt("observedUrls", 0)),
            reportedCards = obj.optInt("reportedCards", obj.optInt("cardCount", 0)),
            cardCount = obj.optInt("cardCount", 0),
            processName = obj.optString("lastHeartbeatProcess").takeIf { it.isNotBlank() }
                ?: processes.firstOrNull()?.name,
            armed = processes.any { it.armed } || obj.optBoolean("armed", false),
            processes = processes,
            bridgeConnected = obj.optBoolean(
                "bridgeConnected",
                com.cardpersonality.honor.data.bridge.PanelBridgeServer.isConnected(),
            ),
            bridgePort = obj.optInt(
                "bridgePort",
                com.cardpersonality.honor.data.bridge.PanelBridgeServer.port(),
            ),
            lastHelloAt = lastHelloAt,
            lastHelloProcess = com.cardpersonality.honor.data.bridge.PanelBridgeServer
                .lastHelloProcess().takeIf { it.isNotBlank() },
            lastHeartbeatProcess = obj.optString("lastHeartbeatProcess")
                .takeIf { it.isNotBlank() },
        )
    }

    // ------------------------------------------------------------------ legacy provider

    private fun parseLegacy(ctx: Context, json: String): OppoEngineStatus {
        val obj = JSONObject(json)
        var rootHeartbeatAt = obj.optLong("lastHookReportAt", 0L)
        if (rootHeartbeatAt <= 0L) rootHeartbeatAt = engineStateFileMtime(ctx)
        val processes = parseProcesses(obj.optJSONArray("processes"), obj)
        val age = ageOf(rootHeartbeatAt)
        return OppoEngineStatus(
            rawJson = json,
            heartbeatAgeMs = age,
            hooks = obj.optInt("installedCount", obj.optInt("hooks", 0)),
            attempted = obj.optInt("attemptedCount", 0),
            observedModels = obj.optInt("observedModels", obj.optInt("observedUrls", 0)),
            reportedCards = obj.optInt("reportedCards", obj.optInt("cardCount", 0)),
            cardCount = obj.optInt("cardCount", 0),
            processName = obj.optString("processName").takeIf { it.isNotBlank() },
            armed = obj.optBoolean("armed", false),
            processes = processes,
        )
    }

    // ------------------------------------------------------------------ shared parsing

    private fun parseProcesses(array: JSONArray?, root: JSONObject): List<EngineProcess> {
        if (array == null) return emptyList()
        val rootLastSeen = root.optLong(
            "lastHelloAt",
            root.optLong("lastHookReportAt", 0L),
        )
        val walletPkg = root.optString("walletPackage")
        val out = ArrayList<EngineProcess>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("processName")
                .ifBlank { item.optString("process") }
                .ifBlank { item.optString("name") }
                .ifBlank { "未知进程" }
            val isMain = item.optBoolean(
                "isDefaultProcess",
                item.optBoolean(
                    "isDefault",
                    item.optBoolean(
                        "isMain",
                        item.optBoolean("isMainProcess", name == walletPkg),
                    ),
                ),
            )
            var heartbeatAt = item.optLong(
                "lastSeen",
                item.optLong(
                    "lastHookReportAt",
                    item.optLong("lastHeartbeat", item.optLong("heartbeatAt", 0L)),
                ),
            )
            if (heartbeatAt <= 0L && (isMain || array.length() == 1)) {
                heartbeatAt = rootLastSeen
            }
            // lastSeen may be epoch seconds in some builds.
            if (heartbeatAt in 1L..9_999_999_999L) heartbeatAt *= 1000L
            out.add(
                EngineProcess(
                    name = name,
                    isMain = isMain,
                    armed = item.optBoolean("armed", false),
                    installedHooks = item.optInt("installedCount", item.optInt("hooks", 0)),
                    attemptedHooks = item.optInt("attemptedCount", 0),
                    missed = parseMissed(item),
                    classProbes = parseClassProbes(item),
                    heartbeatAgeMs = ageOf(heartbeatAt),
                ),
            )
        }
        return out
    }

    private fun parseMissed(item: JSONObject): List<String> {
        val array = item.optJSONArray("missed")
        if (array != null) {
            val out = ArrayList<String>()
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
            return out
        }
        val count = item.optInt("missedCount", 0)
        return if (count > 0) listOf("missed=$count") else emptyList()
    }

    private fun parseClassProbes(item: JSONObject): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        val obj = item.optJSONObject("classProbes")
            ?: item.optJSONObject("classes")
            ?: item.optJSONObject("classesFound")
        if (obj != null) {
            for (key in obj.keys()) out[key] = obj.optBoolean(key, false)
            return out
        }
        val found = ArrayList<String>()
        val array = item.optJSONArray("classProbes") ?: item.optJSONArray("classes")
        if (array != null) {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let { found.add(it) }
            }
        }
        if (found.isEmpty()) return out
        for (key in CLASS_PROBE_KEYS) {
            val match = found.any { it.contains(key, ignoreCase = true) }
            out[key] = match
        }
        for (name in found) {
            if (CLASS_PROBE_KEYS.none { name.contains(it, ignoreCase = true) }) {
                out[name] = true
            }
        }
        return out
    }

    private fun ageOf(timestamp: Long): Long? {
        if (timestamp <= 0L) return null
        return (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    }

    private fun providerEngineJson(ctx: Context): String? {
        return try {
            val bundle = ctx.contentResolver.call(
                Uri.parse("content://" + com.cardpersonality.honor.data.FaceRepository.AUTHORITY),
                "engine_state",
                null,
                null,
            ) ?: return null
            val json = bundle.getString("engine") ?: return null
            if (json.isBlank()) null else json
        } catch (_: Throwable) {
            null
        }
    }

    private fun engineStateFileMtime(ctx: Context): Long {
        return try {
            val file = File(ctx.filesDir, "oppo_engine.json")
            if (file.isFile) file.lastModified() else 0L
        } catch (_: Throwable) {
            0L
        }
    }

    /** Opens the LSPosed manager app (module scope settings live there). */
    fun openLsposedManager(ctx: Context): Boolean {
        val candidates = listOf(
            "org.lsposed.manager" to "org.lsposed.manager.ui.activity.MainActivity",
            "org.lsposed.manager" to null,
        )
        for ((pkg, activity) in candidates) {
            try {
                val intent = if (activity != null) {
                    Intent().setClassName(pkg, activity)
                } else {
                    ctx.packageManager.getLaunchIntentForPackage(pkg)
                } ?: continue
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                return true
            } catch (_: Throwable) {
                // try the next candidate
            }
        }
        return false
    }
}
