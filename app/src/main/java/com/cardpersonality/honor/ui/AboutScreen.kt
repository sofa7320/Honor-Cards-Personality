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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cardpersonality.honor.BuildConfig
import com.cardpersonality.honor.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val GITHUB_REPO_URL = "https://github.com/sofa7320/Honor-Cards-Personality"
private const val GITHUB_ISSUES_URL = "https://github.com/sofa7320/Honor-Cards-Personality/issues/new"

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
    val walletSnapshot = remember { WalletProbe.query(ctx) }
    val installedWallets = remember(walletSnapshot) {
        walletSnapshot?.wallets ?: WalletPackages.installed(ctx).map {
            WalletStatus(it.packageName, it.displayName, "", true, it.versionName)
        }
    }
    val honorInstalled = installedWallets.firstOrNull {
        it.packageName == WalletPackages.HONOR && it.installed
    }
    val oppoInstalled = installedWallets.firstOrNull {
        it.packageName == WalletPackages.OPPO && it.installed
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
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(18.dp)),
                    )
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
                            summary = WalletPackages.HONOR +
                                if (honorInstalled != null) {
                                    " · 已安装" + (honorInstalled.versionName?.let { "（本机 $it）" } ?: "")
                                } else {
                                    " · 未安装"
                                },
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp)
                                .height(0.5.dp)
                                .background(MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.2f)),
                        )
                        AboutInfoRow(
                            label = "欧加钱包",
                            value = WalletPackages.OPPO_SUPPORTED_VERSION,
                            summary = WalletPackages.OPPO +
                                if (oppoInstalled != null) {
                                    " · 已安装" + (oppoInstalled.versionName?.let { "（本机 $it）" } ?: "")
                                } else {
                                    " · 未安装"
                                },
                        )
                    }
                }
            }

            item { SmallTitle("链接") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    onClick = { openUrl(GITHUB_REPO_URL) },
                ) {
                    AboutLinkRow(
                        label = "GitHub 仓库",
                        value = "sofa7320/Honor-Cards-Personality",
                    )
                }
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    onClick = { openUrl(GITHUB_ISSUES_URL) },
                ) {
                    AboutLinkRow(
                        label = "发送错误报告",
                        value = "GitHub Issues",
                    )
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
    value: String,
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
            Text(
                text = value,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}



