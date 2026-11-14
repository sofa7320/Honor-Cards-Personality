package com.cardpersonality.honor.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/**
 * Tiny hand-built vector icons for controls that Miuix 0.9.0 does not ship (layer up/down arrows).
 * Kept local so we don't add a material-icons dependency.
 */
object EditorIcons {
    val ArrowUp: ImageVector by lazy { buildArrow(up = true) }
    val ArrowDown: ImageVector by lazy { buildArrow(up = false) }

    private fun buildArrow(up: Boolean): ImageVector {
        val path = PathBuilder().apply {
            if (up) {
                moveTo(12f, 4f)
                lineTo(19.5f, 11.5f)
                lineTo(13.6f, 11.5f)
                lineTo(13.6f, 20f)
                lineTo(10.4f, 20f)
                lineTo(10.4f, 11.5f)
                lineTo(4.5f, 11.5f)
            } else {
                moveTo(12f, 20f)
                lineTo(4.5f, 12.5f)
                lineTo(10.4f, 12.5f)
                lineTo(10.4f, 4f)
                lineTo(13.6f, 4f)
                lineTo(13.6f, 12.5f)
                lineTo(19.5f, 12.5f)
            }
            close()
        }
        return ImageVector.Builder(
            name = if (up) "EditorArrowUp" else "EditorArrowDown",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = path.nodes,
            fill = SolidColor(Color.Black),
        ).build()
    }
}
