package com.cardpersonality.honor.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.State
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import top.yukonga.miuix.kmp.icon.extended.Import
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cardpersonality.honor.FaceImageLoader
import com.cardpersonality.honor.FaceGeometry
import com.cardpersonality.honor.FaceTypefaces
import com.cardpersonality.honor.data.CardFaceConfig
import com.cardpersonality.honor.data.CardKey
import com.cardpersonality.honor.data.CardTypes
import com.cardpersonality.honor.data.FaceRepository
import com.cardpersonality.honor.data.StickerKind
import com.cardpersonality.honor.data.StickerSpec
import com.cardpersonality.honor.ui.components.CardFacePreview
import com.cardpersonality.honor.ui.components.EditorIcons
import com.cardpersonality.honor.ui.components.PanelDialog
import com.cardpersonality.honor.ui.components.sameVisual
import com.cardpersonality.honor.ui.util.EditorPrefs
import com.cardpersonality.honor.ui.util.Haptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Image as ImageIcon
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.RotateLeft
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the editor edits: one detected card, or the per-type default. */
data class EditorTarget(
    val key: CardKey,
    val title: String,
    val subtitle: String = "",
    val appElementIds: List<String> = emptyList(),
    /** Wallet package this card was detected in (null = infer from installed wallets). */
    val walletPackage: String? = null,
    /** Wallet's original face URL/path for this card (round 23: OPPO bridge URL fallback). */
    val faceUrl: String? = null,
)

/** Which colour of which sticker the colour picker dialog is editing. */
private data class ColorPickerRequest(val stickerId: String, val target: String) {
    val label: String
        get() = when (target) {
            "glow" -> "发光颜色"
            "stroke" -> "描边颜色"
            else -> "文字颜色"
        }
}

private val STICKER_COLORS = listOf(
    0xFFFFFFFF.toInt(),
    0xFF000000.toInt(),
    0xFF3482FF.toInt(),
    0xFFE94634.toInt(),
    0xFF34C759.toInt(),
    0xFFFFCC00.toInt(),
    0xFFFF8A00.toInt(),
    0xFFE6E6E6.toInt(),
)

/** 24 preset colours for the custom colour dialog (includes STICKER_COLORS). */
private val PRESET_COLORS = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF8E8E93.toInt(), 0xFFC7C7CC.toInt(),
    0xFF3482FF.toInt(), 0xFF0A84FF.toInt(), 0xFF30B0C7.toInt(), 0xFF32ADE6.toInt(),
    0xFF34C759.toInt(), 0xFF30D158.toInt(), 0xFFA8E05F.toInt(), 0xFF66D4CF.toInt(),
    0xFFFFCC00.toInt(), 0xFFFFD60A.toInt(), 0xFFFF9F0A.toInt(), 0xFFFF8A00.toInt(),
    0xFFE94634.toInt(), 0xFFFF453A.toInt(), 0xFFFF375F.toInt(), 0xFFFF2D55.toInt(),
    0xFFAF52DE.toInt(), 0xFFBF5AF2.toInt(), 0xFF5E5CE6.toInt(), 0xFF64D2FF.toInt(),
)

private fun colorToHex(color: Int): String =
    if (android.graphics.Color.alpha(color) == 255) {
        String.format("#%06X", color and 0xFFFFFF)
    } else {
        String.format("#%08X", color)
    }

/**
 * Normalizes colour input so Chinese IMEs / pasted text also work:
 * full-width ＃／０-９／Ａ-Ｆ／ａ-ｆ are folded to ASCII, whitespace (incl. 全角空格) is removed.
 */
private fun normalizeHexInput(text: String): String {
    val out = StringBuilder(text.length)
    for (ch in text) {
        when {
            ch == '#' || ch == '＃' || ch == '﹟' -> out.append('#')
            ch in '０'..'９' -> out.append('0' + (ch - '０'))
            ch in 'Ａ'..'Ｆ' -> out.append('A' + (ch - 'Ａ'))
            ch in 'ａ'..'ｆ' -> out.append('a' + (ch - 'ａ'))
            ch == '　' || ch.isWhitespace() -> Unit // drop spaces / full-width space
            else -> out.append(ch)
        }
    }
    return out.toString()
}

private fun parseHexColor(text: String): Int? {
    var hex = normalizeHexInput(text).trim()
    hex = hex.removePrefix("#").removePrefix("0x").removePrefix("0X")
    // 3-digit shorthand (#RGB -> #RRGGBB)
    if (hex.length == 3) {
        hex = buildString {
            for (ch in hex) {
                append(ch)
                append(ch)
            }
        }
    }
    if (hex.length != 6 && hex.length != 8) return null
    if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    return try {
        if (hex.length == 6) (0xFF000000L or hex.toLong(16)).toInt() else hex.toLong(16).toInt()
    } catch (_: Throwable) {
        null
    }
}

/** Card-face editor with wallet-accurate preview, image crop, user elements and app elements. */
@Composable
fun EditorScreen(
    ctx: Context,
    target: EditorTarget,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val initial = remember(target.key) {
        FaceRepository.loadCard(ctx, target.key, target.walletPackage) ?: CardFaceConfig()
    }
    var cfg by remember(target.key) { mutableStateOf(initial) }
    var baseline by remember(target.key) { mutableStateOf(initial) }
    // Selection is kept in a State object that ONLY the preview/Elements wrappers read, so a tap
    // on a sticker does not recompose the whole editor (image tab, dialogs, etc.).
    val selectedSticker = remember { mutableStateOf<String?>(null) }
    var hasStoredCustomFace by remember(target.key) {
        mutableStateOf(FaceRepository.hasCustomFace(ctx, target.key.type, target.key.id, target.walletPackage))
    }
    var tab by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var showAddText by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var imageImporting by remember { mutableStateOf(false) }
    var originalFacePath by remember(target.key) { mutableStateOf<String?>(null) }
    var faceUrlFallbackFailed by remember(target.key) { mutableStateOf(false) }
    var snapEnabled by remember { mutableStateOf(EditorPrefs.isSnapEnabled(ctx)) }
    // Round 25: export quality (1x..10x) persisted globally and applied to all renders/exports.
    var exportScale by remember {
        mutableStateOf(EditorPrefs.getExportScale(ctx).takeIf { it in 1..10 } ?: 4)
    }
    LaunchedEffect(exportScale) {
        com.cardpersonality.honor.FaceRenderer.setExportScale(exportScale)
        EditorPrefs.setExportScale(ctx, exportScale)
    }
    var showFontPicker by remember { mutableStateOf(false) }
    var fontImporting by remember { mutableStateOf(false) }
    var exportZipRunning by remember { mutableStateOf(false) }
    var importZipRunning by remember { mutableStateOf(false) }
    var renameStickerId by remember { mutableStateOf<String?>(null) }
    var colorPickerRequest by remember { mutableStateOf<ColorPickerRequest?>(null) }
    var tabAnimInitialised by remember { mutableStateOf(false) }

    // Round 23: wallet-native custom faces (e.g. Honor access cards) must show up in the panel
    // immediately. On editor open, invalidate + re-capture the wallet-original snapshot for cards
    // the panel has not customized (2 s debounce lives in OriginalSnapshotRefresh).
    LaunchedEffect(target.key, target.walletPackage) {
        if (!hasStoredCustomFace) {
            withContext(Dispatchers.IO) {
                runCatching {
                    com.cardpersonality.honor.ui.util.OriginalSnapshotRefresh.refreshIfNeeded(
                        ctx, target.key.type, target.key.id,
                    )
                }
            }
        }
    }

    /** Persist the auto-snap toggle (app-level setting, not part of the card config). */
    fun setSnapEnabled(value: Boolean) {
        if (snapEnabled == value) return
        snapEnabled = value
        EditorPrefs.setSnapEnabled(ctx, value)
    }

    /** Applies [block] to the sticker with [id] inside the working config. */
    fun updateSticker(id: String, block: (StickerSpec) -> StickerSpec) {
        cfg = cfg.copy(stickers = cfg.stickers.map { if (it.id == id) block(it) else it })
    }

    // Fade-only tab transition (no translation of the scroll container) with per-tab scroll state.
    val tabEnter = remember { Animatable(1f) }
    val tabScrolls = remember { mutableStateMapOf<Int, ScrollState>() }
    val contentScroll = tabScrolls.getOrPut(tab) { ScrollState(0) }
    LaunchedEffect(tab) {
        if (!tabAnimInitialised) {
            tabAnimInitialised = true
            return@LaunchedEffect
        }
        tabEnter.snapTo(0f)
        tabEnter.animateTo(1f, tween(durationMillis = 130))
    }

    /**
     * Ensures the editor has a base image (when the card is still showing the wallet's current face,
     * that snapshot becomes the base) and then runs [action]. Used when adding new elements.
     */
    fun ensureBaseImageThen(action: (String?) -> Unit) {
        if (cfg.imagePath != null) {
            action(null)
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { FaceRepository.useOriginalAsSource(ctx, target.key.type, target.key.id) }
                    .getOrElse { t ->
                        FaceRepository.ImportResult(
                            null,
                            t.javaClass.simpleName + (t.message?.let { "：" + it.take(80) } ?: ""),
                        )
                    }
            }
            if (result.ok) {
                cfg = cfg.copy(
                    imagePath = result.path,
                    scale = 1f,
                    offsetX = 0f,
                    offsetY = 0f,
                    flipX = false,
                    flipY = false,
                )
                action(null)
            } else {
                Toast.makeText(
                    ctx,
                    "无法获取钱包当前卡面：" + (result.error ?: "未知错误"),
                    Toast.LENGTH_LONG,
                ).show()
                action(result.error ?: "未知错误")
            }
        }
    }

    val dirty = !cfg.sameVisual(baseline)

    fun save(thenExit: Boolean = true) {
        if (saving) return
        saving = true
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { FaceRepository.saveCard(ctx, target.key, cfg, target.walletPackage) }
                // Round 23: explicit OPPO URL-fallback PUT using the card's reported faceUrl.
                runCatching {
                    com.cardpersonality.honor.ui.util.OppoFaceUrlPush.push(
                        ctx, target.key, target.walletPackage, target.faceUrl,
                    )
                }
            }
            saving = false
            showExitDialog = false
            Toast.makeText(ctx, "已保存，切换回钱包即可生效", Toast.LENGTH_SHORT).show()
            if (thenExit) onDone()
        }
    }

    // Wallet's current (unmodified) face for this card, used as a dimmed backdrop and as the
    // "以当前卡面为底图" source when no custom image is configured.
    LaunchedEffect(target.key, target.walletPackage, target.faceUrl) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val card = FaceRepository.cards(ctx)
                    .firstOrNull { it.key.fileKey == target.key.fileKey }
                val existing = if (card != null) {
                    FaceRepository.ensureOriginalFace(ctx, card)
                } else {
                    FaceRepository.originalSnapshotFile(ctx, target.key.type, target.key.id)
                }
                if (existing != null) {
                    existing.absolutePath to false
                } else {
                    // Round 24: wallet-native custom faces (e.g. access card cardPicUrl) may be
                    // reported before the module could capture a local snapshot. Download the
                    // URL directly for the editor background/preview.
                    val downloaded =
                        com.cardpersonality.honor.ui.util.FaceUrlFallback.ensureDownloaded(
                            ctx, target.key.type, target.key.id, target.faceUrl,
                        )
                    (downloaded?.absolutePath) to
                        (downloaded == null && !target.faceUrl.isNullOrBlank())
                }
            }.getOrElse { null to false }
        }
        originalFacePath = loaded.first
        faceUrlFallbackFailed = loaded.second
    }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            imageImporting = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    FaceRepository.importSourceImageOutcome(ctx, uri)
                }
                imageImporting = false
                if (result.ok) {
                    cfg = cfg.copy(
                        imagePath = result.path,
                        scale = 1f,
                        offsetX = 0f,
                        offsetY = 0f,
                    )
                } else {
                    Toast.makeText(
                        ctx,
                        "图片导入失败：" + (result.error ?: "未知错误"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
    val stickerPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    FaceRepository.importStickerImageOutcome(ctx, uri)
                }
                val path = result.path
                if (path != null) {
                    ensureBaseImageThen { baseError ->
                        if (baseError == null) {
                            val spec = StickerSpec(
                                id = newStickerId(),
                                kind = StickerKind.IMAGE,
                                imagePath = path,
                                cx = 0.5f,
                                cy = 0.5f,
                                w = 0.5f,
                            )
                            cfg = cfg.copy(stickers = listOf(spec) + cfg.stickers)
                            selectedSticker.value = spec.id
                        }
                    }
                } else {
                    Toast.makeText(
                        ctx,
                        "贴图导入失败：" + (result.error ?: "未知错误"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    fun pickSource() = sourcePicker.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
    )

    fun pickSticker() = stickerPicker.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
    )

    // Custom font import (.ttf/.otf) for the currently selected text sticker.
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val stickerId = selectedSticker.value
            fontImporting = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    FaceRepository.importFontOutcome(ctx, uri)
                }
                fontImporting = false
                val path = result.path
                if (path != null && stickerId != null) {
                    updateSticker(stickerId) {
                        it.copy(fontFamily = "custom", customFontPath = path)
                    }
                    showFontPicker = false
                    Toast.makeText(ctx, "字体已导入", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        ctx,
                        "字体导入失败：" + (result.error ?: "未知错误"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    fun openFontImporter() {
        runCatching {
            fontPicker.launch(arrayOf("font/*", "application/octet-stream", "*/*"))
        }.onFailure {
            Toast.makeText(ctx, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    /** Exports the current card customization as a ZIP into Download/honor_cards_personality. */
    fun exportCurrentCardZip() {
        if (exportZipRunning) return
        exportZipRunning = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    FaceRepository.exportCardZip(ctx, target.key, cfg, target.title)
                }.getOrElse { t ->
                    FaceRepository.ImportResult(
                        null,
                        t.javaClass.simpleName + (t.message?.let { "：" + it.take(120) } ?: ""),
                    )
                }
            }
            exportZipRunning = false
            if (result.ok) {
                Toast.makeText(ctx, "已导出：" + result.path, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(ctx, "导出失败：" + (result.error ?: "未知错误"), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ZIP import: replaces the current card configuration with an exported card package.
    val zipImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importZipRunning) {
            importZipRunning = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    FaceRepository.importCardZip(ctx, uri)
                }
                importZipRunning = false
                val imported = result.config
                if (imported != null) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            FaceRepository.saveCard(ctx, target.key, imported, target.walletPackage)
                            com.cardpersonality.honor.ui.util.OppoFaceUrlPush.push(
                                ctx, target.key, target.walletPackage, target.faceUrl,
                            )
                            FaceRepository.notifyWalletConfigChanged(ctx)
                        }
                    }
                    cfg = imported
                    baseline = imported
                    hasStoredCustomFace = true
                    selectedSticker.value = null
                    Toast.makeText(ctx, "已导入卡面并保存", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        ctx,
                        "导入失败：" + (result.error ?: "未知错误"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    fun pickCardZip() {
        runCatching {
            zipImportPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }.onFailure {
            Toast.makeText(ctx, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    fun useCurrentFaceAsSource() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                FaceRepository.useOriginalAsSource(ctx, target.key.type, target.key.id)
            }
            if (result.ok) {
                cfg = cfg.copy(
                    imagePath = result.path,
                    scale = 1f,
                    offsetX = 0f,
                    offsetY = 0f,
                )
                Toast.makeText(ctx, "已将当前卡面设为底图", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    ctx,
                    "无法使用当前卡面：" + (result.error ?: "未知错误"),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    BackHandler {
        if (dirty) showExitDialog = true else onDone()
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = target.title,
                subtitle = target.subtitle,
                navigationIcon = {
                    IconButton(onClick = { if (dirty) showExitDialog = true else onDone() }) {
                        Icon(MiuixIcons.Close, contentDescription = "取消")
                    }
                },
                actions = {
                    TextButton(
                        text = if (saving) "保存中…" else "保存",
                        onClick = { save() },
                        enabled = dirty && !saving,
                        colors = if (dirty && !saving) {
                            ButtonDefaults.textButtonColorsPrimary()
                        } else {
                            ButtonDefaults.textButtonColors()
                        },
                    )
                },
            )
        },
    ) { innerPadding ->
        val previewImage = com.cardpersonality.honor.ui.util.rememberFaceImageBitmap(cfg.imagePath)
        val imageMissing = cfg.imagePath != null && previewImage == null
        val hasCurrentFace = originalFacePath != null
        Column(modifier = Modifier.fillMaxSize()) {
            // Pinned (non-scrolling) preview header: only the tab bar + tab content scroll below.
            Column(modifier = Modifier.padding(top = innerPadding.calculateTopPadding())) {
                SmallTitle("卡面预览")
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PreviewWithSelection(
                        selectedState = selectedSticker,
                        cfg = cfg,
                        walletPackage = target.walletPackage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 420.dp),
                        backgroundImagePath = if (cfg.imagePath == null) originalFacePath else null,
                        snapEnabled = snapEnabled,
                        onCfgChange = { updated ->
                            cfg = updated
                            val current = selectedSticker.value
                            if (current != null && cfg.stickers.none { it.id == current }) {
                                selectedSticker.value = null
                            }
                        },
                        onStickerSelected = { selectedSticker.value = it },
                    )
                    if (cfg.imagePath == null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                MiuixIcons.Photos,
                                contentDescription = null,
                                tint = Color(0x99FFFFFF),
                                modifier = Modifier.size(30.dp),
                            )
                            Text(
                                text = when {
                                    hasCurrentFace -> "钱包当前卡面"
                                    faceUrlFallbackFailed -> "钱包尚未渲染该卡，请先打开钱包卡包页"
                                    else -> "请先打开钱包卡包页让钱包渲染一次该卡"
                                },
                                style = MiuixTheme.textStyles.body1,
                                color = Color(0xCCFFFFFF),
                            )
                            Text(
                                text = if (hasCurrentFace) {
                                    "以当前卡面为底图，或在下方选择新图片"
                                } else if (faceUrlFallbackFailed) {
                                    "打开钱包卡包页/详情页渲染该卡后返回本页刷新，或在下方「图片」中选择一张图片"
                                } else {
                                    "在下方「图片」中选择图片；若卡片由钱包原生自定义，请先打开钱包卡包页渲染一次"
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = Color(0x99FFFFFF),
                            )
                        }
                        if (hasCurrentFace) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 6.dp),
                            ) {
                                Button(
                                    onClick = { useCurrentFaceAsSource() },
                                    colors = ButtonDefaults.buttonColorsPrimary(),
                                ) {
                                    Text("以当前卡面为底图")
                                }
                            }
                        }
                    } else if (imageMissing) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "卡面图片不可用",
                                style = MiuixTheme.textStyles.body1,
                                color = Color(0xCCFFFFFF),
                            )
                            Text(
                                text = "请在下方「图片」中重新选择",
                                style = MiuixTheme.textStyles.footnote1,
                                color = Color(0x99FFFFFF),
                            )
                        }
                    }
                }
                val faceSpec = FaceGeometry.specFor(target.walletPackage)
                Text(
                    text = "圆角 ${faceSpec.cornerRadiusDp.toInt()}dp · 比例 " +
                        "${faceSpec.designWidth.toInt()} : ${faceSpec.designHeight.toInt()} · 导出 " +
                        "${faceSpec.designWidth.toInt() * exportScale} × " +
                        "${faceSpec.designHeight.toInt() * exportScale}（${exportScale}x）",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(contentScroll)
                    .padding(bottom = innerPadding.calculateBottomPadding() + 28.dp),
            ) {
                TabRow(
                    tabs = listOf("图片", "元素", "组件", "输出"),
                    selectedTabIndex = tab,
                    onTabSelected = { tab = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
                // Column (NOT Box): tab pages emit several top-level rows; a Box would stack all
                // of them on top of each other and clip the lazy item's height.
                // No explicit background here: the tab bar + content must sit directly on the page
                // background (the dark Miuix "background" color is lighter than the page black and
                // showed up as a stray gray rectangle under the preview).
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = tabEnter.value
                        },
                ) {
                    when (tab) {
                        0 -> ImageTab(
                            ctx = ctx,
                            cfg = cfg,
                            imageImporting = imageImporting,
                            hasCurrentFace = originalFacePath != null,
                            hasStoredCustomFace = hasStoredCustomFace,
                            snapEnabled = snapEnabled,
                            onSnapChange = ::setSnapEnabled,
                            exportZipRunning = exportZipRunning,
                            importZipRunning = importZipRunning,
                            onPickImage = ::pickSource,
                            onUseCurrentFace = { useCurrentFaceAsSource() },
                            onExportZip = { exportCurrentCardZip() },
                            onImportZip = { pickCardZip() },
                            onUseSample = {
                            if (!imageImporting) {
                                imageImporting = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        FaceRepository.importSampleImageOutcome(ctx)
                                    }
                                    imageImporting = false
                                    if (result.ok) {
                                        cfg = cfg.copy(
                                            imagePath = result.path,
                                            scale = 1f,
                                            offsetX = 0f,
                                            offsetY = 0f,
                                        )
                                    } else {
                                        Toast.makeText(
                                            ctx,
                                            "示例图片载入失败：" + (result.error ?: "未知错误"),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            }
                        },
                        onCfgChange = { cfg = it },
                    )
                    1 -> SelectedElementsTab(
                        selectedState = selectedSticker,
                        cfg = cfg,
                        snapEnabled = snapEnabled,
                        onSnapChange = ::setSnapEnabled,
                        onCfgChange = { cfg = it },
                        onSelect = { selectedSticker.value = it },
                        onAddText = { showAddText = true },
                        onAddImage = ::pickSticker,
                        onOpenFontPicker = { showFontPicker = true },
                        onOpenCustomColor = { spec, target ->
                            colorPickerRequest = ColorPickerRequest(spec.id, target)
                        },
                        onRequestRename = { renameStickerId = it },
                    )
                    3 -> OutputTab(
                        hasStoredCustomFace = hasStoredCustomFace,
                        exportScale = exportScale,
                        faceSpec = FaceGeometry.specFor(target.walletPackage),
                        onExportScaleChange = { exportScale = it },
                        onRestoreOriginal = { showRestoreDialog = true },
                    )
                    else -> AppElementsTab(
                        ctx = ctx,
                        target = target,
                        cfg = cfg,
                        onCfgChange = { cfg = it },
                    )
                }
                }
            }
        }
    }

    if (showAddText) {
        AddTextDialog(
            onDismiss = { showAddText = false },
            onConfirm = { text, color ->
                ensureBaseImageThen { baseError ->
                    if (baseError == null) {
                        val spec = StickerSpec(
                            id = newStickerId(),
                            kind = StickerKind.TEXT,
                            text = text,
                            color = color,
                            sizeDp = 24f,
                            cx = 0.5f,
                            cy = 0.35f,
                        )
                        cfg = cfg.copy(stickers = listOf(spec) + cfg.stickers)
                        selectedSticker.value = spec.id
                    }
                    showAddText = false
                }
            },
        )
    }

    if (showExitDialog) {
        PanelDialog(
            title = "您的修改尚未保存，确定离开吗？",
            summary = "可以保存后离开，也可以放弃本次修改。",
            onDismissRequest = { showExitDialog = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { save(thenExit = true) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(if (saving) "保存中…" else "保存并离开") }
                Button(
                    onClick = {
                        showExitDialog = false
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                ) { Text("不保存并离开") }
                Button(
                    onClick = { showExitDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("继续编辑") }
            }
        }
    }

    if (showRestoreDialog) {
        PanelDialog(
            title = "恢复原始卡面？",
            summary = "将删除本卡的自定义卡面与元素设置，钱包立即恢复钱包原始卡面。",
            onDismissRequest = { showRestoreDialog = false },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { showRestoreDialog = false },
                    modifier = Modifier.weight(1f),
                ) { Text("取消") }
                Button(
                    onClick = {
                        showRestoreDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    FaceRepository.clearCard(ctx, target.key, target.walletPackage)
                                    // Round 22: invalidate cached wallet-original snapshots so the
                                    // preview re-captures the current (restored) card face.
                                    FaceRepository.refreshOriginalSnapshots(
                                        ctx, target.key.type, target.key.id,
                                    )
                                }
                            }
                            val stillCustom = withContext(Dispatchers.IO) {
                                FaceRepository.hasCustomFace(
                                    ctx, target.key.type, target.key.id, target.walletPackage,
                                )
                            }
                            Toast.makeText(
                                ctx,
                                if (stillCustom) {
                                    "已删除本卡自定义，当前仍使用类型默认卡面"
                                } else {
                                    "已恢复钱包原始卡面"
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                            onDone()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("恢复") }
            }
        }
    }

    if (showFontPicker) {
        PanelDialog(
            title = "字体",
            summary = "选择内置字体或导入 .ttf / .otf",
            onDismissRequest = { showFontPicker = false },
        ) {
            FontPickerDialogBody(
                selectedState = selectedSticker,
                cfg = cfg,
                fontImporting = fontImporting,
                onApplyFamily = { stickerId, family, customPath ->
                    updateSticker(stickerId) {
                        it.copy(fontFamily = family, customFontPath = customPath)
                    }
                    showFontPicker = false
                },
                onImport = { openFontImporter() },
                onClose = { showFontPicker = false },
            )
        }
    }

    colorPickerRequest?.let { request ->
        val sticker = cfg.stickers.firstOrNull { it.id == request.stickerId }
        if (sticker != null) {
            val initial = when (request.target) {
                "glow" -> sticker.glowColor
                "stroke" -> sticker.strokeColor
                else -> if (sticker.color == -1) 0xFFFFFFFF.toInt() else sticker.color
            }
            ColorPickerDialog(
                title = request.label,
                initialColor = initial,
                onDismiss = { colorPickerRequest = null },
                onApply = { color ->
                    updateSticker(sticker.id) {
                        when (request.target) {
                            "glow" -> it.copy(glowColor = color)
                            "stroke" -> it.copy(strokeColor = color)
                            else -> it.copy(color = color)
                        }
                    }
                    colorPickerRequest = null
                },
            )
        }
    }

    renameStickerId?.let { id ->
        val sticker = cfg.stickers.firstOrNull { it.id == id }
        if (sticker != null) {
            RenameStickerDialog(
                spec = sticker,
                onDismiss = { renameStickerId = null },
                onConfirm = { newName ->
                    updateSticker(id) { it.copy(name = newName.ifBlank { null }) }
                    renameStickerId = null
                },
            )
        }
    }
}

@Composable
private fun ImageTab(
    ctx: Context,
    cfg: CardFaceConfig,
    imageImporting: Boolean,
    hasCurrentFace: Boolean,
    hasStoredCustomFace: Boolean,
    snapEnabled: Boolean,
    onSnapChange: (Boolean) -> Unit,
    exportZipRunning: Boolean,
    importZipRunning: Boolean,
    onPickImage: () -> Unit,
    onUseCurrentFace: () -> Unit,
    onUseSample: () -> Unit,
    onExportZip: () -> Unit,
    onImportZip: () -> Unit,
    onCfgChange: (CardFaceConfig) -> Unit,
) {
    val image = com.cardpersonality.honor.ui.util.rememberFaceImageBitmap(cfg.imagePath)
    val containScale = remember(image) {
        val bitmap = image
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            1f
        } else {
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            val cover = max(336f / w, 208f / h)
            val contain = min(336f / w, 208f / h)
            (contain / cover).coerceIn(0.4f, 1f)
        }
    }

    SettingsGroup {
        ArrowPreference(
            title = if (cfg.imagePath == null) "选择图片" else "更换图片",
            summary = when {
                imageImporting -> "正在导入…"
                cfg.imagePath != null -> "已导入图片，可拖动/缩放调整位置"
                else -> "从相册选择一张图片作为卡面"
            },
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Photos,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 14.dp).size(24.dp),
                )
            },
            onClick = onPickImage,
            enabled = !imageImporting,
        )
        ArrowPreference(
            title = "使用示例图片",
            summary = if (imageImporting) "正在导入…" else "内置示例卡面，快速体验合成效果",
            startAction = {
                Icon(
                    imageVector = MiuixIcons.ImageIcon,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 14.dp).size(24.dp),
                )
            },
            onClick = onUseSample,
            enabled = !imageImporting,
        )
        if (hasCurrentFace) {
            ArrowPreference(
                title = "以当前卡面为底图",
                summary = "把钱包当前卡面复制为底图，再在上面调整",
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.ImageIcon,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 14.dp).size(24.dp),
                    )
                },
                onClick = onUseCurrentFace,
                enabled = !imageImporting,
            )
        }
    }

    SettingsGroup {
        ArrowPreference(
            title = "导入卡面（ZIP）",
            summary = if (importZipRunning) {
                "正在导入…"
            } else {
                "从本模块导出的 ZIP 恢复卡面配置与资源（覆盖当前卡片）"
            },
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Import,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 14.dp).size(24.dp),
                )
            },
            onClick = onImportZip,
            enabled = !importZipRunning,
        )
        ArrowPreference(
            title = "保存当前卡面（ZIP）",
            summary = if (exportZipRunning) {
                "正在导出…"
            } else {
                "导出配置、源图、贴纸图片与字体到 Download/${FaceRepository.DOWNLOAD_EXPORT_DIR}"
            },
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Download,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 14.dp).size(24.dp),
                )
            },
            onClick = onExportZip,
            enabled = !exportZipRunning,
        )
    }

    SettingsGroup {
        LabeledSlider(
            title = "缩放",
            valueText = String.format("%.2fx", cfg.scale),
            value = cfg.scale,
            defaultValue = 1f,
            valueRange = 0.4f..4f,
            unit = "x",
            inputDecimals = 2,
            onValueChange = { onCfgChange(cfg.copy(scale = it)) },
        )
        LabeledSlider(
            title = "水平位置",
            valueText = String.format("%+.0f%%", cfg.offsetX * 100f),
            value = cfg.offsetX,
            defaultValue = 0f,
            valueRange = -0.5f..0.5f,
            unit = "%",
            inputMultiplier = 100f,
            onValueChange = { onCfgChange(cfg.copy(offsetX = it)) },
        )
        LabeledSlider(
            title = "垂直位置",
            valueText = String.format("%+.0f%%", cfg.offsetY * 100f),
            value = cfg.offsetY,
            defaultValue = 0f,
            valueRange = -0.5f..0.5f,
            unit = "%",
            inputMultiplier = 100f,
            onValueChange = { onCfgChange(cfg.copy(offsetY = it)) },
        )
    }

    SettingsGroup {
        SwitchPreference(
            checked = snapEnabled,
            onCheckedChange = onSnapChange,
            title = "自动吸附",
            summary = "拖动图片时自动对齐中心线（默认开）",
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = { onCfgChange(cfg.copy(scale = 1f, offsetX = 0f, offsetY = 0f)) },
            modifier = Modifier.weight(1f),
        ) { Text("填充") }
        Button(
            onClick = {
                val target = containScale
                onCfgChange(cfg.copy(scale = target, offsetX = 0f, offsetY = 0f))
            },
            modifier = Modifier.weight(1f),
        ) { Text("适应") }
        Button(
            onClick = {
                onCfgChange(
                    cfg.copy(
                        scale = 1f,
                        offsetX = 0f,
                        offsetY = 0f,
                        rotate = 0f,
                        flipX = false,
                        flipY = false,
                    ),
                )
            },
            modifier = Modifier.weight(1f),
        ) {
            Icon(MiuixIcons.RotateLeft, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("重置")
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = { onCfgChange(cfg.copy(flipX = !cfg.flipX)) },
            modifier = Modifier.weight(1f),
        ) { Text(if (cfg.flipX) "已水平翻转" else "水平翻转") }
        Button(
            onClick = { onCfgChange(cfg.copy(flipY = !cfg.flipY)) },
            modifier = Modifier.weight(1f),
        ) { Text(if (cfg.flipY) "已垂直翻转" else "垂直翻转") }
    }

    Text(
        text = "在预览卡片上单指拖动移动图片，双指缩放，双击可快速重置。",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
    )


}

@Composable
private fun OutputTab(
    hasStoredCustomFace: Boolean,
    exportScale: Int,
    faceSpec: FaceGeometry.FaceSpec,
    onExportScaleChange: (Int) -> Unit,
    onRestoreOriginal: () -> Unit,
) {
    val outW = faceSpec.designWidth.toInt() * exportScale
    val outH = faceSpec.designHeight.toInt() * exportScale
    Text(
        text = "输出画质决定保存到钱包的卡面分辨率；越高越清晰，文件也越大。",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
    )
    SettingsGroup {
        LabeledSlider(
            title = "导出画质",
            valueText = "${exportScale}x · ${outW} × ${outH}",
            value = exportScale.toFloat(),
            defaultValue = 4f,
            valueRange = 1f..10f,
            keyPoints = listOf(1f, 2f, 3f, 4f, 5f, 6f, 8f, 10f),
            unit = "x",
            onValueChange = { onExportScaleChange(it.roundToInt()) },
        )
    }
    Text(
        text = "当前倍率 ${exportScale}x，预计输出 ${outW} × ${outH} px。仅影响渲染/导出，不改动钱包原始卡面。",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
    )
    if (hasStoredCustomFace) {
        SettingsGroup {
            ArrowPreference(
                title = "重置为钱包原始卡面",
                summary = "删除自定义卡面，立即恢复钱包原图",
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Reset,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 14.dp).size(24.dp),
                    )
                },
                onClick = onRestoreOriginal,
            )
        }
    }
}
@Composable
private fun ElementsTab(
    cfg: CardFaceConfig,
    selectedStickerId: String?,
    snapEnabled: Boolean,
    onSnapChange: (Boolean) -> Unit,
    onCfgChange: (CardFaceConfig) -> Unit,
    onSelect: (String?) -> Unit,
    onAddText: () -> Unit,
    onAddImage: () -> Unit,
    onOpenFontPicker: () -> Unit,
    onOpenCustomColor: (StickerSpec, String) -> Unit,
    onRequestRename: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onAddText,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) {
            Icon(MiuixIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("添加文字")
        }
        Button(
            onClick = onAddImage,
            modifier = Modifier.weight(1f),
        ) {
            Icon(MiuixIcons.Photos, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("添加图片")
        }
    }

    SettingsGroup {
        SwitchPreference(
            checked = snapEnabled,
            onCheckedChange = onSnapChange,
            title = "自动吸附",
            summary = "拖动元素时自动对齐边缘与中心线（默认开）",
        )
    }

    if (cfg.stickers.isEmpty()) {
        Text(
            text = "还没有元素组件。添加文字或图片贴纸，它们会和卡面图片一起合成到最终卡面中。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        )
    } else {
        SettingsGroup {
            cfg.stickers.forEachIndexed { index, spec ->
                StickerRow(
                    spec = spec,
                    selected = spec.id == selectedStickerId,
                    isFirst = index == 0,
                    isLast = index == cfg.stickers.lastIndex,
                    onClick = { onSelect(spec.id) },
                    onLongPressName = { onRequestRename(spec.id) },
                    onToggleVisible = {
                        onCfgChange(cfg.copy(stickers = cfg.stickers.update(spec.id) { it.copy(visible = !it.visible) }))
                    },
                    onMoveUp = {
                        onCfgChange(cfg.copy(stickers = cfg.stickers.move(index, index - 1)))
                    },
                    onMoveDown = {
                        onCfgChange(cfg.copy(stickers = cfg.stickers.move(index, index + 1)))
                    },
                    onDelete = {
                        onCfgChange(cfg.copy(stickers = cfg.stickers.filterNot { it.id == spec.id }))
                        if (selectedStickerId == spec.id) onSelect(null)
                    },
                )
            }
        }
    }

    val selected = cfg.stickers.firstOrNull { it.id == selectedStickerId }
    if (selected != null) {
        SelectedStickerEditor(
            spec = selected,
            onOpenFontPicker = onOpenFontPicker,
            onOpenCustomColor = { target -> onOpenCustomColor(selected, target) },
            onCfgChange = { updatedSpec ->
                onCfgChange(
                    cfg.copy(
                        stickers = cfg.stickers.update(selected.id) { updatedSpec },
                    ),
                )
            },
            onDelete = {
                onCfgChange(cfg.copy(stickers = cfg.stickers.filterNot { it.id == selected.id }))
                onSelect(null)
            },
        )
    }
}

@Composable
private fun AppElementsTab(
    ctx: Context,
    target: EditorTarget,
    cfg: CardFaceConfig,
    onCfgChange: (CardFaceConfig) -> Unit,
) {
    // Which wallet's elements to show: the card's own wallet when known, otherwise every
    // installed supported wallet (Honor device -> Honor only; both installed -> grouped sections).
    val installedWallets = remember { WalletPackages.installed(ctx) }
    val activeWallets = remember(target.walletPackage, installedWallets) {
        when {
            target.walletPackage != null -> listOf(target.walletPackage)
            installedWallets.isNotEmpty() -> installedWallets.map { it.packageName }
            else -> listOf(WalletPackages.HONOR)
        }
    }
    val perWallet = activeWallets.associateWith { pkg ->
        AppElements.forType(
            packageName = pkg,
            type = target.key.type,
            reported = if (pkg == WalletPackages.HONOR) target.appElementIds else emptyList(),
        )
    }
    val anyElements = perWallet.values.any { it.isNotEmpty() }

    if (!anyElements) {
        val walletNames = activeWallets.joinToString("、") { WalletPackages.displayName(it) }
        Text(
            text = "尚未识别到该卡类型的界面元素。\n\n完成对${walletNames}的界面分析后，这里会列出钱包自己绘制的卡片元素（名称、卡号、余额等），你可以单独选择隐藏它们。默认始终“保持原样”。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        )
    } else {
        perWallet.forEach { (pkg, ids) ->
            if (ids.isEmpty()) return@forEach
            SmallTitle(WalletPackages.displayName(pkg) + " · " + pkg)
            SettingsGroup {
                ids.forEach { id ->
                    val hidden = id in cfg.hiddenAppElements
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = AppElements.label(pkg, id),
                                style = MiuixTheme.textStyles.body1,
                            )
                            Text(
                                text = if (hidden) "已隐藏" else "保持原样",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        SegmentedControl(
                            options = listOf("保持原样", "隐藏"),
                            selectedIndex = if (hidden) 1 else 0,
                            onSelect = { index ->
                                val nextHidden = if (index == 1) {
                                    (cfg.hiddenAppElements + id).distinct()
                                } else {
                                    cfg.hiddenAppElements - id
                                }
                                onCfgChange(cfg.copy(hiddenAppElements = nextHidden))
                            },
                            modifier = Modifier.width(150.dp),
                        )
                    }
                }
            }
        }
    }
    val walletNames = activeWallets.joinToString("、") { WalletPackages.displayName(it) }
    Text(
        text = "元素组件由${walletNames}自行绘制在卡片上方。隐藏操作在模块支持后生效，默认不修改任何内容。",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
    )
}
// --------------------------------------------------------------------------------- sub views

@Composable
private fun SelectedStickerEditor(
    spec: StickerSpec,
    onOpenFontPicker: () -> Unit,
    onOpenCustomColor: (String) -> Unit,
    onCfgChange: (StickerSpec) -> Unit,
    onDelete: () -> Unit,
) {
    SettingsGroup {
        if (spec.kind == StickerKind.TEXT) {
            TextField(
                value = spec.text,
                onValueChange = { onCfgChange(spec.copy(text = it)) },
                label = "文字内容",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            ColorSwatchRow(
                selectedColor = spec.color,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                onSelect = { onCfgChange(spec.copy(color = it)) },
                onCustomClick = { onOpenCustomColor("text") },
            )
            LabeledSlider(
                title = "字号",
                valueText = "${spec.sizeDp.roundToInt()}",
                value = spec.sizeDp,
                defaultValue = 24f,
                valueRange = 6f..120f,
                unit = "",
                onValueChange = { onCfgChange(spec.copy(sizeDp = it)) },
            )
        } else {
            LabeledSlider(
                title = "尺寸",
                valueText = "${(spec.w * 100f).roundToInt()}%",
                value = spec.w,
                defaultValue = 0.5f,
                valueRange = 0.05f..2.0f,
                unit = "%",
                inputMultiplier = 100f,
                onValueChange = { onCfgChange(spec.copy(w = it)) },
            )
            SwitchPreference(
                checked = spec.flipX,
                onCheckedChange = { onCfgChange(spec.copy(flipX = it)) },
                title = "水平翻转",
            )
            SwitchPreference(
                checked = spec.flipY,
                onCheckedChange = { onCfgChange(spec.copy(flipY = it)) },
                title = "垂直翻转",
            )
        }

        if (spec.kind == StickerKind.TEXT) {
            ArrowPreference(
                title = "字体",
                summary = stickerFontSummary(spec),
                onClick = onOpenFontPicker,
            )
            LabeledSlider(
                title = "粗细",
                valueText = fontWeightLabel(spec.fontWeight),
                value = spec.fontWeight.toFloat(),
                defaultValue = 700f,
                valueRange = 100f..900f,
                unit = "",
                onValueChange = {
                    val quantized = ((it / 100f).roundToInt() * 100).coerceIn(100, 900)
                    onCfgChange(spec.copy(fontWeight = quantized))
                },
            )
            SwitchPreference(
                checked = spec.italic,
                onCheckedChange = { onCfgChange(spec.copy(italic = it)) },
                title = "斜体",
            )
            SwitchPreference(
                checked = spec.underline,
                onCheckedChange = { onCfgChange(spec.copy(underline = it)) },
                title = "下划线",
            )
            SwitchPreference(
                checked = spec.glow,
                onCheckedChange = { onCfgChange(spec.copy(glow = it)) },
                title = "发光",
                summary = "在文字后方加柔和光晕",
            )
            if (spec.glow) {
                ColorSwatchRow(
                    selectedColor = spec.glowColor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    onSelect = { onCfgChange(spec.copy(glowColor = it)) },
                    onCustomClick = { onOpenCustomColor("glow") },
                )
                LabeledSlider(
                    title = "发光半径",
                    valueText = "${spec.glowRadius.roundToInt()}",
                    value = spec.glowRadius,
                    defaultValue = 8f,
                    valueRange = 1f..30f,
                    unit = "",
                    onValueChange = { onCfgChange(spec.copy(glowRadius = it)) },
                )
            }
            SwitchPreference(
                checked = spec.stroke,
                onCheckedChange = { onCfgChange(spec.copy(stroke = it)) },
                title = "描边",
                summary = "在文字外圈加一层轮廓",
            )
            if (spec.stroke) {
                ColorSwatchRow(
                    selectedColor = spec.strokeColor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    onSelect = { onCfgChange(spec.copy(strokeColor = it)) },
                    onCustomClick = { onOpenCustomColor("stroke") },
                )
                LabeledSlider(
                    title = "描边宽度",
                    valueText = String.format("%.1f", spec.strokeWidth),
                    value = spec.strokeWidth,
                    defaultValue = 2f,
                    valueRange = 0.5f..12f,
                    unit = "",
                    inputDecimals = 1,
                    onValueChange = { onCfgChange(spec.copy(strokeWidth = it)) },
                )
            }
        }

        val rotationHapticsContext = LocalContext.current
        LabeledSlider(
            title = "旋转",
            valueText = "${spec.rot.roundToInt()}°",
            value = spec.rot,
            defaultValue = 0f,
            valueRange = -270f..270f,
            keyPoints = (-270..270 step 90).map { it.toFloat() },
            // 0.007 of the 540° range ≈ 3.8° of light magnetic slowdown around each 90° multiple.
            magnetThreshold = 0.007f,
            unit = "°",
            onValueChange = { raw ->
                val nearest90 = (raw / 90f).roundToInt() * 90f
                val snapped = if (abs(raw - nearest90) <= ROTATION_SNAP_DEGREES) nearest90 else raw
                onCfgChange(spec.copy(rot = FaceGeometry.clampRotation(snapped)))
            },
            onValueChangeFinished = { finalValue ->
                // On release, finish the snap when close enough to a 90° multiple.
                val nearest90 = (finalValue / 90f).roundToInt() * 90f
                if (abs(finalValue - nearest90) <= ROTATION_RELEASE_SNAP_DEGREES &&
                    abs(finalValue - nearest90) > 0.01f
                ) {
                    onCfgChange(spec.copy(rot = FaceGeometry.clampRotation(nearest90)))
                    Haptics.tick(rotationHapticsContext)
                }
            },
        )
        LabeledSlider(
            title = "透明度",
            valueText = "${(spec.alpha * 100f).roundToInt()}%",
            value = spec.alpha,
            defaultValue = 1f,
            valueRange = 0.05f..1f,
            unit = "%",
            inputMultiplier = 100f,
            onValueChange = { onCfgChange(spec.copy(alpha = it)) },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = {
                    onCfgChange(
                        spec.copy(
                            cx = 0.5f,
                            cy = 0.5f,
                            rot = 0f,
                            sizeDp = if (spec.kind == StickerKind.TEXT) 24f else spec.sizeDp,
                            w = if (spec.kind == StickerKind.IMAGE) 0.32f else spec.w,
                            flipX = false,
                            flipY = false,
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("居中重置") }
            TextButton(
                text = "删除该元素",
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(
                    textColor = MiuixTheme.colorScheme.error,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Human-readable summary of the sticker's font for the 字体 row. */
private fun stickerFontSummary(spec: StickerSpec): String {
    if (!spec.customFontPath.isNullOrEmpty()) {
        return runCatching { java.io.File(spec.customFontPath).name + "（自定义）" }.getOrDefault("自定义字体（自定义）")
    }
    val family = spec.fontFamily
    if (family.isNullOrEmpty()) return "Sans Serif（西文，默认）"
    return FaceTypefaces.BUILT_IN_FAMILIES.firstOrNull { it.first == family }?.second ?: family
}

private fun fontWeightLabel(weight: Int): String = when {
    weight <= 200 -> "极细 $weight"
    weight <= 300 -> "细 $weight"
    weight <= 400 -> "常规 $weight"
    weight <= 500 -> "中等 $weight"
    weight <= 600 -> "半粗 $weight"
    weight <= 700 -> "粗 $weight"
    weight <= 800 -> "特粗 $weight"
    else -> "极粗 $weight"
}

/** Horizontal color swatch picker shared by text/glow/stroke controls and the add-text dialog. */
@Composable
private fun ColorSwatchRow(
    selectedColor: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
    onCustomClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        STICKER_COLORS.forEach { colorInt ->
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(colorInt))
                    .border(
                        width = if (selectedColor == colorInt) 3.dp else 1.dp,
                        color = if (selectedColor == colorInt) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.outline
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(colorInt) },
            )
        }
        if (onCustomClick != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.secondaryVariant)
                    .border(1.dp, MiuixTheme.colorScheme.outline, CircleShape)
                    .clickable { onCustomClick() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "＋",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSecondaryVariant,
                )
            }
        }
    }
}

/** Row used by the font picker dialog. */
@Composable
private fun FontOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) {
                    MiuixTheme.colorScheme.tertiaryContainer
                } else {
                    Color.Transparent
                },
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (selected) {
            Text(
                text = "已选",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * Compact font picker: the list is scrollable inside at most ~50% of the screen height and the
 * import action stays fixed at the bottom so it is always reachable.
 */
@Composable
private fun FontPickerDialogBody(
    selectedState: State<String?>,
    cfg: CardFaceConfig,
    fontImporting: Boolean,
    onApplyFamily: (String, String?, String?) -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit,
) {
    val sticker = cfg.stickers.firstOrNull {
        it.id == selectedState.value && it.kind == StickerKind.TEXT
    }
    // Compact dialog: total height (title+summary + list + pinned buttons) stays ≈45–50% of the
    // screen. The list gets the remaining space and scrolls internally.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val listMaxHeight = (screenHeight * 0.5f - 230.dp).coerceAtLeast(140.dp)
    Column(modifier = Modifier.fillMaxWidth()) {
        if (sticker != null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = listMaxHeight),
            ) {
                item("default") {
                    FontOptionRow(
                        label = "Sans Serif（西文，默认）",
                        selected = sticker.customFontPath == null && sticker.fontFamily.isNullOrEmpty(),
                    ) { onApplyFamily(sticker.id, null, null) }
                }
                FaceTypefaces.BUILT_IN_FAMILIES.forEach { (family, label) ->
                    item(family) {
                        FontOptionRow(
                            label = label,
                            selected = sticker.customFontPath == null && sticker.fontFamily == family,
                        ) { onApplyFamily(sticker.id, family, null) }
                    }
                }
                if (!sticker.customFontPath.isNullOrEmpty()) {
                    item("custom") {
                        val customName = runCatching {
                            java.io.File(sticker.customFontPath!!).name
                        }.getOrDefault("自定义字体")
                        FontOptionRow(
                            label = "$customName（自定义）",
                            selected = true,
                        ) { }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
                enabled = !fontImporting,
            ) { Text(if (fontImporting) "正在导入…" else "导入字体文件（.ttf / .otf）") }
        } else {
            Text(
                text = "请先在「元素」中选中一个文字元素",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("关闭")
        }
    }
}

/** Reads the selected-id State inside its own scope so editor selection changes don't recompose the
 *  whole editor screen; selection chrome is the only thing that needs to update. */
@Composable
private fun PreviewWithSelection(
    selectedState: State<String?>,
    cfg: CardFaceConfig,
    modifier: Modifier = Modifier,
    walletPackage: String? = null,
    backgroundImagePath: String? = null,
    snapEnabled: Boolean = false,
    onCfgChange: (CardFaceConfig) -> Unit,
    onStickerSelected: (String?) -> Unit,
) {
    CardFacePreview(
        cfg = cfg,
        modifier = modifier,
        selectedStickerIdState = selectedState,
        walletPackage = walletPackage,
        backgroundImagePath = backgroundImagePath,
        snapEnabled = snapEnabled,
        onCfgChange = onCfgChange,
        onStickerSelected = onStickerSelected,
    )
}

/** Same isolation for the elements tab (selection highlight / style sheet). */
@Composable
private fun SelectedElementsTab(
    selectedState: State<String?>,
    cfg: CardFaceConfig,
    snapEnabled: Boolean,
    onSnapChange: (Boolean) -> Unit,
    onCfgChange: (CardFaceConfig) -> Unit,
    onSelect: (String?) -> Unit,
    onAddText: () -> Unit,
    onAddImage: () -> Unit,
    onOpenFontPicker: () -> Unit,
    onOpenCustomColor: (StickerSpec, String) -> Unit,
    onRequestRename: (String) -> Unit,
) {
    ElementsTab(
        cfg = cfg,
        selectedStickerId = selectedState.value,
        snapEnabled = snapEnabled,
        onSnapChange = onSnapChange,
        onCfgChange = onCfgChange,
        onSelect = onSelect,
        onAddText = onAddText,
        onAddImage = onAddImage,
        onOpenFontPicker = onOpenFontPicker,
        onOpenCustomColor = onOpenCustomColor,
        onRequestRename = onRequestRename,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerRow(
    spec: StickerSpec,
    selected: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onLongPressName: () -> Unit,
    onToggleVisible: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MiuixTheme.colorScheme.tertiaryContainer else Color.Transparent,
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MiuixTheme.colorScheme.secondaryVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (spec.kind == StickerKind.TEXT) {
                Text(
                    text = "文",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSecondaryVariant,
                )
            } else {
                StickerThumbnail(
                    path = spec.imagePath,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stickerDisplayName(spec),
                style = MiuixTheme.textStyles.body1,
                maxLines = 1,
                modifier = Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPressName,
                ),
            )
            Text(
                text = if (spec.visible) "显示中" else "已隐藏",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
        IconButton(onClick = onMoveUp, enabled = !isFirst) {
            Icon(
                EditorIcons.ArrowUp,
                contentDescription = "上移一层",
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onMoveDown, enabled = !isLast) {
            Icon(
                EditorIcons.ArrowDown,
                contentDescription = "下移一层",
                modifier = Modifier.size(18.dp),
            )
        }
        Switch(checked = spec.visible, onCheckedChange = { onToggleVisible() })
        IconButton(onClick = onDelete) {
            Icon(MiuixIcons.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
        }
    }
}

private fun stickerDisplayName(spec: StickerSpec): String {
    val custom = spec.name?.takeIf { it.isNotBlank() }
    if (custom != null) return custom
    return if (spec.kind == StickerKind.TEXT) {
        spec.text.ifBlank { "（空文字）" }
    } else {
        "图片贴纸"
    }
}

/** 36dp rounded thumbnail of an image sticker, with the gallery glyph as fallback. */
@Composable
private fun StickerThumbnail(path: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = if (path.isNullOrEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { FaceImageLoader.load(path, 128)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    val image = bitmap
    if (image != null) {
        androidx.compose.foundation.Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                MiuixIcons.Photos,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSecondaryVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    valueText: String,
    value: Float,
    defaultValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    keyPoints: List<Float> = listOf(defaultValue),
    magnetThreshold: Float = 0.018f,
    /** Unit suffix used by the numeric-input dialog (e.g. "°", "%"). */
    unit: String = "",
    /** Displayed input = value * inputMultiplier (e.g. 100 for fractions). */
    inputMultiplier: Float = 1f,
    inputDecimals: Int = 0,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: ((Float) -> Unit)? = null,
) {
    val hapticsContext = LocalContext.current
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { 10.dp.toPx() }
    var trackWidthPx by remember { mutableIntStateOf(0) }
    var lastValue by remember { mutableStateOf(value) }
    LaunchedEffect(value) { lastValue = value }
    var showInputDialog by remember { mutableStateOf(false) }

    val points = keyPoints.ifEmpty { listOf(defaultValue) }
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    // Miuix magnetThreshold is a fraction of the slider range (magnetic slowdown near key points).
    // Haptics use a much narrower band so the tick means "you are effectively at the key point".
    val hapticBand = span * 0.01f
    // Key-point haptics fire once when *entering* a key point, never when leaving it.
    var insideKeyPoint by remember { mutableStateOf(false) }
    var lastEndTickAt by remember { mutableLongStateOf(0L) }
    val latestOnFinish by rememberUpdatedState(onValueChangeFinished)

    fun displayOf(raw: Float): String =
        String.format(java.util.Locale.US, "%.${inputDecimals}f", raw * inputMultiplier)

    fun emit(newValue: Float) {
        val coerced = newValue.coerceIn(valueRange.start, valueRange.endInclusive)
        if (abs(coerced - lastValue) < 0.000001f) return
        val nowInside = points.any { abs(coerced - it) <= hapticBand }
        val atStart = coerced <= valueRange.start + 0.0001f
        val atEnd = coerced >= valueRange.endInclusive - 0.0001f
        val wasAtStart = lastValue <= valueRange.start + 0.0001f
        val wasAtEnd = lastValue >= valueRange.endInclusive - 0.0001f
        lastValue = coerced
        if (nowInside && !insideKeyPoint) {
            insideKeyPoint = true
            Haptics.tick(hapticsContext)
        } else if (!nowInside) {
            insideKeyPoint = false
        }
        // One tick when hitting either end of the track (100 ms throttled).
        if ((atStart && !wasAtStart) || (atEnd && !wasAtEnd)) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastEndTickAt >= 100L) {
                lastEndTickAt = now
                Haptics.tick(hapticsContext)
            }
        }
        onValueChange(coerced)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MiuixTheme.textStyles.body1)
            Spacer(Modifier.weight(1f))
            // One-tap reset to this control's default (sits left of the value label).
            IconButton(
                onClick = { emit(defaultValue) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Reset,
                    contentDescription = "$title 重置为默认",
                    tint = if (abs(value - defaultValue) < 0.0001f) {
                        MiuixTheme.colorScheme.onBackgroundVariant
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            // Tap the value to type an exact number instead of dragging.
            Text(
                text = valueText,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showInputDialog = true }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Slider(
            value = value,
            onValueChange = { emit(it) },
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { trackWidthPx = it.width }
                .pointerInput(valueRange, defaultValue, trackWidthPx) {
                    // Miuix Slider is drag-only; add tap-to-set without consuming the gesture (so
                    // dragging still works): detect a tap ourselves in the Main pass.
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downPosition = down.position
                        var moved = false
                        var upPosition = downPosition
                        var released = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            upPosition = change.position
                            if ((upPosition - downPosition).getDistance() > slop) moved = true
                            if (!change.pressed) {
                                released = true
                                break
                            }
                        }
                        if (!moved && trackWidthPx > 0) {
                            val usable = (trackWidthPx - 2f * thumbRadiusPx).coerceAtLeast(1f)
                            val fraction = ((upPosition.x - thumbRadiusPx) / usable).coerceIn(0f, 1f)
                            emit(
                                valueRange.start +
                                    fraction * (valueRange.endInclusive - valueRange.start),
                            )
                        }
                        if (released) latestOnFinish?.invoke(lastValue)
                    }
                },
            showKeyPoints = true,
            keyPoints = points,
            magnetThreshold = magnetThreshold,
            // We fire the key-point haptics ourselves (Miuix Step needs steps/snap points).
            hapticEffect = SliderDefaults.SliderHapticEffect.None,
        )
    }

    if (showInputDialog) {
        val rangeHint = "范围：" + displayOf(valueRange.start) + unit + " ~ " +
            displayOf(valueRange.endInclusive) + unit
        var inputText by remember(showInputDialog) { mutableStateOf(displayOf(value)) }
        var errorText by remember(showInputDialog) { mutableStateOf<String?>(null) }
        PanelDialog(
            title = "输入$title",
            summary = rangeHint,
            onDismissRequest = { showInputDialog = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = inputText,
                    onValueChange = {
                        inputText = it
                        errorText = null
                    },
                    label = "$title$unit",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorText != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = errorText!!,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { showInputDialog = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(),
                    ) { Text("取消") }
                    Button(
                        onClick = {
                            val normalized = inputText.trim()
                                .replace('，', '.')
                                .replace("＋", "+")
                                .replace("－", "-")
                            val parsed = normalized.toFloatOrNull()
                            if (parsed == null || !parsed.isFinite()) {
                                errorText = "请输入有效数字（$rangeHint）"
                            } else {
                                val raw = parsed / inputMultiplier
                                val clamped = raw.coerceIn(
                                    valueRange.start,
                                    valueRange.endInclusive,
                                )
                                emit(clamped)
                                showInputDialog = false
                                if (abs(clamped - raw) > 0.000001f) {
                                    android.widget.Toast.makeText(
                                        hapticsContext,
                                        "已按范围限制为 " + displayOf(clamped) + unit,
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text("确定") }
                }
            }
        }
    }
}

@Composable
private fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) MiuixTheme.colorScheme.surfaceVariant else Color.Transparent,
                    )
                    .clickable { if (!selected) onSelect(index) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (selected) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        content()
    }
}

@Composable
private fun ColorPickerDialog(
    title: String,
    initialColor: Int,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit,
) {
    var hex by remember(initialColor) { mutableStateOf(colorToHex(initialColor)) }
    val parsed = parseHexColor(hex)
    PanelDialog(
        title = title,
        summary = "选择预设颜色，或输入 #RRGGBB / #AARRGGBB（支持 #RGB 简写与中文全角输入）",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            PRESET_COLORS.chunked(8).forEach { rowColors ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowColors.forEach { colorInt ->
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(colorInt))
                                .border(
                                    width = if (parsed == colorInt) 3.dp else 1.dp,
                                    color = if (parsed == colorInt) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.outline
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { hex = colorToHex(colorInt) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(parsed?.let { Color(it) } ?: Color.Transparent)
                        .border(1.dp, MiuixTheme.colorScheme.outline, CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                TextField(
                    value = hex,
                    onValueChange = { hex = it },
                    label = "#RRGGBB / #AARRGGBB",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (parsed == null) "颜色格式无效（示例：#FF3482FF）" else "预览 ${colorToHex(parsed)}",
                style = MiuixTheme.textStyles.footnote1,
                color = if (parsed == null) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onBackgroundVariant
                },
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = { parsed?.let(onApply) },
                    modifier = Modifier.weight(1f),
                    enabled = parsed != null,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("应用") }
            }
        }
    }
}

@Composable
private fun RenameStickerDialog(
    spec: StickerSpec,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(spec.id) { mutableStateOf(spec.name ?: "") }
    PanelDialog(
        title = "重命名元素",
        summary = "留空则恢复默认名称；长按列表中的名称可再次重命名。",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = "元素名称",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = { onConfirm(name.trim()) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("确定") }
            }
        }
    }
}

@Composable
private fun AddTextDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(STICKER_COLORS[0]) }
    PanelDialog(
        title = "添加文字",
        summary = "文字会显示在卡面图片上方，可拖动调整位置。",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = "输入文字",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                STICKER_COLORS.forEach { colorInt ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(colorInt))
                            .border(
                                width = if (color == colorInt) 3.dp else 1.dp,
                                color = if (color == colorInt) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.outline
                                },
                                shape = CircleShape,
                            )
                            .clickable { color = colorInt },
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = { if (text.isNotBlank()) onConfirm(text.trim(), color) },
                    modifier = Modifier.weight(1f),
                    enabled = text.isNotBlank(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("添加") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- helpers

/** Rotation slider/gesture snap window around multiples of 90° (degrees). */
private const val ROTATION_SNAP_DEGREES = 11f
/** On release, a rotation within this many degrees of a multiple of 90° finishes snapping. */
private const val ROTATION_RELEASE_SNAP_DEGREES = 15f
private fun newStickerId(): String = "s_" + UUID.randomUUID().toString().take(8)

private fun List<StickerSpec>.update(id: String, block: (StickerSpec) -> StickerSpec): List<StickerSpec> =
    map { if (it.id == id) block(it) else it }

private fun List<StickerSpec>.move(from: Int, to: Int): List<StickerSpec> {
    if (from == to || from !in indices || to !in indices) return this
    val list = toMutableList()
    val item = list.removeAt(from)
    list.add(to, item)
    return list
}







































