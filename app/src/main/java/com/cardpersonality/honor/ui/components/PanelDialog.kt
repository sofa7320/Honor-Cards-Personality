package com.cardpersonality.honor.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix-styled modal dialog backed by a plain Compose [Dialog].
 *
 * Deliberately NOT Miuix's OverlayDialog: that one needs `LocalDialogStates`/`MiuixPopupHost`
 * from a Scaffold ancestor, which made dialogs composed outside the Scaffold content invisible.
 * A real Dialog window always renders and still inherits the Miuix theme composition locals.
 */
@Composable
fun PanelDialog(
    title: String,
    summary: String? = null,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            cornerRadius = 22.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            ) {
                Text(title, style = MiuixTheme.textStyles.title4)
                if (!summary.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = summary,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
                Spacer(Modifier.height(18.dp))
                content()
            }
        }
    }
}
