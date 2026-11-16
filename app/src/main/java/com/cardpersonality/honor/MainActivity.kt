package com.cardpersonality.honor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cardpersonality.honor.ui.CardPersonalityApp

/**
 * Launcher activity — thin Compose host owned by module-dev.
 * The whole Miuix UI (theme, screens, editor) lives in com.cardpersonality.honor.ui (owned by ui-dev).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Round 18: start the loopback bridge before the first frame so OPPO wallet processes can
        // connect even if the panel is opened right after a wallet restart.
        try {
            com.cardpersonality.honor.data.bridge.PanelBridgeServer.ensureStarted(this)
        } catch (_: Throwable) {
        }
        enableEdgeToEdge()
        setContent { CardPersonalityApp() }
    }
}

