package com.cardpersonality.honor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cardpersonality.honor.BuildConfig
import com.cardpersonality.honor.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.cardpersonality.honor.ui.util.DiagnosticExporter
import kotlinx.coroutines.launch

private const val GITHUB_REPO_URL = "https://github.com/sofa7320/Honor-Cards-Personality"
private const val GITHUB_ISSUES_URL = "https://github.com/sofa7320/Honor-Cards-Personality/issues/new"
private const val MIUIX_URL = "https://github.com/compose-miuix-ui/miuix"
private const val LSPOSED_URL = "https://github.com/LSPosed/LSPosed"
private const val OPLUS_CARD_CUSTOMIZER_URL = "https://github.com/crrashh1542/oplus-card-customizer"

/**
 * Miuix "About" page: app icon/name/version, the wallet versions this build supports, a link to the
 * GitHub repository / issue tracker (opened via a plain ACTION_VIEW intent, fail-safe) and a short
 * acknowledgements block.
 */
@Composable
fun AboutScreen(
    ctx: Context,
    onBack: () -> Unit,
) {
    var exporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val bridgeConnected = remember {
        runCatching {
            com.cardpersonality.honor.data.bridge.PanelBridgeServer.isConnected()
        }.getOrDefault(false)
    }
    val bridgePort = remember {
        runCatching {
            com.cardpersonality.honor.data.bridge.PanelBridgeServer.port()
        }.getOrDefault(0)
    }
    val bridgeHelloProcess = remember {
        runCatching {
            com.cardpersonality.honor.data.bridge.PanelBridgeServer.lastHelloProcess()
        }.getOrDefault("")
    }
    val launcherIcon = rememberLauncherIconBitmap(ctx)
    fun exportDiagnostics() {
        if (exporting) return
        exporting = true
        scope.launch {
            val result = DiagnosticExporter.export(ctx)
            exporting = false
            if (result.ok) {
                val suffix = result.error?.let { "（$it）" } ?: ""
                Toast.makeText(
                    ctx,
                    "已导出诊断报告：${result.path}$suffix",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(
                    ctx,
                    "导出失败：" + (result.error ?: "未知错误"),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun openUrl(url: String) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            Toast.makeText(
                ctx,
                "无法打开链接：" + (t.message?.take(80) ?: t.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "关于",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            MiuixIcons.ChevronForward,
                            contentDescription = "返回",
                            modifier = Modifier.rotate(180f),
                        )
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
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (launcherIcon != null) {
                        Image(
                            bitmap = launcherIcon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(24.dp)),
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(24.dp)),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MiuixTheme.textStyles.title2,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "版本 v${BuildConfig.VERSION_NAME}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "为本机钱包自定义卡面：图片、文字、贴纸与组件显示，全部离线处理。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }

            item { SmallTitle("支持的钱包") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column {
                        AboutInfoRow(
                            label = "荣耀钱包",
                            value = WalletPackages.HONOR_SUPPORTED_VERSION,
                            summary = WalletPackages.HONOR,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp)
                                .height(0.5.dp)
                                .background(
                                    MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.2f),
                                ),
                        )
                        AboutInfoRow(
                            label = "欧加钱包",
                            value = WalletPackages.OPPO_SUPPORTED_VERSION,
                            summary = WalletPackages.OPPO,
                        )
                    }
                }
            }
            item { SmallTitle("反馈") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    onClick = { openUrl(GITHUB_REPO_URL) },
                ) {
                    AboutLinkRow(label = "GitHub 仓库")
                }
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    onClick = { openUrl(GITHUB_ISSUES_URL) },
                ) {
                    AboutLinkRow(label = "发送错误报告")
                }
            }

            item { SmallTitle("开源支持") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    onClick = { openUrl(MIUIX_URL) },
                ) {
                    OpenSourceRow(
                        label = "Miuix",
                        description = "界面组件与设计规范",
                    )
                }
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    onClick = { openUrl(LSPOSED_URL) },
                ) {
                    OpenSourceRow(
                        label = "LSPosed",
                        description = "Xposed 框架与模块加载",
                    )
                }
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    onClick = { openUrl(OPLUS_CARD_CUSTOMIZER_URL) },
                ) {
                    OpenSourceRow(
                        label = "oplus-card-customizer",
                        description = "OPPO 钱包卡面自定义支持（实现参考）",
                    )
                }
            }
            item { SmallTitle("诊断") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    onClick = { exportDiagnostics() },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("导出诊断报告", style = MiuixTheme.textStyles.body1)
                            Text(
                                text = if (exporting) {
                                    "正在收集设备 / Root / LSPosed / 操作记录…"
                                } else if (bridgeConnected) {
                                    "OPPO bridge：已连接（端口 $bridgePort，进程 ${bridgeHelloProcess.ifEmpty { "未知" }}）；" +
                                        "点击导出设备 / Root / LSPosed / 操作记录"
                                } else {
                                    "OPPO bridge：未连接（请打开欧加钱包并确认模块已启用 v44+）；" +
                                        "点击导出设备 / Root / LSPosed / 操作记录"
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                        }
                        if (exporting) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                MiuixIcons.ChevronForward,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onBackgroundVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            item { SmallTitle("致谢") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "感谢 Miuix（界面组件）、LSPosed（模块框架）以及所有开源贡献者。\n" +
                            "本应用只在本机修改卡面显示，不收集、不上传任何数据。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}

/** Label + value row matching the LSPosed-style "label left, value right" card rows. */
@Composable
private fun AboutInfoRow(
    label: String,
    value: String,
    summary: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MiuixTheme.textStyles.body1)
            if (!summary.isNullOrEmpty()) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

@Composable
private fun AboutLinkRow(
    label: String,
    /** Optional right-hand value; when null only the title + chevron are shown. */
    value: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MiuixTheme.textStyles.body1)
        Spacer(Modifier.width(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!value.isNullOrBlank()) {
                Text(
                    text = value,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun OpenSourceRow(
    label: String,
    description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            MiuixIcons.ChevronForward,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Reads the real launcher icon (the same AdaptiveIconDrawable the desktop uses); avoids drawing a
 * look-alike vector and works with adaptive icons that Compose painterResource cannot decode.
 */
@Composable
private fun rememberLauncherIconBitmap(ctx: Context): ImageBitmap? {
    return remember(ctx) {
        runCatching {
            val drawable = ctx.packageManager.getApplicationIcon(ctx.packageName)
            val size = 256
            val bitmap = android.graphics.Bitmap.createBitmap(
                size,
                size,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            val canvas = android.graphics.Canvas(bitmap)
            if (drawable is android.graphics.drawable.AdaptiveIconDrawable) {
                // Desktop launchers draw the adaptive background + foreground layers and clip them
                // with the icon mask. Doing the same here keeps the About icon identical to the
                // launcher icon (drawing only foreground would lose the blue plate + mask).
                drawable.setBounds(0, 0, size, size)
                // AdaptiveIconDrawable exposes the system mask as a Path (API 33+); clip the two
                // layers with it so the About icon matches the launcher (blue plate + mask).
                val maskPath: android.graphics.Path? = if (
                    android.os.Build.VERSION.SDK_INT >= 33
                ) {
                    runCatching { drawable.iconMask }.getOrNull()
                } else {
                    null
                }
                canvas.save()
                if (maskPath != null && !maskPath.isEmpty) {
                    val bounds = android.graphics.RectF()
                    maskPath.computeBounds(bounds, true)
                    if (bounds.width() > 0f && bounds.height() > 0f) {
                        // Normalize the mask Path (not the canvas) into 0..size so the layer
                        // bounds (0..size) stay aligned with the clip.
                        val scaleX = size / bounds.width()
                        val scaleY = size / bounds.height()
                        val matrix = android.graphics.Matrix()
                        matrix.setScale(scaleX, scaleY)
                        matrix.postTranslate(-bounds.left * scaleX, -bounds.top * scaleY)
                        maskPath.transform(matrix)
                    }
                    canvas.clipPath(maskPath)
                }
                drawable.background?.let { layer ->
                    layer.setBounds(0, 0, size, size)
                    layer.draw(canvas)
                }
                drawable.foreground?.let { layer ->
                    layer.setBounds(0, 0, size, size)
                    layer.draw(canvas)
                }
                canvas.restore()
            } else {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
            }
            bitmap.asImageBitmap()
        }.getOrNull()
    }
}

















