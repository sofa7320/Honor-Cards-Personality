package com.cardpersonality.honor.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cardpersonality.honor.BuildConfig
import com.cardpersonality.honor.FaceImageLoader
import com.cardpersonality.honor.R
import com.cardpersonality.honor.data.CardFaceConfig
import com.cardpersonality.honor.data.CardInfo
import com.cardpersonality.honor.data.CardKey
import com.cardpersonality.honor.data.CardTypes
import com.cardpersonality.honor.data.Contract
import com.cardpersonality.honor.data.FaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home: cards reported by the hook (grouped by family) + per-type default entries. */
@Composable
fun HomeScreen(
    ctx: Context,
    onOpenEditor: (EditorTarget) -> Unit,
) {
    var cards by remember { mutableStateOf<List<CardInfo>>(emptyList()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var reportUpdatedAt by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var moduleConnected by remember { mutableStateOf<Boolean?>(null) }
    var storeVersion by remember { mutableIntStateOf(0) }

    // Refresh the card report whenever the panel comes back to the foreground (e.g. after
    // the user opened the wallet once so the hook could report detected cards).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(reloadKey) {
        loading = true
        withContext(Dispatchers.IO) {
            cards = FaceRepository.cards(ctx)
            reportUpdatedAt = FaceRepository.reportUpdatedAt(ctx)
            storeVersion = FaceRepository.storeVersion(ctx)
        }
        moduleConnected = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.call(
                    Uri.parse("content://" + FaceRepository.AUTHORITY),
                    Contract.CALL_PING,
                    null,
                    null,
                ) != null
            }.getOrDefault(false)
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_name),
                subtitle = "荣耀钱包 · 自定义卡面",
                actions = {
                    IconButton(onClick = { reloadKey++ }) {
                        Icon(MiuixIcons.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 32.dp,
            ),
        ) {
            item { StatusCard(moduleConnected, reportUpdatedAt, cards.size, BuildConfig.VERSION_NAME) }

            val grouped = cards.groupBy { it.type }
            if (cards.isEmpty()) {
                item {
                    EmptyState(
                        loading = loading,
                        reportUpdatedAt = reportUpdatedAt,
                        cardCount = cards.size,
                        onRefresh = { reloadKey++ },
                        onOpenSample = {
                            onOpenEditor(
                                EditorTarget(
                                    key = CardKey(CardTypes.TRANSIT, "sample"),
                                    title = "示例卡片",
                                    subtitle = "交通卡 · 用于预览编辑器效果",
                                ),
                            )
                        },
                    )
                }
            } else {
                item { SmallTitle("已识别的卡片") }
                CardTypes.ALL.forEach { type ->
                    val list = grouped[type].orEmpty()
                    if (list.isNotEmpty()) {
                        item { SmallTitle(CardTypes.displayName(type)) }
                        items(list, key = { it.key.fileKey }) { card ->
                            DetectedCardRow(ctx, card, reloadKey, onOpenEditor)
                        }
                    }
                }
                val otherTypes = grouped.keys.filter { it !in CardTypes.ALL }
                otherTypes.forEach { type ->
                    item { SmallTitle(CardTypes.displayName(type)) }
                    items(grouped[type].orEmpty(), key = { it.key.fileKey }) { card ->
                        DetectedCardRow(ctx, card, reloadKey, onOpenEditor)
                    }
                }
            }

            item { SmallTitle("按卡类型设置") }
            item {
                Text(
                    text = "为某一类卡片设置默认卡面；没有单独设置的卡片会使用这里的配置。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                )
            }
            items(CardTypes.ALL, key = { "type_$it" }) { type ->
                TypeDefaultRow(ctx, type, reloadKey, onOpenEditor)
            }

            item {
                Text(
                    text = "提示：设置完成后重新打开荣耀钱包即可看到新的卡面。卡面仅保存在本机，不上传。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                )
            }
        }
    }

}

@Composable
private fun StatusCard(
    moduleConnected: Boolean?,
    reportUpdatedAt: Long,
    cardCount: Int,
    versionName: String,
) {
    val hookReported = reportUpdatedAt > 0L
    val accent = when {
        moduleConnected == false -> Color(0xFFE94634)
        hookReported -> Color(0xFF34C759)
        moduleConnected == null -> MiuixTheme.colorScheme.onBackgroundVariant
        else -> Color(0xFFFFB020)
    }
    val title = when {
        moduleConnected == false -> "配置服务不可用"
        moduleConnected == null -> "正在检查模块状态…"
        hookReported && cardCount > 0 -> "模块通信正常"
        hookReported -> "模块已连接，未识别到卡片"
        else -> "等待卡片上报"
    }
    val summary = when {
        moduleConnected == false -> "本机应用内配置服务异常，请重新打开应用"
        moduleConnected == null -> ""
        hookReported -> buildString {
            append("卡片上报：")
            append(formatTime(reportUpdatedAt))
            append(" · ")
            append(if (cardCount > 0) "$cardCount 张卡片" else "0 张卡片（打开钱包卡片页后再刷新）")
        }
        else -> "请确认 LSPosed 已启用本模块，并打开一次荣耀钱包"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.body1)
                if (summary.isNotEmpty()) {
                    Text(
                        summary,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
            if (moduleConnected == null) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "v$versionName",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetectedCardRow(
    ctx: Context,
    card: CardInfo,
    refreshToken: Int,
    onOpenEditor: (EditorTarget) -> Unit,
) {
    val cfg = remember(card.key.fileKey, refreshToken) {
        FaceRepository.resolveConfig(ctx, card.key.type, card.key.id) ?: CardFaceConfig()
    }
    val displayName = card.name.ifBlank { CardTypes.displayName(card.type) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = {
            onOpenEditor(
                EditorTarget(
                    key = card.key,
                    title = displayName,
                    subtitle = card.subtitle.ifBlank { CardTypes.displayName(card.type) },
                    appElementIds = card.appElementIds,
                ),
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardFaceRowThumbnail(ctx, card, cfg, refreshToken)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MiuixTheme.textStyles.subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (cfg.hasCustomFace) {
                        CustomBadge()
                    }
                }
                Text(
                    text = buildString {
                        append(card.subtitle.ifBlank { CardTypes.displayName(card.type) })
                        if (card.lastSeen > 0L) {
                            append(" · ")
                            append(formatTime(card.lastSeen))
                        }
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TypeDefaultRow(
    ctx: Context,
    type: String,
    refreshToken: Int,
    onOpenEditor: (EditorTarget) -> Unit,
) {
    val key = remember(type) { CardKey.typeDefault(type) }
    val cfg = remember(type, key, refreshToken) {
        FaceRepository.loadCard(ctx, key) ?: CardFaceConfig()
    }
    val displayName = CardTypes.displayName(type)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = {
            onOpenEditor(
                EditorTarget(
                    key = key,
                    title = displayName + " · 默认卡面",
                    subtitle = "对该类型下所有未单独设置的卡片生效",
                ),
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeDefaultThumbnail(ctx, type, cfg, refreshToken)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(displayName, style = MiuixTheme.textStyles.body1)
                    if (cfg.hasCustomFace) {
                        Spacer(Modifier.width(8.dp))
                        CustomBadge()
                    }
                }
                Text(
                    text = if (cfg.hasCustomFace) "已设置默认卡面" else "未设置 · 使用钱包原始卡面",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
            Icon(
                MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Row thumbnail: composed export when the card has a custom face, otherwise the wallet's current
 * face snapshot (hook-captured, or downloaded from the reported CDN URL), else a type icon.
 */
@Composable
private fun CardFaceRowThumbnail(
    ctx: Context,
    card: CardInfo,
    cfg: CardFaceConfig,
    refreshToken: Int,
) {
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        card.key.fileKey,
        cfg.version,
        cfg.hasCustomFace,
        card.lastSeen,
        card.faceUrl,
        refreshToken,
    ) {
        value = withContext(Dispatchers.IO) {
            val file = if (cfg.hasCustomFace) {
                FaceRepository.exportFileFor(ctx, card.type, card.key.id)
            } else {
                FaceRepository.ensureOriginalFace(ctx, card)
            }
            file?.let { FaceImageLoader.load(it.absolutePath, 640)?.asImageBitmap() }
        }
    }
    val image = bitmap
    Box(
        modifier = Modifier
            .width(92.dp)
            .height(57.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(Color(0xFF343A46), Color(0xFF1C1F26)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = CardTypeUi.icon(card.type),
                contentDescription = null,
                tint = Color(0x66FFFFFF),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun TypeDefaultThumbnail(
    ctx: Context,
    type: String,
    cfg: CardFaceConfig,
    refreshToken: Int,
) {
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        type,
        cfg.version,
        cfg.hasCustomFace,
        refreshToken,
    ) {
        value = withContext(Dispatchers.IO) {
            if (!cfg.hasCustomFace) {
                null
            } else {
                FaceRepository.exportFileFor(ctx, type, CardKey.TYPE_DEFAULT_ID)
                    ?.let { FaceImageLoader.load(it.absolutePath, 400)?.asImageBitmap() }
            }
        }
    }
    val image = bitmap
    Box(
        modifier = Modifier
            .width(92.dp)
            .height(57.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.secondaryVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = CardTypeUi.icon(type),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSecondaryVariant,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun CustomBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "已自定义",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun EmptyState(
    loading: Boolean,
    reportUpdatedAt: Long,
    cardCount: Int,
    onRefresh: () -> Unit,
    onOpenSample: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                MiuixIcons.BankCards,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.size(46.dp),
            )
            Text("尚未识别到卡片", style = MiuixTheme.textStyles.subtitle)
            Text(
                text = "打开一次荣耀钱包后返回本页刷新；也可以先按卡类型设置默认卡面。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            Text(
                text = if (reportUpdatedAt > 0L) {
                    "诊断：最近上报 " + formatTime(reportUpdatedAt) + " · 解析到 $cardCount 张卡片"
                } else {
                    "诊断：尚未收到模块的卡片上报"
                },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = onRefresh,
                enabled = !loading,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.widthIn(min = 160.dp),
            ) { Text(if (loading) "刷新中…" else "刷新") }
            TextButton(
                text = "打开示例编辑器",
                onClick = onOpenSample,
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0L) return ""
    return try {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    } catch (_: Throwable) {
        ""
    }
}
