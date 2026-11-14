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
        enableEdgeToEdge()
        setContent { CardPersonalityApp() }
    }
}
