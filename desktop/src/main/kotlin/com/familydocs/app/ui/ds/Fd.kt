package com.familydocs.app.ui.ds
// Ported from the Android app (app/src/main/java/com/familydocs/app/ui/ds), same look; Android-only bits swapped for desktop ones.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The prototype's design tokens, 1:1 (proto/part1.html --a-* variables).
 * Every screen reads colours from [Fd.c] so the app and the demo stay identical in both themes.
 */
@Immutable
data class FdColors(
    val dark: Boolean,
    val bg: Color, val surface: Color, val surface2: Color, val fg: Color, val muted: Color, val line: Color,
    val primary: Color, val onPrimary: Color, val accent: Color, val accentSoft: Color, val danger: Color, val ok: Color,
    /** how much of a document's hue its icon uses (CSS --a-hue-mix) */
    val hueMix: Float,
    /** how much of a document's hue its tile background uses (CSS --a-tile-mix) */
    val tileMix: Float,
) {
    /** CSS color-mix(in srgb, [a] p, [b]) */
    fun mix(a: Color, p: Float, b: Color): Color = lerp(b, a, p)
    fun tileBg(hue: Color) = mix(hue, tileMix, surface)
    fun tileFg(hue: Color) = mix(hue, hueMix, fg)
    /** keypad key fill: color-mix(fg 8%, bg) */
    fun key(p: Float = .08f) = mix(fg, p, bg)
}

val LightColors = FdColors(
    dark = false,
    bg = Color(0xFFF4F5F9), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFECEEF4), fg = Color(0xFF141B2B), muted = Color(0xFF667085),
    line = Color(0xFFE2E5ED), primary = Color(0xFF1E2F55), onPrimary = Color.White, accent = Color(0xFFE3962E), accentSoft = Color(0xFFFBEBD3),
    danger = Color(0xFFC2453D), ok = Color(0xFF2E8B57), hueMix = 1f, tileMix = .15f,
)

val DarkColors = FdColors(
    dark = true,
    bg = Color(0xFF121213), surface = Color(0xFF1C1C1E), surface2 = Color(0xFF2A2A2D), fg = Color(0xFFEEEEF0), muted = Color(0xFF9D9DA3),
    line = Color(0xFF333336), primary = Color(0xFFE6E6E9), onPrimary = Color(0xFF121213), accent = Color(0xFFEEB056), accentSoft = Color(0xFF3A2D16),
    danger = Color(0xFFE9786F), ok = Color(0xFF5CC28A), hueMix = .62f, tileMix = .26f,
)

/** Abaddon (CC BY 3.0): light for normal text, bold from medium up, as in the demo's @font-face ranges. */
/** The app's name in the header: Bitcount Grid Double (OFL), a static weight-500 cut. */
val Bitcount = FontFamily(Font("font/bitcount_grid_double.ttf", FontWeight.Normal))

val Abaddon = FontFamily(
    Font("font/abaddon_light.ttf", FontWeight.Light),
    Font("font/abaddon_light.ttf", FontWeight.Normal),
    Font("font/abaddon_bold.ttf", FontWeight.Medium),
    Font("font/abaddon_bold.ttf", FontWeight.SemiBold),
    Font("font/abaddon_bold.ttf", FontWeight.Bold),
)

val LocalFd = staticCompositionLocalOf { LightColors }

object Fd {
    val c: FdColors @Composable get() = LocalFd.current
}

/** px in the demo == sp/dp here. */
/**
 * Size actually used on the phone for a design size: the pixel font reads small, so captions and labels (9–13)
 * are raised the most (11 → 13.3, 12 → 13.8), body text a little, and headings (16+) stay as designed.
 * Still in sp, so the phone's own font-size setting applies on top. The demo uses the same curve.
 */
fun readableSize(size: Float): Float = if (size >= 16f) size else size + (16f - size) * .45f

fun fdText(size: Float, weight: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified, spacing: Float = 0f, lineHeight: Float? = null): TextStyle {
    val s = readableSize(size)
    return TextStyle(fontFamily = Abaddon, fontSize = s.sp, fontWeight = weight, color = color, letterSpacing = (spacing * s).sp,
        lineHeight = (lineHeight?.let { it * s / size } ?: (s * 1.35f)).sp)
}

/**
 * Wraps the whole app: provides the tokens, sets the default text style/colour (this is what keeps
 * text readable in dark mode) and paints the background.
 */
@Composable
fun FdTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val m3 = if (dark) darkColorScheme(
        primary = c.primary, onPrimary = c.onPrimary, secondary = c.accent, onSecondary = Color(0xFF1A1205),
        background = c.bg, onBackground = c.fg, surface = c.surface, onSurface = c.fg, surfaceVariant = c.surface2,
        onSurfaceVariant = c.muted, outline = c.line, outlineVariant = c.line, error = c.danger,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerLow = c.surface, surfaceContainerHighest = c.surface2,
    ) else lightColorScheme(
        primary = c.primary, onPrimary = c.onPrimary, secondary = c.accent, onSecondary = Color(0xFF1A1205),
        background = c.bg, onBackground = c.fg, surface = c.surface, onSurface = c.fg, surfaceVariant = c.surface2,
        onSurfaceVariant = c.muted, outline = c.line, outlineVariant = c.line, error = c.danger,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerLow = c.surface, surfaceContainerHighest = c.surface2,
    )
    CompositionLocalProvider(LocalFd provides c) {
        MaterialTheme(colorScheme = m3) {
            CompositionLocalProvider(
                LocalContentColor provides c.fg,
                LocalTextStyle provides fdText(14f, color = c.fg),
                LocalTextSelectionColors provides TextSelectionColors(c.primary, c.primary.copy(alpha = .3f)),
            ) {
                Box(Modifier.fillMaxSize().background(c.bg)) { content() }
            }
        }
    }
}

// ---------------- icons ----------------

private val iconCache = HashMap<String, ImageVector>()

/** One of the demo's icons by name (see [ICON_PATHS]); [stroke] is in 24-unit viewBox units, like the demo's ic(name, size, sw). */
fun fdIcon(name: String, stroke: Float = 1.8f): ImageVector = iconCache.getOrPut("$name/$stroke") {
    val paths = ICON_PATHS[name] ?: ICON_PATHS.getValue("file")
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { (d, filled) ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = stroke, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}

@Composable
fun FdIcon(name: String, size: Dp = 22.dp, stroke: Float = 1.8f, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier, description: String? = null) {
    val v = remember(name, stroke) { fdIcon(name, stroke) }
    Icon(v, description, modifier.size(size), tint = tint)
}
