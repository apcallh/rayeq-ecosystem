package app.rayeq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Composable
fun AmbientBackground(
    style: String = "gradient",
    ambientTint: Color = MaterialTheme.colorScheme.primary,
    content: @Composable BoxScope.() -> Unit
) {
    val bg = MaterialTheme.colorScheme.background
    val modifier = when (style) {
        "solid" -> Modifier.fillMaxSize().background(bg)
        "ambient" -> Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(ambientTint.copy(alpha = 0.22f), bg, bg)
            )
        )
        else -> Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(MaterialTheme.colorScheme.surface, bg)
            )
        )
    }
    Box(modifier = modifier, content = content)
}
