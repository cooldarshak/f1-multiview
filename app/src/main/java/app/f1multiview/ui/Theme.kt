package app.f1multiview.ui
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
@Composable fun F1Theme(content: @Composable () -> Unit){
 MaterialTheme(colorScheme=darkColorScheme(background=Color(0xFF06070A),surface=Color(0xFF111318),surfaceVariant=Color(0xFF191B21),primary=Color(0xFFE10600),secondary=Color(0xFFBFC4CF)),content=content)
}
