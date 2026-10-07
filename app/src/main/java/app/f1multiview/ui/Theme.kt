package app.f1multiview.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun F1Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFE10600),
            onPrimary = Color.White,
            secondary = Color(0xFFB8BBC4),
            background = Color(0xFF0B0B10),
            onBackground = Color(0xFFF5F5F7),
            surface = Color(0xFF14151B),
            onSurface = Color(0xFFF5F5F7),
            surfaceVariant = Color(0xFF1C1D24),
            onSurfaceVariant = Color(0xFFB4B5BE),
            outline = Color(0xFF45464F)
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(30.dp)
        ),
        content = content
    )
}
