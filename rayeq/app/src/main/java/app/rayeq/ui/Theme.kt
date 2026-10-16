package app.rayeq.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import app.rayeq.data.AppSettings

data class RayeqPalette(
    val id: String,
    val nameAr: String,
    val background: Color,
    val surface: Color,
    val primary: Color,
    val secondary: Color
)

val PALETTES = listOf(
    RayeqPalette("ink", "حبر", Color(0xFF0A0F1D), Color(0xFF131B2E), Color(0xFF38BDF8), Color(0xFF818CF8)),
    RayeqPalette("emerald", "زمرّد", Color(0xFF071712), Color(0xFF0F261E), Color(0xFF10B981), Color(0xFF34D399)),
    RayeqPalette("ruby", "ياقوت", Color(0xFF190A10), Color(0xFF28121B), Color(0xFFF43F5E), Color(0xFFFB7185)),
    RayeqPalette("violet", "بنفسج", Color(0xFF110C1D), Color(0xFF1C1430), Color(0xFFA855F7), Color(0xFFC084FC)),
    RayeqPalette("sand", "رمل", Color(0xFF18140E), Color(0xFF262016), Color(0xFFF59E0B), Color(0xFFFBBF24)),
    RayeqPalette("charcoal", "فحم", Color(0xFF0F1115), Color(0xFF181B20), Color(0xFF94A3B8), Color(0xFFCBD5E1))
)

// الدرس المستفاد رقم 25: لكل نمط ارتفاع سطر صريح مع LineHeightStyle(Center, Trim.None) حتى لا يُقص الخط العربي رأسياً
private val arabicLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

val RayeqTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 36.sp,
        lineHeightStyle = arabicLineHeightStyle
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 30.sp,
        lineHeightStyle = arabicLineHeightStyle
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 25.sp,
        lineHeightStyle = arabicLineHeightStyle
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        lineHeightStyle = arabicLineHeightStyle
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        lineHeightStyle = arabicLineHeightStyle
    )
)

@Composable
fun RayeqTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val pal = PALETTES.find { it.id == settings.palette } ?: PALETTES.first()
    val primaryCol = if (settings.palette == "custom") Color(settings.customAccentArgb) else pal.primary
    val bgCol = if (settings.amoled) Color.Black else pal.background

    val scheme = darkColorScheme(
        primary = primaryCol,
        secondary = pal.secondary,
        background = bgCol,
        surface = pal.surface,
        onBackground = Color(0xFFEEF2FF),
        onSurface = Color(0xFFE2E8F0)
    )

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = scheme,
            typography = RayeqTypography,
            content = content
        )
    }
}
