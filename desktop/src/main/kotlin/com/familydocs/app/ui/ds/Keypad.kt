package com.familydocs.app.ui.ds
// Ported from the Android app (app/src/main/java/com/familydocs/app/ui/ds), same look; Android-only bits swapped for desktop ones.

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.shadow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * `.dots`: 13px rings that fill and grow slightly; change [shakeKey] to shake (wrong code).
 * [success]: the unlock moment (demo `.dots.ok`): dots turn green, gather into the middle and a check pops in.
 */
@Composable
fun FdDots(length: Int, filled: Int, shakeKey: Int, success: Boolean = false) {
    val c = Fd.c
    val shake = remember { Animatable(0f) }
    val gather = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    LaunchedEffect(shakeKey) { if (shakeKey > 0) shake.animateTo(0f, keyframes { durationMillis = 400; -8f at 80; 8f at 160; -8f at 240; 8f at 320; 0f at 400 }) }
    LaunchedEffect(success) {
        if (!success) { gather.snapTo(0f); check.snapTo(0f); return@LaunchedEffect }
        launch { gather.animateTo(1f, tween(340, easing = CubicBezierEasing(.55f, 0f, .25f, 1f))) }
        delay(220)
        check.animateTo(1f, tween(440, easing = CubicBezierEasing(.3f, 1.6f, .5f, 1f)))
    }
    Box(contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(16.dp).offset { IntOffset(shake.value.dp.roundToPx(), 0) }.semantics { contentDescription = if (success) "Unlocked" else "$filled of $length digits entered" },
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(length) { i ->
                val on = i < filled
                val ok = success
                val fill by animateColorAsState(if (ok) c.ok else if (on) c.primary else Color.Transparent, tween(if (ok) 100 else 150), label = "dot")
                val s by animateFloatAsState(if (on) 1.1f else 1f, tween(150), label = "dotScale")
                val toCenter = ((length - 1) / 2f - i) * 31f // 13dp dot + 18dp gap
                Box(
                    Modifier.size(13.dp).graphicsLayer {
                        val g = gather.value
                        translationX = toCenter * g * density
                        val k = s * (1f - .65f * g); scaleX = k; scaleY = k
                        alpha = 1f - ((g - .4f) / .6f).coerceIn(0f, 1f)
                    }.clip(CircleShape).background(fill).border(1.5.dp, if (ok) c.ok else if (on) c.primary else c.muted, CircleShape),
                )
            }
        }
        if (success) Box(
            Modifier.size(36.dp).graphicsLayer { val v = check.value; scaleX = .3f + .7f * v; scaleY = .3f + .7f * v; alpha = v.coerceIn(0f, 1f) }
                .shadow(8.dp, CircleShape, ambientColor = c.ok, spotColor = c.ok).clip(CircleShape).background(c.ok),
            contentAlignment = Alignment.Center,
        ) { FdIcon("check", 21.dp, 2.6f, Color.White) }
    }
}

/** `.keys`: 3×4 grid of 76px round keys with letters under the numerals; fingerprint and delete are flat. */
@Composable
fun FdKeypad(onDigit: (Char) -> Unit, onDelete: () -> Unit, onBiometric: (() -> Unit)? = null) {
    val letters = listOf("", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in 0 until 3) Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            for (col in 1..3) { val d = row * 3 + col; Key("$d", { onDigit('0' + d) }) { Numeral("$d", letters[d - 1]) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            if (onBiometric != null) Key("Use fingerprint", onBiometric, flat = true) { FdIcon("finger", 28.dp, 1.6f, Fd.c.muted) }
            else Spacer(Modifier.size(76.dp))
            Key("0", { onDigit('0') }) { Numeral("0", "") }
            Key("Delete", onDelete, flat = true) { FdIcon("bksp", 26.dp, 1.6f, Fd.c.muted) }
        }
    }
}

@Composable
private fun Numeral(n: String, l: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(n, style = fdText(30f, FontWeight.Light, lineHeight = 30f))
        Text(l, style = fdText(9.5f, FontWeight.SemiBold, Fd.c.muted, spacing = .22f, lineHeight = 12f), modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit, flat: Boolean = false, content: @Composable () -> Unit) {
    val c = Fd.c
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg by animateColorAsState(
        when { flat && pressed -> c.fg.copy(alpha = .06f); flat -> Color.Transparent; pressed -> c.key(.20f); else -> c.key(.08f) }, tween(120), label = "key")
    val s by animateFloatAsState(if (pressed && !flat) .95f else 1f, tween(120), label = "keyScale")
    Box(
        Modifier.size(76.dp).scale(s).clip(CircleShape).background(bg)
            .clickable(interactionSource = source, indication = null) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

