package com.cardpersonality.honor.ui.components

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import android.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardpersonality.honor.FaceGeometry
import com.cardpersonality.honor.FaceTypefaces
import com.cardpersonality.honor.data.CardFaceConfig
import com.cardpersonality.honor.data.StickerKind
import com.cardpersonality.honor.data.StickerSpec
import com.cardpersonality.honor.ui.util.Haptics
import com.cardpersonality.honor.ui.util.rememberFaceImageBitmap
import com.cardpersonality.honor.ui.util.rememberStickerBitmaps
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Wallet-accurate card-face preview.
 *
 * Geometry (cover-fit + zoom + pan), text sizes and image-sticker sizes use [FaceGeometry], the
 * exact same math as [com.cardpersonality.honor.FaceRenderer], so the bitmap served to the wallet
 * matches what the user sees here. The 12dp rounded clip is preview-only (the wallet clips too).
 */
@Composable
fun CardFacePreview(
    cfg: CardFaceConfig,
    modifier: Modifier = Modifier,
    selectedStickerIdState: State<String?> = rememberUpdatedState(null),
    interactive: Boolean = true,
    walletPackage: String? = null,
    cornerRadius: Dp? = null,
    /**
     * Wallet's current (unmodified) face snapshot shown as a dimmed backdrop while the user has
     * not picked a custom image yet. Purely visual; never part of the exported bitmap.
     */
    backgroundImagePath: String? = null,
    /** Auto-snap dragged stickers / the source image to canvas edges and center lines. */
    snapEnabled: Boolean = false,
    onCfgChange: (CardFaceConfig) -> Unit = {},
    onStickerSelected: (String?) -> Unit = {},
) {
    val faceSpec = FaceGeometry.specFor(walletPackage)
    val radius: Dp = cornerRadius ?: faceSpec.cornerRadiusDp.dp
    val image = rememberFaceImageBitmap(cfg.imagePath)
    val backgroundImage = rememberFaceImageBitmap(backgroundImagePath)
    val stickerBitmaps = rememberStickerBitmaps(cfg.stickers)
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current
    val colors = MiuixTheme.colorScheme
    val hapticContext = LocalContext.current

    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    // Guide lines currently shown while a snap is active (canvas-normalized coordinates).
    var guideX by remember { mutableStateOf<Float?>(null) }
    var guideY by remember { mutableStateOf<Float?>(null) }
    // Haptic is fired once per newly activated snap anchor (not continuously).
    var activeHapticAnchor by remember { mutableStateOf<String?>(null) }
    // Rotation gesture state: accumulate until the finger clearly rotates (anti-mistouch), then
    // snap to multiples of 90° with a single haptic tick per adhered angle.
    var rotationAccum by remember { mutableStateOf(0f) }
    var rotationUnlocked by remember { mutableStateOf(false) }
    var activeRotationAnchor by remember { mutableStateOf<String?>(null) }
    val latestSnap by rememberUpdatedState(snapEnabled)

    val latestCfg by rememberUpdatedState(cfg)
    val latestOnChange by rememberUpdatedState(onCfgChange)
    val latestOnSelect by rememberUpdatedState(onStickerSelected)

    val stickerBoxes: Map<String, Rect> = remember(
        cfg.stickers, sizePx, stickerBitmaps, density.density, density.fontScale, faceSpec,
    ) {
        if (sizePx.width <= 0) {
            emptyMap()
        } else {
            buildStickerBoxes(
                cfg.stickers,
                sizePx.width.toFloat(),
                sizePx.height.toFloat(),
                stickerBitmaps,
                textMeasurer,
                density.density,
                density.fontScale,
                faceSpec.designWidth,
            )
        }
    }
    val latestBoxes by rememberUpdatedState(stickerBoxes)

    // Content size (canvas px, no selection padding) per sticker, for snapping calculations.
    val stickerSizes: Map<String, Pair<Float, Float>> = remember(
        cfg.stickers, sizePx, stickerBitmaps, density.density, density.fontScale, faceSpec,
    ) {
        if (sizePx.width <= 0) {
            emptyMap()
        } else {
            cfg.stickers.associate { spec ->
                spec.id to stickerContentSize(
                    spec,
                    sizePx.width.toFloat(),
                    sizePx.height.toFloat(),
                    stickerBitmaps,
                    textMeasurer,
                    density.density,
                    density.fontScale,
                    faceSpec.designWidth,
                )
            }
        }
    }
    val latestSizes by rememberUpdatedState(stickerSizes)

    /** Fires the snap haptic once per newly activated anchor (not continuously). */
    fun noteAnchor(key: String?) {
        if (key == activeHapticAnchor) return
        activeHapticAnchor = key
        if (key != null) {
            Haptics.tick(hapticContext)
        }
    }

    /** After the finger lifts, finish the remaining small distance to the exact anchor. */
    fun finishSnap() {
        if (!latestSnap) return
        val W = sizePx.width.toFloat()
        val H = sizePx.height.toFloat()
        if (W <= 0f || H <= 0f) return
        val base = latestCfg
        val selectedId = selectedStickerIdState.value
        if (selectedId != null) {
            val idx = base.stickers.indexOfFirst { it.id == selectedId }
            if (idx < 0) return
            val spec = base.stickers[idx]
            val sizes = latestSizes[spec.id] ?: return
            val wN = sizes.first / W
            val hN = sizes.second / H
            var cx = spec.cx
            var cy = spec.cy
            listOf(wN / 2f, 0.5f, 1f - wN / 2f)
                .minByOrNull { abs(cx - it) }
                ?.takeIf { abs(cx - it) <= SNAP_FINAL_THRESHOLD }
                ?.let { cx = it }
            listOf(hN / 2f, 0.5f, 1f - hN / 2f)
                .minByOrNull { abs(cy - it) }
                ?.takeIf { abs(cy - it) <= SNAP_FINAL_THRESHOLD }
                ?.let { cy = it }
            if (cx != spec.cx || cy != spec.cy) {
                latestOnChange(
                    base.copy(
                        stickers = base.stickers.toMutableList().also {
                            it[idx] = spec.copy(cx = cx, cy = cy)
                        },
                    ),
                )
            }
        } else {
            var oX = base.offsetX
            var oY = base.offsetY
            if (abs(oX) <= SNAP_FINAL_THRESHOLD) oX = 0f
            if (abs(oY) <= SNAP_FINAL_THRESHOLD) oY = 0f
            if (oX != base.offsetX || oY != base.offsetY) {
                latestOnChange(base.copy(offsetX = oX, offsetY = oY))
            }
        }
    }

    val gestureModifier = if (interactive) {
        Modifier
            .pointerInput(image, cfg.stickers.size, snapEnabled) {
                detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, rotation ->
                    val base = latestCfg
                    val canvasW = size.width.toFloat()
                    val canvasH = size.height.toFloat()
                    val selectedId = selectedStickerIdState.value
                    if (selectedId != null) {
                        val index = base.stickers.indexOfFirst { it.id == selectedId }
                        if (index >= 0) {
                            val spec = base.stickers[index]
                            // Free placement: no cx/cy clamp for stickers — the canvas clips
                            // naturally, so a very tall image at 200% can be moved so that any
                            // part of it sits anywhere (e.g. cy -3 .. +3).
                            val rawCx = spec.cx + pan.x / size.width
                            val rawCy = spec.cy + pan.y / size.height
                            var newCx = if (rawCx.isFinite()) rawCx else spec.cx
                            var newCy = if (rawCy.isFinite()) rawCy else spec.cy
                            var snapGuideX: Float? = null
                            var snapGuideY: Float? = null
                            var anchorKey: String? = null
                            if (latestSnap) {
                                val sizes = latestSizes[spec.id]
                                val wPx = sizes?.first ?: 0f
                                val hPx = sizes?.second ?: 0f
                                if (wPx > 0f && canvasW > 0f) {
                                    val wN = wPx / canvasW
                                    val candidates = listOf(
                                        (wN / 2f) to 0f,       // left edge -> canvas left edge
                                        0.5f to 0.5f,           // center -> vertical center line
                                        (1f - wN / 2f) to 1f,   // right edge -> canvas right edge
                                    )
                                    val best = candidates.minByOrNull { abs(newCx - it.first) }
                                    if (best != null && abs(newCx - best.first) <= SNAP_ATTRACT_THRESHOLD) {
                                        // Soft magnet: only pull part of the way so the element keeps
                                        // following the finger and can still leave the anchor.
                                        newCx += (best.first - newCx) * SNAP_ATTRACT_FACTOR
                                        snapGuideX = best.second
                                        anchorKey = "x:${best.second}"
                                    }
                                }
                                if (hPx > 0f && canvasH > 0f) {
                                    val hN = hPx / canvasH
                                    val candidates = listOf(
                                        (hN / 2f) to 0f,
                                        0.5f to 0.5f,
                                        (1f - hN / 2f) to 1f,
                                    )
                                    val best = candidates.minByOrNull { abs(newCy - it.first) }
                                    if (best != null && abs(newCy - best.first) <= SNAP_ATTRACT_THRESHOLD) {
                                        newCy += (best.first - newCy) * SNAP_ATTRACT_FACTOR
                                        snapGuideY = best.second
                                        anchorKey = (anchorKey ?: "") + "y:${best.second}"
                                    }
                                }
                            }
                            noteAnchor(anchorKey)
                            guideX = snapGuideX
                            guideY = snapGuideY
                            // Anti-mistouch: a rotation gesture only unlocks after a clear turn
                            // (~8–10°); once unlocked the element rotates with the fingers and
                            // sticks to 0/±90/±180/±270/±360 within ~2°.
                            rotationAccum += rotation
                            var newRot = spec.rot
                            if (!rotationUnlocked) {
                                if (abs(rotationAccum) >= ROTATION_UNLOCK_THRESHOLD) {
                                    rotationUnlocked = true
                                    Haptics.tick(hapticContext)
                                }
                            } else {
                                newRot = spec.rot + rotation
                            }
                            val nearest90 = (newRot / 90f).roundToInt() * 90f
                            if (abs(newRot - nearest90) <= ROTATION_SNAP_THRESHOLD) {
                                newRot = nearest90
                                val key = "rot:${nearest90.toInt()}"
                                if (activeRotationAnchor != key) {
                                    activeRotationAnchor = key
                                    Haptics.tick(hapticContext)
                                }
                            } else {
                                activeRotationAnchor = null
                            }
                            newRot = FaceGeometry.clampRotation(newRot)
                            val updated = if (spec.kind == StickerKind.IMAGE) {
                                spec.copy(
                                    cx = newCx,
                                    cy = newCy,
                                    w = (spec.w * zoom).coerceIn(MIN_STICKER_W, MAX_STICKER_W),
                                    rot = newRot,
                                )
                            } else {
                                spec.copy(
                                    cx = newCx,
                                    cy = newCy,
                                    sizeDp = (spec.sizeDp * zoom).coerceIn(6f, 120f),
                                    rot = newRot,
                                )
                            }
                            latestOnChange(
                                base.copy(
                                    stickers = base.stickers.toMutableList().also { it[index] = updated },
                                ),
                            )
                        }
                    } else {
                        val source = image ?: return@detectTransformGestures
                        var updated = FaceGeometry.gestureTransform(
                            cfg = base,
                            imageWidth = source.width.toFloat(),
                            imageHeight = source.height.toFloat(),
                            canvasWidth = canvasW,
                            canvasHeight = canvasH,
                            panX = pan.x,
                            panY = pan.y,
                            zoom = zoom,
                            anchorX = centroid.x,
                            anchorY = centroid.y,
                        )
                        var snapGuideX: Float? = null
                        var snapGuideY: Float? = null
                        var anchorKey: String? = null
                        if (latestSnap) {
                            if (abs(updated.offsetX) <= SNAP_ATTRACT_THRESHOLD) {
                                updated = updated.copy(offsetX = updated.offsetX * (1f - SNAP_ATTRACT_FACTOR))
                                snapGuideX = 0.5f
                                anchorKey = "imgx"
                            }
                            if (abs(updated.offsetY) <= SNAP_ATTRACT_THRESHOLD) {
                                updated = updated.copy(offsetY = updated.offsetY * (1f - SNAP_ATTRACT_FACTOR))
                                snapGuideY = 0.5f
                                anchorKey = (anchorKey ?: "") + "imgy"
                            }
                        }
                        noteAnchor(anchorKey)
                        guideX = snapGuideX
                        guideY = snapGuideY
                        latestOnChange(updated)
                    }
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) {
                            // Finish a rotation snap on release when close to a 90° multiple.
                            val wasRotating = rotationUnlocked
                            if (wasRotating) {
                                val selected = selectedStickerIdState.value
                                if (selected != null) {
                                    val spec = latestCfg.stickers.firstOrNull { it.id == selected }
                                    if (spec != null) {
                                        val nearest90 = (spec.rot / 90f).roundToInt() * 90f
                                        if (abs(spec.rot - nearest90) <= ROTATION_RELEASE_SNAP &&
                                            abs(spec.rot - nearest90) > 0.01f
                                        ) {
                                            latestOnChange(
                                                latestCfg.copy(
                                                    stickers = latestCfg.stickers.map {
                                                        if (it.id == selected) {
                                                            it.copy(
                                                                rot = FaceGeometry.clampRotation(nearest90),
                                                            )
                                                        } else {
                                                            it
                                                        }
                                                    },
                                                ),
                                            )
                                            Haptics.tick(hapticContext)
                                        }
                                    }
                                }
                            }
                            guideX = null
                            guideY = null
                            activeHapticAnchor = null
                            rotationAccum = 0f
                            rotationUnlocked = false
                            activeRotationAnchor = null
                            finishSnap()
                        }
                    }
                }
            }
            .pointerInput(cfg.stickers, image) {
                detectTapGestures(
                    onDoubleTap = {
                        if (selectedStickerIdState.value != null) {
                            latestOnSelect(null)
                        } else {
                            latestOnChange(latestCfg.copy(scale = 1f, offsetX = 0f, offsetY = 0f))
                        }
                    },
                    onTap = { position ->
                        val tapStartNanos = System.nanoTime()
                        // Hit-test in the freshest sticker-list order (index 0 = top-most layer).
                        val order = latestCfg.stickers.map { it.id }
                        val hit = hitTest(latestBoxes, order, position)
                        // Diagnostics for z-order hit-testing: ordered ids (index 0 = top-most),
                        // the returned hit, and the boxes containing the tap point.
                        val ordered = order.filter { latestBoxes.containsKey(it) }
                        val containing = latestBoxes.entries
                            .filter { (_, rect) -> rect.contains(position) }
                            .map { (id, rect) ->
                                "$id@[${rect.left.toInt()},${rect.top.toInt()},${rect.right.toInt()},${rect.bottom.toInt()}]"
                            }
                        Log.d(
                            "CARD_PERSONALITY_HIT",
                            "tap=(${position.x.toInt()},${position.y.toInt()}) hit=$hit " +
                                "ordered=$ordered containing=$containing",
                        )
                        if (hit != null) {
                            latestOnSelect(hit)
                        } else if (selectedStickerIdState.value != null) {
                            latestOnSelect(null)
                        }
                        Log.d(
                            "CARD_PERSONALITY_PERF",
                            "tap handled in ${(System.nanoTime() - tapStartNanos) / 1_000_000}ms hit=$hit",
                        )
                        android.view.Choreographer.getInstance().postFrameCallback {
                            Log.d(
                                "CARD_PERSONALITY_PERF",
                                "tap->nextFrame +${(System.nanoTime() - tapStartNanos) / 1_000_000}ms hit=$hit",
                            )
                        }
                    },
                )
            }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .aspectRatio(faceSpec.aspect)
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(radius), clip = false)
            .clip(RoundedCornerShape(radius))
            .background(Color(0xFF16181D))
            .onSizeChanged { sizePx = it },
    ) {
        Canvas(modifier = Modifier.fillMaxSize().then(gestureModifier)) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF2B2F3A),
                        Color(0xFF16181D),
                        Color(0xFF0E0F12),
                    ),
                ),
            )
            if (image != null) {
                val placement = FaceGeometry.placement(
                    imageWidth = image.width.toFloat(),
                    imageHeight = image.height.toFloat(),
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    cfg = cfg,
                )
                val centerX = placement.dx + placement.destWidth / 2f
                val centerY = placement.dy + placement.destHeight / 2f
                withTransform({
                    if (cfg.flipX || cfg.flipY) {
                        // Mirror about the destination center, then apply cover placement.
                        translate(centerX, centerY)
                        scale(
                            if (cfg.flipX) -1f else 1f,
                            if (cfg.flipY) -1f else 1f,
                            Offset.Zero,
                        )
                        translate(-centerX, -centerY)
                    }
                    translate(placement.dx, placement.dy)
                    scale(placement.scale, placement.scale, Offset.Zero)
                }) {
                    drawImage(image)
                }
            } else if (backgroundImage != null) {
                // Wallet's current face as a dimmed, non-interactive backdrop.
                val placement = FaceGeometry.placement(
                    imageWidth = backgroundImage.width.toFloat(),
                    imageHeight = backgroundImage.height.toFloat(),
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    cfg = CardFaceConfig(),
                )
                withTransform({
                    translate(placement.dx, placement.dy)
                    scale(placement.scale, placement.scale, Offset.Zero)
                }) {
                    drawImage(backgroundImage, alpha = 0.65f)
                }
                drawRect(color = Color(0x33000000))
            }
            val designScale = size.width / faceSpec.designWidth
            // List index 0 is the TOP-most layer -> draw in reverse so 0 lands on top.
            for (spec in cfg.stickers.asReversed()) {
                if (!spec.visible) continue
                when (spec.kind) {
                    StickerKind.IMAGE -> drawImageSticker(spec, stickerBitmaps)
                    else -> drawTextSticker(spec, textMeasurer, density.density, density.fontScale, designScale)
                }
            }
        }
        // Selection chrome + snap guides live in their own Canvas that reads the selection State,
        // so changing the selection never redraws/recomposes the card content.
        SelectionOverlay(
            boxes = stickerBoxes,
            selectedState = selectedStickerIdState,
            guideX = guideX,
            guideY = guideY,
            color = colors.primary,
        )
    }
}

@Composable
private fun SelectionOverlay(
    boxes: Map<String, Rect>,
    selectedState: State<String?>,
    guideX: Float?,
    guideY: Float?,
    color: Color,
) {
    val selected = selectedState.value
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (selected != null) {
            val box = boxes[selected]
            if (box != null) {
                drawRect(
                    color = color,
                    topLeft = Offset(box.left - 4f, box.top - 4f),
                    size = androidx.compose.ui.geometry.Size(box.width + 8f, box.height + 8f),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                    ),
                )
            }
        }
        if (guideX != null) {
            drawLine(
                color = color,
                start = Offset(guideX * size.width, 0f),
                end = Offset(guideX * size.width, size.height),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            )
        }
        if (guideY != null) {
            drawLine(
                color = color,
                start = Offset(0f, guideY * size.height),
                end = Offset(size.width, guideY * size.height),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            )
        }
    }
}

// ------------------------------------------------------------------------------------- drawing

/** Rotation range is ±360° for both the slider and the pinch gesture. */
private const val MIN_STICKER_W = 0.05f
private const val MAX_STICKER_W = 2.0f
/** Degrees the fingers must turn before a two-finger rotate gesture activates (anti-mistouch). */
private const val ROTATION_UNLOCK_THRESHOLD = 9f
/** Snap the rotation to multiples of 90° when within this many degrees. */
private const val ROTATION_SNAP_THRESHOLD = 11f
/** On gesture release, finish the 90° snap when within this many degrees. */
private const val ROTATION_RELEASE_SNAP = 15f
private const val SNAP_ATTRACT_THRESHOLD = 0.012f
private const val SNAP_FINAL_THRESHOLD = 0.005f
private const val SNAP_ATTRACT_FACTOR = 0.20f

/**
 * Draws a text sticker with the SAME Android-Paint pipeline as [FaceRenderer] (native canvas,
 * CENTER-aligned text, outline pass then fill pass). Using the exporter's renderer here avoids the
 * Compose outlined-text rasterisation seam that used to leave a faint "ghost" outline in the
 * preview while the exported bitmap was clean.
 */
private fun DrawScope.drawTextSticker(
    spec: StickerSpec,
    textMeasurer: TextMeasurer,
    densityValue: Float,
    fontScale: Float,
    designScale: Float,
) {
    if (spec.text.isBlank()) return
    val stickerAlpha = spec.alpha.coerceIn(0f, 1f)
    val typeface = FaceTypefaces.resolve(
        customFontPath = spec.customFontPath,
        family = spec.fontFamily,
        weight = spec.fontWeight,
        italic = spec.italic,
    )
    val fontSize = spec.sizeDp.coerceIn(4f, 400f) * designScale
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = withStickerAlpha(if (spec.color == -1) 0xFFFFFFFF.toInt() else spec.color, stickerAlpha)
        textSize = fontSize
        this.typeface = typeface
        isUnderlineText = spec.underline
        if (spec.glow && spec.glowRadius > 0f) {
            setShadowLayer(
                spec.glowRadius * designScale,
                0f,
                0f,
                withStickerAlpha(spec.glowColor, stickerAlpha),
            )
        }
    }
    val strokePaint = if (spec.stroke && spec.strokeWidth > 0f) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = withStickerAlpha(spec.strokeColor, stickerAlpha)
            textSize = fontSize
            this.typeface = typeface
            style = Paint.Style.STROKE
            strokeWidth = spec.strokeWidth * designScale
            strokeJoin = Paint.Join.ROUND
            isUnderlineText = spec.underline
        }
    } else {
        null
    }
    val cx = spec.cx * size.width
    val cy = spec.cy * size.height
    val lines = spec.text.split('\n')
    val fm = fillPaint.fontMetrics
    val lineHeight = (fm.descent - fm.ascent) * 1.05f
    val totalHeight = lineHeight * lines.size
    var baseline = cy - totalHeight / 2f - fm.ascent
    val native = drawContext.canvas.nativeCanvas
    native.save()
    native.rotate(FaceGeometry.clampRotation(spec.rot), cx, cy)
    for (line in lines) {
        // Outline first, then fill on top: the fill covers the inner half of the stroke so only
        // the outer edge remains visible (identical to the exporter).
        if (strokePaint != null) native.drawText(line, cx, baseline, strokePaint)
        native.drawText(line, cx, baseline, fillPaint)
        baseline += lineHeight
    }
    native.restore()
}

/** Multiplies a packed ARGB color's alpha by [alpha] (0..1). */
private fun withStickerAlpha(color: Int, alpha: Float): Int {
    val base = android.graphics.Color.alpha(color)
    val newAlpha = (base * alpha).roundToInt().coerceIn(0, 255)
    return (color and 0x00FFFFFF) or (newAlpha shl 24)
}

private fun DrawScope.drawImageSticker(spec: StickerSpec, bitmaps: Map<String, ImageBitmap>) {
    val bitmap = bitmaps[spec.id] ?: return
    if (bitmap.width <= 0) return
    val dstW = (spec.w.coerceIn(0.05f, 2f) * size.width).coerceAtLeast(8f)
    val dstH = dstW * bitmap.height / bitmap.width
    val centerX = spec.cx * size.width
    val centerY = spec.cy * size.height
    withTransform({
        translate(centerX - dstW / 2f, centerY - dstH / 2f)
        rotate(FaceGeometry.clampRotation(spec.rot), Offset(dstW / 2f, dstH / 2f))
        if (spec.flipX || spec.flipY) {
            scale(
                if (spec.flipX) -1f else 1f,
                if (spec.flipY) -1f else 1f,
                Offset(dstW / 2f, dstH / 2f),
            )
        }
        scale(dstW / bitmap.width, dstH / bitmap.height, Offset.Zero)
    }) {
        drawImage(bitmap, alpha = spec.alpha.coerceIn(0f, 1f))
    }
}

private fun stickerTextStyle(
    spec: StickerSpec,
    canvasWidth: Float,
    densityValue: Float,
    fontScale: Float,
    designWidth: Float,
): TextStyle {
    val fontSizePx = spec.sizeDp.coerceIn(4f, 400f) * (canvasWidth / designWidth)
    val fontSizeSp = fontSizePx / (densityValue * fontScale).coerceAtLeast(0.01f)
    val typeface = FaceTypefaces.resolve(
        customFontPath = spec.customFontPath,
        family = spec.fontFamily,
        weight = spec.fontWeight,
        italic = spec.italic,
    )
    return TextStyle(
        color = if (spec.color == -1) Color.White else Color(spec.color),
        fontSize = fontSizeSp.sp,
        fontFamily = FontFamily(typeface),
        textDecoration = if (spec.underline) TextDecoration.Underline else null,
        lineHeight = (fontSizeSp * 1.12f).sp,
    )
}

private fun measureStickerText(
    spec: StickerSpec,
    textMeasurer: TextMeasurer,
    canvasWidth: Float,
    densityValue: Float,
    fontScale: Float,
    designWidth: Float,
): TextLayoutResult {
    return textMeasurer.measure(
        text = AnnotatedString(spec.text),
        style = stickerTextStyle(spec, canvasWidth, densityValue, fontScale, designWidth),
    )
}

private fun stickerContentSize(
    spec: StickerSpec,
    canvasWidth: Float,
    canvasHeight: Float,
    bitmaps: Map<String, ImageBitmap>,
    textMeasurer: TextMeasurer,
    densityValue: Float,
    fontScale: Float,
    designWidth: Float,
): Pair<Float, Float> {
    return when (spec.kind) {
        StickerKind.IMAGE -> {
            val bitmap = bitmaps[spec.id]
            val width = spec.w.coerceIn(0.05f, 2f) * canvasWidth
            val height = if (bitmap != null && bitmap.width > 0) {
                width * bitmap.height / bitmap.width
            } else {
                width * 0.6f
            }
            width to height
        }
        else -> {
            if (spec.text.isBlank()) {
                0f to 0f
            } else {
                val layout = measureStickerText(spec, textMeasurer, canvasWidth, densityValue, fontScale, designWidth)
                layout.size.width.toFloat() to layout.size.height.toFloat()
            }
        }
    }
}

private fun buildStickerBoxes(
    stickers: List<StickerSpec>,
    canvasWidth: Float,
    canvasHeight: Float,
    bitmaps: Map<String, ImageBitmap>,
    textMeasurer: TextMeasurer,
    densityValue: Float,
    fontScale: Float,
    designWidth: Float,
): Map<String, Rect> {
    // LinkedHashMap preserves sticker-list order: index 0 = TOP-most layer.
    val out = LinkedHashMap<String, Rect>(stickers.size)
    for (spec in stickers) {
        if (!spec.visible) continue
        val centerX = spec.cx * canvasWidth
        val centerY = spec.cy * canvasHeight
        val (w, h) = stickerContentSize(
            spec, canvasWidth, canvasHeight, bitmaps, textMeasurer, densityValue, fontScale, designWidth,
        )
        val pad = 6f
        out[spec.id] = Rect(
            centerX - w / 2f - pad,
            centerY - h / 2f - pad,
            centerX + w / 2f + pad,
            centerY + h / 2f + pad,
        )
    }
    return out
}

private fun hitTest(boxes: Map<String, Rect>, orderedIds: List<String>, position: Offset): String? {
    // Walk the freshest sticker order (index 0 = top-most) so the TOP element wins the hit.
    for (id in orderedIds) {
        val rect = boxes[id] ?: continue
        if (rect.contains(position)) return id
    }
    // Fallback: any box not present in the order list (should not happen).
    for ((id, rect) in boxes) {
        if (rect.contains(position)) return id
    }
    return null
}

/** Small helper used by editors: whether two configs are visually identical. */
fun CardFaceConfig.sameVisual(other: CardFaceConfig): Boolean {
    if (imagePath != other.imagePath) return false
    if (abs(scale - other.scale) > 0.0001f) return false
    if (abs(offsetX - other.offsetX) > 0.0001f) return false
    if (abs(offsetY - other.offsetY) > 0.0001f) return false
    if (flipX != other.flipX || flipY != other.flipY) return false
    if (hiddenAppElements != other.hiddenAppElements) return false
    if (stickers.size != other.stickers.size) return false
    return stickers.zip(other.stickers).all { (a, b) ->
        a.id == b.id && a.kind == b.kind && a.text == b.text && a.color == b.color &&
            abs(a.sizeDp - b.sizeDp) < 0.0001f && a.imagePath == b.imagePath &&
            abs(a.cx - b.cx) < 0.0001f && abs(a.cy - b.cy) < 0.0001f &&
            abs(a.w - b.w) < 0.0001f && abs(a.rot - b.rot) < 0.0001f &&
            a.visible == b.visible &&
            a.fontWeight == b.fontWeight && a.italic == b.italic && a.underline == b.underline &&
            a.glow == b.glow && a.glowColor == b.glowColor &&
            abs(a.glowRadius - b.glowRadius) < 0.0001f &&
            a.stroke == b.stroke && a.strokeColor == b.strokeColor &&
            abs(a.strokeWidth - b.strokeWidth) < 0.0001f &&
            a.fontFamily == b.fontFamily && a.customFontPath == b.customFontPath &&
            a.flipX == b.flipX && a.flipY == b.flipY &&
            abs(a.alpha - b.alpha) < 0.0001f && a.name == b.name
    }
}

/** Compact preview for list rows (no gestures). */
@Composable
fun CardFaceThumbnail(
    cfg: CardFaceConfig,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    walletPackage: String? = null,
) {
    CardFacePreview(
        cfg = cfg,
        modifier = modifier,
        interactive = false,
        walletPackage = walletPackage,
        cornerRadius = cornerRadius,
    )
}

/** Placeholder used when a card has no custom face yet. */
@Composable
fun EmptyFacePlaceholder(
    modifier: Modifier = Modifier,
    walletPackage: String? = null,
) {
    val spec = FaceGeometry.specFor(walletPackage)
    Box(
        modifier = modifier
            .aspectRatio(spec.aspect)
            .clip(RoundedCornerShape(spec.cornerRadiusDp.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF343A46), Color(0xFF1C1F26)),
                ),
            ),
    )
}
















