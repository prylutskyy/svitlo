package ua.svitlo.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal val Day = lightColorScheme(
    primary = Color(0xFF245E46), onPrimary = Color.White,
    primaryContainer = Color(0xFFC8EED5), onPrimaryContainer = Color(0xFF103B28),
    secondary = Color(0xFF55634C), secondaryContainer = Color(0xFFE0EBCF), onSecondaryContainer = Color(0xFF26351E),
    tertiary = Color(0xFF745B00), tertiaryContainer = Color(0xFFFFE79B), onTertiaryContainer = Color(0xFF443400),
    background = Color(0xFFF6F8F1), onBackground = Color(0xFF191D18),
    surface = Color(0xFFF6F8F1), onSurface = Color(0xFF191D18),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3EB),
    surfaceContainer = Color(0xFFEAEFE5), surfaceContainerHigh = Color(0xFFE3E9DE), surfaceContainerHighest = Color(0xFFDBE3D6),
    onSurfaceVariant = Color(0xFF434C43), outline = Color(0xFF737D72), outlineVariant = Color(0xFFC2CCBF),
    error = Color(0xFF9D2828), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF6D1217),
)
internal val Night = darkColorScheme(
    primary = Color(0xFFA0D3B3), onPrimary = Color(0xFF073822),
    primaryContainer = Color(0xFF254F39), onPrimaryContainer = Color(0xFFC2EFCF),
    secondary = Color(0xFFBECBAE), secondaryContainer = Color(0xFF3D4934), onSecondaryContainer = Color(0xFFDAE8C9),
    tertiary = Color(0xFFE8C95A), tertiaryContainer = Color(0xFF584500), onTertiaryContainer = Color(0xFFFFE79B),
    background = Color(0xFF10150F), onBackground = Color(0xFFE0E6DA),
    surface = Color(0xFF10150F), onSurface = Color(0xFFE0E6DA),
    surfaceContainerLowest = Color(0xFF0B100A), surfaceContainerLow = Color(0xFF191F18),
    surfaceContainer = Color(0xFF1D241C), surfaceContainerHigh = Color(0xFF283026), surfaceContainerHighest = Color(0xFF333C30),
    onSurfaceVariant = Color(0xFFC2CCBC), outline = Color(0xFF8C9788), outlineVariant = Color(0xFF424C3F),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF732020), onErrorContainer = Color(0xFFFFDAD6),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BaseTheme(scheme: ColorScheme, content: @Composable () -> Unit) {
    val base = Typography()
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(),
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp)),
        typography = base.copy(headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold)), content = content)
}
