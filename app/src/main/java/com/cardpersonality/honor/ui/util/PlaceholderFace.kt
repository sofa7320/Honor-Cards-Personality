package com.cardpersonality.honor.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Round 32: neutral 336:208 placeholder face used while the wallet has not yet rendered a card face
 * (e.g. an access card right after a cold start). It lets the user edit/save the card immediately
 * instead of being blocked by "wallet has not rendered this card yet".
 */
object PlaceholderFace {

    private const val WIDTH = 336
    private const val HEIGHT = 208

    fun ensure(
        ctx: Context,
        type: String,
        id: String,
        title: String,
        subtitle: String = "药做卡面",
    ): File? = try {
        val dir = File(ctx.filesDir, "placeholders").apply { mkdirs() }
        val sha = sha256Hex("$type:$id")
        val target = File(dir, "$sha.png")
        if (target.isFile && target.length() > 0L) {
            target
        } else {
            val bitmap = render(title, subtitle)
            val tmp = File(dir, "$sha.png.tmp")
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            bitmap.recycle()
            tmp.renameTo(target) || run {
                target.delete()
                tmp.renameTo(target)
            }
            target.takeIf { it.isFile }
        }
    } catch (_: Throwable) {
        null
    }

    private fun render(title: String, subtitle: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
                intArrayOf(0xFF2B2F3A.toInt(), 0xFF16181D.toInt(), 0xFF0E0F12.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bg)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
        canvas.drawRect(0f, HEIGHT * 0.68f, WIDTH.toFloat(), HEIGHT * 0.68f + 1f, line)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(title.take(12), WIDTH / 2f, HEIGHT * 0.44f, titlePaint)
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99FFFFFF.toInt()
            textSize = 20f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(subtitle, WIDTH / 2f, HEIGHT * 0.60f, subPaint)
        return bitmap
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
