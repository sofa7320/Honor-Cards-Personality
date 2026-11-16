package com.cardpersonality.honor.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Round-11 diagnostics journal: a thread-safe ring of the last [MAX_ENTRIES] operations persisted in
 * `files/oplog.txt` as `时间|来源|操作|详情` lines. Used by the panel's About/diagnostic report and by
 * support when a user reports "wallet not detected" / "face not applied".
 *
 * The panel process writes directly; the wallet process (hook side) sends entries through the
 * FaceProvider `"oplog"` call (see ProviderClient.logOp) so there is a single journal file.
 */
object OpLog {
    const val FILE_NAME = "oplog.txt"
    const val MAX_ENTRIES = 300

    private const val TAG = "CardPersonality"
    private const val MAX_DETAIL = 400

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    /** Appends one journal entry (keeps at most [MAX_ENTRIES] lines). Never throws. */
    fun record(ctx: Context?, source: String, action: String, detail: String = "") {
        val context = ctx ?: return
        val line = buildLine(source, action, detail)
        synchronized(lock) {
            try {
                val existing = try {
                    val f = file(context)
                    if (f.isFile) f.readLines(Charsets.UTF_8) else emptyList()
                } catch (_: Throwable) {
                    emptyList()
                }
                writeAtomic(context, (existing + line).takeLast(MAX_ENTRIES))
            } catch (t: Throwable) {
                Log.w(TAG, "oplog write failed", t)
            }
        }
    }

    /** Newest entries (oldest first) for the About page / diagnostic report. Never throws. */
    fun read(ctx: Context, limit: Int = MAX_ENTRIES): List<String> {
        return synchronized(lock) {
            try {
                val f = file(ctx)
                if (f.isFile) f.readLines(Charsets.UTF_8).takeLast(limit) else emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    /** Whole journal as one string (UI diagnostic copy/share). Never throws. */
    fun report(ctx: Context): String = read(ctx).joinToString("\n")

    /** Where the journal lives, for UI "open/export log" affordances. */
    fun path(ctx: Context): String = file(ctx).absolutePath

    private fun buildLine(source: String, action: String, detail: String): String {
        return "${timeFormat.format(Date())}|${sanitize(source)}|${sanitize(action)}|${sanitize(detail)}"
    }

    private fun sanitize(value: String): String =
        value.replace('|', '/').replace('\n', ' ').replace('\r', ' ').take(MAX_DETAIL)

    private fun writeAtomic(ctx: Context, lines: List<String>) {
        val target = file(ctx)
        target.parentFile?.mkdirs()
        val tmp = File(target.absolutePath + ".tmp")
        tmp.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
