package com.familydocs.app.ui.ds
// Ported from the Android app (app/src/main/java/com/familydocs/app/ui/ds), same look; Android-only bits swapped for desktop ones.
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Generated from the demo's cardart.js. Same scene as the demo: sky gradient, (for national ID cards) the issuing
// country's flag, faint and tilted, hill 1, the type's motif image, hills 2 and 3. Colours are always the type's own.

/** National ID cards: these show the issuing country's flag faintly behind the hills unless the user turns it off
 *  (and any other document can turn it on: DocumentEntity.showFlag). */
val NATIONAL_CARD_TYPES = com.familydocs.app.domain.DocTypes.FLAG_TYPES
private const val FLAG_ALPHA = .13f

/** The flag's tilt for a card: 4–12° either way, fixed per document type + country (same hash as the demo). */
fun flagTilt(key: String, country: String): Float {
    var h = 7
    for (ch in "$key:$country") h = h * 31 + ch.code
    val u = h.toLong() and 0xFFFFFFFFL
    return ((if ((u and 1L) == 1L) 1 else -1) * (4 + (u ushr 1) % 9)).toFloat()
}

private val TYPE_PALETTES: Map<String, LongArray> = mapOf(
    "passport" to longArrayOf(0xFF4A5E84, 0xFF2E3D5E, 0xFF3A4C70, 0xFF2D3C5A, 0xFF233049),
    "visa" to longArrayOf(0xFF7E6C9C, 0xFF594A78, 0xFF685985, 0xFF54476D, 0xFF423858),
    "residence" to longArrayOf(0xFF6C948E, 0xFF47706B, 0xFF557D77, 0xFF44665F, 0xFF35524C),
    "aadhaar" to longArrayOf(0xFFB98A66, 0xFF8F6247, 0xFF9A6A4C, 0xFF7D543B, 0xFF63412E),
    "pan" to longArrayOf(0xFF5D7895, 0xFF3F5873, 0xFF4C6582, 0xFF3C516B, 0xFF2F4056),
    "voter" to longArrayOf(0xFF7F8A5C, 0xFF5A6440, 0xFF6C7650, 0xFF586140, 0xFF454D32),
    "driving" to longArrayOf(0xFFA6786A, 0xFF7C5347, 0xFF8C6052, 0xFF734C40, 0xFF5C3C32),
    "id" to longArrayOf(0xFF6A7896, 0xFF4A5672, 0xFF58657F, 0xFF475268, 0xFF384154),
    "social" to longArrayOf(0xFF6A8296, 0xFF4A6072, 0xFF58707F, 0xFF475B68, 0xFF384854),
    "insurance" to longArrayOf(0xFF6A9080, 0xFF47685A, 0xFF567A6A, 0xFF445F53, 0xFF344A40),
    "vaccine" to longArrayOf(0xFFA87A8C, 0xFF7C5466, 0xFF8F6474, 0xFF76505E, 0xFF5E3F4B),
    "medical" to longArrayOf(0xFFA3716B, 0xFF784E49, 0xFF8A5E58, 0xFF714B46, 0xFF5A3B37),
    "bank" to longArrayOf(0xFF6A6B9C, 0xFF4A4A76, 0xFF5A5B88, 0xFF48486F, 0xFF393958),
    "education" to longArrayOf(0xFF988459, 0xFF6E5E3C, 0xFF7F6E49, 0xFF68593B, 0xFF52462E),
    "certificate" to longArrayOf(0xFF9C7A62, 0xFF725642, 0xFF84654F, 0xFF6C513F, 0xFF563F31),
    "property" to longArrayOf(0xFF7C7398, 0xFF585073, 0xFF675F84, 0xFF544D6C, 0xFF423C56),
    "bill" to longArrayOf(0xFFA28C5E, 0xFF776440, 0xFF8A7650, 0xFF71603F, 0xFF5A4C31),
    "tax" to longArrayOf(0xFF5E7B8F, 0xFF41596B, 0xFF4D687B, 0xFF3E5465, 0xFF314352),
    "afmamka" to longArrayOf(0xFF5E7B8F, 0xFF41596B, 0xFF4D687B, 0xFF3E5465, 0xFF314352),
)

/** Muted colours from each country's flag: sky, sky 2, hill 1, hill 2, hill 3. */
private val MOTIFS: Map<String, String> = mapOf(
    "passport" to "card_m_passport",
    "visa" to "card_m_visa",
    "residence" to "card_m_residence",
    "aadhaar" to "card_m_aadhaar",
    "pan" to "card_m_pan",
    "voter" to "card_m_voter",
    "driving" to "card_m_driving",
    "id" to "card_m_id",
    "social" to "card_m_social",
    "insurance" to "card_m_insurance",
    "vaccine" to "card_m_vaccine",
    "medical" to "card_m_medical",
    "bank" to "card_m_bank",
    "education" to "card_m_education",
    "certificate" to "card_m_certificate",
    "property" to "card_m_property",
    "bill" to "card_m_bill",
    "tax" to "card_m_tax",
    "afmamka" to "card_m_tax",
)

/** [sky, sky2, hill1, hill2, hill3] for a card type. */
fun cardPalette(key: String, hue: Long): List<Color> {
    val p = TYPE_PALETTES[key]
    if (p != null) return p.map { Color(it) }
    // Unknown types: plain hills in a muted version of the type's colour (demo fallbackPalette).
    val m = lerp(Color(hue), Color(0xFF8A8F99), .38f)
    fun shade(a: Float) = if (a >= 0) lerp(m, Color.White, a) else lerp(m, Color.Black, -a)
    return listOf(shade(.12f), shade(-.22f), shade(-.08f), shade(-.24f), shade(-.38f))
}

/** The illustrated background of a wallet card (demo `cardSceneSVG`), drawn at any size. */
@Composable
fun CardScene(key: String, hue: Long, country: String?, modifier: Modifier = Modifier, showFlag: Boolean = key in NATIONAL_CARD_TYPES) {
    val p = cardPalette(key, hue)
    val motif = MOTIFS[key]
    // Full flags (cflag_xx, 4:3), tilted per card and scaled just enough to keep the card filled (demo flagPose).
    val flagName = if (showFlag && !country.isNullOrEmpty()) "cflag_" + country.lowercase() else null
    val flag = flagName?.takeIf { Res.has(it) }?.let { fdPainter(it) }
    val tilt = remember(key, country) { flagTilt(key, country.orEmpty().uppercase()) }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.linearGradient(listOf(p[0], p[1]), Offset(0f, 0f), Offset(size.width * .35f, size.height)))
            if (flag != null) {
                val fw = size.width; val fh = fw * .75f
                val a = Math.toRadians(abs(tilt).toDouble()); val c = cos(a).toFloat(); val sn = sin(a).toFloat()
                val sc = maxOf((fw / 2 * c + size.height / 2 * sn) / (fw / 2), (fw / 2 * sn + size.height / 2 * c) / (fh / 2))
                withTransform({
                    rotate(tilt, center); scale(sc, sc, center); translate(0f, (size.height - fh) / 2f)
                }) { with(flag) { draw(Size(fw, fh), alpha = FLAG_ALPHA) } }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val k = maxOf(size.width / 160f, size.height / 101f) // preserveAspectRatio xMidYMid slice
            val dx = (size.width - 160f * k) / 2f; val dy = (size.height - 101f * k) / 2f
            fun X(x: Float) = dx + x * k
            fun Y(y: Float) = dy + y * k
            drawPath(hill(0, ::X, ::Y), p[2])
        }
        // A passport card has its dates and machine-readable lines lower down: its booklet sits higher and a bit smaller.
        if (motif != null) Image(fdPainter(motif), null, Modifier.fillMaxSize().then(if (key == "passport") Modifier.graphicsLayer {
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(124f / 160f, 63f / 101f); scaleX = .8f; scaleY = .8f; translationY = -size.height * .08f
        } else Modifier), contentScale = ContentScale.Crop)
        Canvas(Modifier.fillMaxSize()) {
            val k = maxOf(size.width / 160f, size.height / 101f)
            val dx = (size.width - 160f * k) / 2f; val dy = (size.height - 101f * k) / 2f
            fun X(x: Float) = dx + x * k
            fun Y(y: Float) = dy + y * k
            drawPath(hill(1, ::X, ::Y), p[3])
            drawPath(hill(2, ::X, ::Y), p[4])
        }
    }
}

// The demo's three hills, "M0 y0 Q c1 e1 T 160 y2 V101 H0 Z" with the T control point reflected.
private val HILLS = listOf(
    floatArrayOf(68f, 40f, 56f, 80f, 64f, 58f),
    floatArrayOf(80f, 45f, 70f, 95f, 78f, 73f),
    floatArrayOf(90f, 50f, 83f, 100f, 88f, 85f),
)

private fun hill(i: Int, X: (Float) -> Float, Y: (Float) -> Float): Path {
    val h = HILLS[i]
    val y0 = h[0]; val cx = h[1]; val cy = h[2]; val ex = h[3]; val ey = h[4]; val y2 = h[5]
    return Path().apply {
        moveTo(X(0f), Y(y0))
        quadraticBezierTo(X(cx), Y(cy), X(ex), Y(ey))
        quadraticBezierTo(X(2 * ex - cx), Y(2 * ey - cy), X(160f), Y(y2))
        lineTo(X(160f), Y(101f)); lineTo(X(0f), Y(101f)); close()
    }
}
