package com.familydocs.app.ui.ds
// Ported from the Android app (app/src/main/java/com/familydocs/app/ui/ds), same look; Android-only bits swapped for desktop ones.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.spring
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.familydocs.app.data.DisplayMode
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.domain.Countries
import com.familydocs.app.domain.DocIcon
import com.familydocs.app.domain.DocTypes
import com.familydocs.app.domain.maskValue
import com.familydocs.app.ui.components.rememberVaultImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- characters (the demo's illustrated people) ----------------

/** Default pictures, rendered from the demo's faceSVG so they are identical. Keys are stored in MemberEntity.avatar. */
val CHARACTERS: List<Pair<String, String>> = listOf(
    "char_blue_short" to "char_blue_short",
    "char_peach_bun" to "char_peach_bun",
    "char_lilac_long" to "char_lilac_long",
    "char_mint_glasses" to "char_mint_glasses",
    "char_sand_bun" to "char_sand_bun",
    "char_rose_long" to "char_rose_long",
    "char_sky_short" to "char_sky_short",
    "char_slate_glasses" to "char_slate_glasses",
)

fun characterRes(key: String?): String? = CHARACTERS.firstOrNull { it.first == key }?.second

// ---------------- event pictures (the demo's ART, rendered to images) ----------------

/** Key (stored in EventEntity.art), label, picture. Same order as the demo's EVENT_ARTS. */
val EVENT_ARTS: List<Triple<String, String, String>> = listOf(
    Triple("mountain", "Mountains", "art_mountain"),
    Triple("beach", "Beach", "art_beach"),
    Triple("heritage", "Heritage", "art_heritage"),
    Triple("desert", "Desert", "art_desert"),
    Triple("jungle", "Jungle safari", "art_jungle"),
    Triple("trip", "Trip", "art_trip"),
    Triple("birthday", "Birthday", "art_birthday"),
    Triple("appointment", "Appointment", "art_appointment"),
    Triple("health", "Doctor", "art_health"),
    Triple("anniversary", "Anniversary", "art_anniversary"),
    Triple("festival", "Festival", "art_festival"),
)

fun artRes(key: String?): String = EVENT_ARTS.firstOrNull { it.first == key }?.third ?: "art_trip"

/** Round event picture: the user's photo if there is one, otherwise the event's illustration. */
@Composable
fun FdEventPic(art: String, photoFile: String?, size: Dp, modifier: Modifier = Modifier, lift: Boolean = false) {
    val photo = rememberVaultImage(photoFile, 700)
    val base = modifier.size(size).then(if (lift) Modifier.shadow(10.dp, CircleShape, ambientColor = Color(0x730A0F1E), spotColor = Color(0x730A0F1E)) else Modifier).clip(CircleShape)
    if (photo != null) Image(photo, null, base, contentScale = ContentScale.Crop)
    else Image(fdPainter(artRes(art)), null, base, contentScale = ContentScale.Crop)
}

/** The demo's fallback hues for initials avatars and unknown document types. */
private val FALLBACK_HUES = listOf(0xFF4B6BAF, 0xFFA0573C, 0xFF2F7F7A, 0xFF7A4FB5, 0xFF8A6D1F, 0xFFB04A6E, 0xFF3F7C4B, 0xFF5868A8)

/** FNV-1a, same as the demo's hash(), so the same name gets the same colour. */
fun fnv(s: String): Long {
    var h = 2166136261L
    for (ch in s) { h = h xor ch.code.toLong(); h = (h * 16777619L) and 0xFFFFFFFFL }
    return h
}

/**
 * `.av`: circular picture. Order: chosen photo → shared house icon → illustrated character → coloured initials.
 * [lift] adds the demo's soft drop shadow (`.ring .av`).
 */
@Composable
fun FdAvatar(
    name: String, size: Dp, modifier: Modifier = Modifier, photoFile: String? = null, character: String? = null,
    shared: Boolean = false, lift: Boolean = false,
) {
    val c = Fd.c
    val photo: ImageBitmap? = rememberVaultImage(photoFile, 700)
    val base = modifier.size(size).then(if (lift) Modifier.shadow(10.dp, CircleShape, ambientColor = Color(0x730A0F1E), spotColor = Color(0x730A0F1E)) else Modifier).clip(CircleShape)
    when {
        photo != null -> Image(photo, name, base.background(c.surface2), contentScale = ContentScale.Crop)
        shared -> Image(fdPainter("art_home"), name, base, contentScale = ContentScale.Crop)
        characterRes(character) != null -> Image(fdPainter(characterRes(character)!!), name, base, contentScale = ContentScale.Crop)
        else -> {
            val n = name.trim().ifEmpty { "?" }
            val ini = n.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
            Box(base.background(Color(FALLBACK_HUES[(fnv(n) % FALLBACK_HUES.size).toInt()])), contentAlignment = Alignment.Center) {
                Text(ini, style = fdText(size.value * .38f, color = Color.White))
            }
        }
    }
}

// ---------------- flags ----------------

/** Same flag artwork as the demo (flag-icons, rendered to small images). 3:2 with 3px corners and a hairline edge. */
@Composable
fun FdFlag(code: String, width: Dp = 22.dp, ring: Color = Color(0x1F000000), greyscale: Boolean = false) {
    if (code.isEmpty()) return
    val id = "flag_" + code.lowercase()
    val shape = RoundedCornerShape(3.dp)
    val m = Modifier.size(width, width * 2 / 3).border(1.dp, ring, shape).clip(shape).semantics { contentDescription = Countries.name(code) }
    if (Res.has(id)) Image(fdPainter(id), null, m, contentScale = ContentScale.Crop,
        colorFilter = if (greyscale) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null)
    else Box(m.background(Color(0x33808080)), contentAlignment = Alignment.Center) { Text(code, style = fdText(9f, FontWeight.Bold)) }
}

// ---------------- document icons ----------------

fun DocIcon.demoName(): String = when (this) {
    DocIcon.HOUSE -> "house"; DocIcon.BOLT -> "bolt"; DocIcon.FINGERPRINT -> "finger"; DocIcon.CARD -> "card"; DocIcon.PASSPORT -> "passport"
    DocIcon.PLANE -> "plane"; DocIcon.VISA -> "visa"; DocIcon.RESIDENCE -> "homeid"; DocIcon.CAR -> "car"; DocIcon.BALLOT -> "ballot"; DocIcon.SHIELD -> "shield"
    DocIcon.SYRINGE -> "syringe"; DocIcon.BANK -> "bank"; DocIcon.CAP -> "cap"; DocIcon.CERTIFICATE -> "cert"; DocIcon.MEDICAL -> "medical"
    DocIcon.RECEIPT -> "receipt"; DocIcon.ID -> "idcard"; DocIcon.FILE -> "file"; DocIcon.KEY -> "key"; DocIcon.PEOPLE -> "people"
    DocIcon.TICKET -> "ticket"; DocIcon.BED -> "bed"; DocIcon.TRAIN -> "train"
    DocIcon.BIKE -> "bike"; DocIcon.FOOD -> "food"; DocIcon.DRINKS -> "drinks"
}

/** `.dicon`: hue-tinted rounded tile with the type icon (or the chosen picture), initials badge for unknown types. */
@Composable
fun FdDocTile(name: String, size: Dp = 54.dp, picture: ImageBitmap? = null, iconOverride: String? = null, hueOverride: Long? = null) {
    val c = Fd.c
    val t = DocTypes.forName(name)
    val hue = Color(hueOverride ?: t.hue)
    val radius = if (size >= 80.dp) 24.dp else if (size <= 44.dp) 13.dp else 16.dp
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(c.tileBg(hue)), contentAlignment = Alignment.Center) {
        if (picture != null) Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            FdIcon(iconOverride ?: t.icon.demoName(), if (size >= 80.dp) 38.dp else size * .48f, 1.7f, c.tileFg(hue))
            if (!t.matched && t.initials.isNotEmpty() && iconOverride == null) Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(c.surface).padding(horizontal = 3.dp),
            ) { Text(t.initials, style = fdText(9f, FontWeight.Bold, lineHeight = 14f)) }
        }
    }
}

@Composable
fun FdDocIcon(doc: DocumentEntity, size: Dp = 54.dp) {
    val pic = if (doc.display != DisplayMode.ICON) rememberVaultImage(doc.pictureFile, 400) else null
    FdDocTile(doc.name, size, pic)
}

/** Value of the field that means [key] ("subject", "specialisation", "degree"…), if filled. */
fun DocumentEntity.fieldValue(key: String): String? =
    fields.firstOrNull { com.familydocs.app.ocr.FieldKeys.canonical(it.label) == key && it.value.isNotBlank() }?.value?.trim()

/** The document's main number, shown in full (e.g. "9649 3578 0824"); otherwise its first filled detail. Degrees: the year. */
fun DocumentEntity.tail(): String {
    if (DocTypes.forName(name).key == "education") fieldValue("year of passing")?.let { return it }
    // AFM & AMKA: both numbers on the card
    if (DocTypes.forName(name).key == "afmamka") afmAmka().takeIf { it.isNotEmpty() }?.let { return it.joinToString("  ·  ") { (l, v) -> "$l $v" } }
    val f = fields.firstOrNull { it.sensitive && it.value.isNotBlank() } ?: fields.firstOrNull { it.value.isNotBlank() }
    return when { f == null -> "No details yet"; f.sensitive -> f.value.trim(); else -> f.value.take(14) }
}

/** The AFM and AMKA numbers of an "AFM & AMKA" document, labelled, in that order (whichever are filled). */
fun DocumentEntity.afmAmka(): List<Pair<String, String>> {
    fun get(re: String) = fields.firstOrNull { Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(it.label) && it.value.isNotBlank() }?.value?.trim()
    return listOfNotNull(get("\\bafm\\b|ΑΦΜ|tax")?.let { "AFM" to it }, get("\\bamka\\b|ΑΜΚΑ|social")?.let { "AMKA" to it })
}

fun DocumentEntity.validUntil(): String? = fieldValue("expiry")

/**
 * Dates printed on the wallet card: the expiry date if there is one, otherwise the issue date; a passport shows both.
 * Nothing when neither is filled in (no "Added" date).
 */
fun DocumentEntity.cardDates(): List<Pair<String, String>> {
    val expiry = fieldValue("expiry")?.let { "VALID UNTIL" to it }
    val issued = fieldValue("issued")?.let { "ISSUED" to it }
    return if (DocTypes.forName(name).key == "passport") listOfNotNull(issued, expiry) else listOfNotNull(expiry ?: issued)
}

fun DocumentEntity.holder(fallback: String): String {
    fun get(re: String) = fields.firstOrNull { Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(it.label) && it.value.isNotBlank() }?.value
    return get("^name$|insured|elector") ?: listOfNotNull(get("given"), get("surname")).joinToString(" ").ifBlank { fallback }
}

fun fmtDate(t: Long): String = SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date(t))

// ---------------- wallet cards ----------------

/** Minimal card templates per kind of document (demo `CARD_TPL`). */
private enum class CardTpl { PASSPORT, ID, VISA, PAPER, MEMBER, PLAIN }

private fun cardTpl(key: String): CardTpl = when (key) {
    "passport" -> CardTpl.PASSPORT
    "aadhaar", "pan", "residence", "driving", "voter", "id" -> CardTpl.ID
    else -> CardTpl.PLAIN // every card now sits on its illustration; only passports and ID cards change layout
}

/** Passport-style machine-readable lines, built from the holder and the masked number (never the full number). */
private fun mrzLines(holder: String, cc: String, masked: String): Pair<String, String> {
    val name = holder.uppercase().replace(Regex("[^A-Z]+"), "<")
    fun pad(x: String) = (x + "<".repeat(44)).take(44)
    return pad("P<${cc.ifEmpty { "XXX" }}<$name") to pad("<<<<<${masked.filter(Char::isDigit)}<$cc")
}

/**
 * `.wcard`: credit-card shaped document (1.586:1) in one of the minimal templates:
 * passport (line globe + machine-readable lines), ID card (photo box + fields), visa (guilloche waves + stamp),
 * paper (light sheet, colour edge, folded corner, text lines), member card (big faint icon) or plain.
 */
@Composable
fun FdWalletCard(
    doc: DocumentEntity, holderFallback: String, open: Boolean, modifier: Modifier = Modifier,
    photoFile: String? = null, character: String? = null, shared: Boolean = false,
    /** Cast a shadow up onto the card behind it (every card in a stack but the first). */
    shadeAbove: Boolean = false,
    /** 1 = normal; lower fades the card towards the background (the others while one is lifted). Shadows stay. */
    /** (Lambdas: they animate, and are read only while drawing, so the card doesn't recompose every frame.) */
    dim: () -> Float = { 1f },
    /** How strong that shadow is: it fades out (smoothly) where the card sits in a gap rather than on another card. */
    shadeStrength: () -> Float = { 1f },
) {
    val c = Fd.c
    val t = DocTypes.forName(doc.name)
    val hue = Color(t.hue)
    val deep = lerp(Color(0xFF0B1020), hue, .55f)
    val glow = lerp(Color.White, hue, .55f)
    val pic = if (doc.display != DisplayMode.ICON) rememberVaultImage(doc.pictureFile, 900) else null
    val tpl = if (pic != null) CardTpl.PLAIN else cardTpl(t.key)
    val paper = tpl == CardTpl.PAPER
    val ink = if (paper) c.mix(hue, .45f, c.fg) else Color.White
    val sheet = c.mix(hue, .10f, c.surface)
    val shadow by animateDpAsState(if (open) 22.dp else 14.dp, tween(350, easing = FdEase), label = "cardShadow")
    // The lifted (selected) card gets a soft light rim.
    val rim by animateFloatAsState(if (open) 1f else 0f, tween(300, easing = FdEase), label = "cardRim")
    val shape = RoundedCornerShape(18.dp)
    val dates = doc.cardDates()
    val holder = doc.holder(holderFallback)

    BoxWithConstraints(
        modifier.fillMaxWidth()
            // Elevation shadows fall downwards, where the next card covers them; so each card also darkens the strip
            // of the card behind it, just above its own top edge. Drawn by hand: it looks the same on every phone.
            .then(if (shadeAbove) Modifier.drawBehind {
                // Exactly as wide as the cards (no inset, no corners of its own), so its sides line up with the card
                // edges; it runs on under this card's rounded top corners, where the card behind shows through.
                val h = 26.dp.toPx(); val r = 18.dp.toPx()
                val k = shadeStrength()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, .55f to Color.Black.copy(alpha = (if (c.dark) .32f else .16f) * k),
                    1f to Color.Black.copy(alpha = (if (c.dark) .62f else .38f) * k), startY = -h, endY = r),
                    topLeft = Offset(0f, -h), size = androidx.compose.ui.geometry.Size(size.width, h + r))
            } else Modifier)
            .shadow(shadow, shape, ambientColor = Color(0xB3000000), spotColor = Color(0xCC000000)).clip(shape)
            // Fading with a veil on top, not transparency: a see-through layer would cut off the shadow drawn above the
            // card, so it would vanish while another card is lifted and snap back after.
            .drawWithContent { drawContent(); val d = dim(); if (d < 1f) drawRect(c.bg.copy(alpha = 1f - d)) }
            // The card itself (art, flag, text) is recorded once in its own layer; fading or moving it only replays it.
            .graphicsLayer()
            .border(2.dp, (if (paper) hue else Color.White).copy(alpha = (if (paper) .7f else .75f) * rim), shape)
            .then(if (paper) Modifier.background(sheet).border(1.dp, hue.copy(alpha = .22f), shape)
            else Modifier.background(Brush.linearGradient(listOf(hue, deep), Offset.Zero, Offset(900f, 1100f)))),
    ) {
        val w = maxWidth
        val wPx = with(LocalDensity.current) { w.toPx() }
        // ---- background art ----
        when {
            pic != null -> {
                Image(pic, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to lerp(Color.Black, hue, .88f), .22f to lerp(Color.Black, hue, .88f), .45f to Color(0x40050A19), 1f to Color(0xC7050A19))))
            }
            else -> {
                // `.wcard.art`: sky, hills and the type's motif; national documents in their country's flag colours.
                CardScene(t.key, t.hue, doc.issuer.takeIf { it.isNotEmpty() }, Modifier.fillMaxSize(), showFlag = doc.flagShown)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                    if (c.dark) listOf(Color(0x42000000), Color(0x1A000000)) else listOf(Color(0x290A0F1E), Color.Transparent))))
            }
        }

        // ---- content ----
        // AFM & AMKA cards carry both numbers: stacked in the strip, side by side on the card.
        val nums = if (t.key == "afmamka") doc.afmAmka() else emptyList()
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                FdIcon(t.icon.demoName(), 20.dp, tint = ink)
                Text(doc.name, style = fdText(15f, FontWeight.SemiBold, ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (nums.size == 2) Column(Modifier.widthIn(max = w * .5f), horizontalAlignment = Alignment.End) {
                    nums.forEach { (l, v) -> Text("$l  $v", style = fdText(10.5f, color = ink.copy(alpha = .88f), lineHeight = 13f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                else Text(doc.tail(), style = fdText(11.5f, color = ink.copy(alpha = .85f)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = w * .45f))
                if (doc.issuer.isNotEmpty()) FdFlag(doc.issuer, 24.dp, ring = if (paper) Color(0x1F000000) else Color.White.copy(alpha = .55f))
            }
            when (tpl) {
                CardTpl.ID -> {
                    Spacer(Modifier.weight(1f))
                    // No holder photo on the card: holder, number and validity only.
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        CardLabel("HOLDER", holder.uppercase(), ink)
                        Text(doc.tail(), style = fdText(16f, color = ink, spacing = .06f), maxLines = 1)
                        dates.forEach { (l, v) -> CardLabel(l, v.uppercase(), ink) }
                    }
                }
                CardTpl.PAPER -> {
                    Column(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(.62f, .50f, .36f).forEach { f -> Box(Modifier.fillMaxWidth(f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(ink.copy(alpha = .13f))) }
                    }
                    Spacer(Modifier.weight(1f))
                    CardBottom(holder, dates, open, ink)
                }
                CardTpl.PASSPORT -> {
                    Spacer(Modifier.weight(1f))
                    CardBottom(holder, dates, open, ink)
                    val (l1, l2) = mrzLines(holder, doc.issuer, doc.tail())
                    Column(Modifier.padding(top = 9.dp).alpha(.55f)) {
                        Text(l1, style = fdText(10f, color = ink, spacing = .14f, lineHeight = 14.5f), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                        Text(l2, style = fdText(10f, color = ink, spacing = .14f, lineHeight = 14.5f), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                    }
                }
                else -> {
                    Spacer(Modifier.weight(1f))
                    val subject = doc.fieldValue("subject")
                    if (nums.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        nums.forEach { (l, v) ->
                            Column {
                                Text(l, style = fdText(10f, FontWeight.Medium, ink.copy(alpha = .75f), spacing = .1f))
                                Text(v, style = fdText(17f, color = ink, spacing = .05f), maxLines = 1)
                            }
                        }
                    }
                    else if (t.key == "education" && subject != null) {
                        // Degree cards: the degree, then subject and specialisation instead of a number.
                        doc.fieldValue("degree")?.let { Text(it, style = fdText(12.5f, color = ink.copy(alpha = .85f)), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Text(subject, style = fdText(19f, FontWeight.SemiBold, ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        doc.fieldValue("specialisation")?.let { Text(it, style = fdText(13.5f, color = ink.copy(alpha = .9f)), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else Text(doc.tail(), style = fdText(19f, color = ink, spacing = .06f))
                    Box(Modifier.padding(top = 8.dp)) { CardBottom(holder, dates, open, ink) }
                }
            }
        }
        // `.wopen`: shown on the lifted card, bottom-right, over every template.
        // Just under the header row, so the card's dates at the bottom stay visible while it's lifted.
        androidx.compose.animation.AnimatedVisibility(open, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 50.dp),
            enter = fadeIn(tween(250, 200)), exit = fadeOut(tween(120))) {
            Box(Modifier.clip(RoundedCornerShape(99.dp)).background(if (paper) hue.copy(alpha = .16f) else Color.White.copy(alpha = .22f)).padding(horizontal = 9.dp, vertical = 3.dp)) {
                Text("Tap again to open", style = fdText(10.5f, color = ink))
            }
        }
    }
}

/** `.wbot`: Holder on the left, the card's dates on the right. */
@Composable
private fun CardBottom(holder: String, dates: List<Pair<String, String>>, open: Boolean, ink: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        CardLabel("HOLDER", holder.uppercase(), ink, Modifier.weight(1f, fill = false))
        if (dates.isNotEmpty()) Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            dates.forEach { (l, v) -> CardLabel(l, v.uppercase(), ink, end = true) }
        }
    }
}

@Composable
private fun CardLabel(label: String, value: String, ink: Color, modifier: Modifier = Modifier, end: Boolean = false) {
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(label, style = fdText(9f, FontWeight.Medium, ink.copy(alpha = .75f), spacing = .08f))
        Text(value, style = fdText(12f, FontWeight.SemiBold, ink, spacing = .03f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `.wemb`: thin-line globe (circle, two meridians, equator, two parallels), white at 17%. */
@Composable
private fun CardGlobe(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier.alpha(.17f)) {
        val k = size.width / 100f
        val st = androidx.compose.ui.graphics.drawscope.Stroke(1.3.dp.toPx())
        drawCircle(Color.White, 46 * k, center, style = st)
        drawOval(Color.White, Offset(30 * k, 4 * k), androidx.compose.ui.geometry.Size(40 * k, 92 * k), style = st)
        drawOval(Color.White, Offset(14 * k, 4 * k), androidx.compose.ui.geometry.Size(72 * k, 92 * k), style = st)
        drawLine(Color.White, Offset(4 * k, 50 * k), Offset(96 * k, 50 * k), st.width)
        drawLine(Color.White, Offset(10.2f * k, 27 * k), Offset(89.8f * k, 27 * k), st.width)
        drawLine(Color.White, Offset(10.2f * k, 73 * k), Offset(89.8f * k, 73 * k), st.width)
    }
}

/** `.wwaves`: seven guilloche-like wave lines across the card, white at 13%. */
@Composable
private fun CardWaves(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier.alpha(.13f)) {
        val sx = size.width / 300f; val sy = size.height / 100f
        listOf(38f, 47f, 56f, 65f, 74f, 83f, 92f).forEach { y ->
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, y * sy)
                for (i in 0 until 4) {
                    val x0 = i * 75f
                    quadraticBezierTo((x0 + 37.5f) * sx, (y + if (i % 2 == 0) -6f else 6f) * sy, (x0 + 75f) * sx, y * sy)
                }
            }
            drawPath(p, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
    }
}

/** `.wstamp`: round double-ring stamp, tilted −14°, "VISA" and the issuing country code. */
@Composable
private fun CardStamp(cc: String, modifier: Modifier) {
    Box(modifier.graphicsLayer { rotationZ = -14f }, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val outer = 1.6.dp.toPx()
            drawCircle(Color.White.copy(alpha = .5f), size.minDimension / 2 - outer / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(outer))
            val inner = 1.4.dp.toPx()
            drawCircle(Color.White.copy(alpha = .32f), size.minDimension / 2 - outer - 3.dp.toPx() - inner / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(inner))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("VISA", style = fdText(13f, FontWeight.Bold, Color.White.copy(alpha = .75f), spacing = .2f))
            if (cc.isNotEmpty()) Text(cc, style = fdText(10f, color = Color.White.copy(alpha = .64f)))
        }
    }
}

/** Folded top-right corner of a paper card. */
@Composable
private fun CardFold(back: Color, fold: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val s = size.width; val r = 5.dp.toPx()
        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(s, 0f); lineTo(s, s); close() }, back)
        drawPath(androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f); lineTo(s, s); lineTo(r, s); quadraticBezierTo(0f, s, 0f, s - r); close()
        }, fold)
    }
}

