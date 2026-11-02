package com.familydocs.app.ui.ds
// Ported from the Android app (app/src/main/java/com/familydocs/app/ui/ds), same look; Android-only bits swapped for desktop ones.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The demo's standard easing: cubic-bezier(.2,.8,.2,1). */
val FdEase = CubicBezierEasing(.2f, .8f, .2f, 1f)

// ---------------- small helpers ----------------

/** Dashed rounded border (CSS `border: 1.5px dashed`). */
fun Modifier.dashedBorder(color: Color, width: Dp = 1.5.dp, radius: Dp = 18.dp) = drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color = color, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

/** Plain clickable without the Material ripple tint fighting our colours. */
fun Modifier.tap(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit) = clickable(enabled = enabled, role = role, onClick = onClick)

// ---------------- top bar & buttons ----------------

@Composable
fun FdIconButton(icon: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 42.dp, iconSize: Dp = 22.dp,
                 tint: Color = Fd.c.fg, description: String? = null, background: Color = Color.Transparent, stroke: Float = 1.8f) {
    Box(modifier.size(size).clip(CircleShape).background(background).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        FdIcon(icon, iconSize, stroke, tint, description = description)
    }
}

/** `.topbar`: back button, 17px semibold title, actions on the right. */
@Composable
fun FdTopBar(title: String = "", onBack: (() -> Unit)?, right: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (onBack != null) FdIconButton("back", onBack, description = "Back") else Spacer(Modifier.width(42.dp))
        Text(title, style = fdText(17f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        right()
    }
}

/** `.tbtn`: text button in primary colour. */
@Composable
fun FdTextButton(text: String, onClick: () -> Unit, enabled: Boolean = true, color: Color = Fd.c.primary) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).tap(enabled, onClick = onClick).alpha(if (enabled) 1f else .4f).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(text, style = fdText(14f, FontWeight.SemiBold, color))
    }
}

enum class BtnKind { Primary, Ghost, Danger }

/** `.btn` / `.btn.ghost` / `.btn.danger` */
@Composable
fun FdButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: BtnKind = BtnKind.Primary, icon: String? = null,
             enabled: Boolean = true, contentColor: Color? = null) {
    val c = Fd.c
    val bg = when (kind) { BtnKind.Primary -> c.primary; BtnKind.Ghost -> c.surface; BtnKind.Danger -> c.danger }
    val fg = contentColor ?: when (kind) { BtnKind.Primary -> c.onPrimary; BtnKind.Ghost -> c.fg; BtnKind.Danger -> Color.White }
    Row(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else .45f).clip(RoundedCornerShape(16.dp)).background(bg)
            .then(if (kind == BtnKind.Ghost) Modifier.border(1.dp, c.line, RoundedCornerShape(16.dp)) else Modifier)
            .tap(enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { FdIcon(icon, 20.dp, 2.2f, fg); Spacer(Modifier.width(10.dp)) }
        Text(text, style = fdText(15f, FontWeight.SemiBold, fg))
    }
}

/** `.savebar`: sticky bottom area that fades into the page. */
@Composable
fun FdSavebar(content: @Composable ColumnScope.() -> Unit) {
    val bg = Fd.c.bg
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(0f to bg.copy(alpha = 0f), .3f to bg, 1f to bg))
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}

/** `.fab`: accent pill, bottom-right. */
@Composable
fun FdFab(text: String, icon: String = "plus", onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Fd.c
    Row(
        modifier.height(58.dp).shadow(14.dp, RoundedCornerShape(20.dp), ambientColor = c.accent, spotColor = c.accent)
            .clip(RoundedCornerShape(20.dp)).background(c.accent).tap(onClick = onClick).padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FdIcon(icon, 22.dp, 2.2f, Color(0xFF1A1205))
        Text(text, style = fdText(14f, FontWeight.SemiBold, Color(0xFF1A1205)))
    }
}

// ---------------- form ----------------

/** `.field`: label above, hint/error below. */
@Composable
fun FdField(label: String?, modifier: Modifier = Modifier, hint: String? = null, error: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Fd.c
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, style = fdText(12f, FontWeight.Medium, c.muted))
        content()
        when {
            error != null -> Text(error, style = fdText(11.5f, color = c.danger))
            hint != null -> Text(hint, style = fdText(11.5f, color = c.muted))
        }
    }
}

/** `.inp`: surface box, 1.5px border (primary when focused, danger on error), 50px tall, optional leading icon. */
@Composable
fun FdInput(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "", icon: String? = null,
    error: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text, mono: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leading: (@Composable RowScope.() -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null,
    singleLine: Boolean = true,
) {
    val c = Fd.c
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val border by animateColorAsState(if (error) c.danger else if (focused) c.primary else c.line, tween(150), label = "inpBorder")
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = singleLine, minLines = if (singleLine) 1 else 3, interactionSource = source,
        textStyle = fdText(15f, color = c.fg), cursorBrush = SolidColor(c.primary), visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
                    .border(1.5.dp, border, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = if (singleLine) 0.dp else 12.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (icon != null) FdIcon(icon, 20.dp, tint = c.muted)
                leading?.invoke(this)
                Box(Modifier.weight(1f), contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                    if (value.isEmpty()) Text(placeholder, style = fdText(15f, color = c.muted), maxLines = if (singleLine) 1 else 3, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                trailing?.invoke(this)
            }
        },
    )
}

/** `.chip` */
@Composable
fun FdChip(text: String, selected: Boolean, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = Fd.c
    val bg by animateColorAsState(if (selected) c.primary else c.surface, tween(150), label = "chipBg")
    val fg = if (selected) c.onPrimary else c.fg
    Row(
        Modifier.clip(RoundedCornerShape(99.dp)).background(bg).border(1.dp, if (selected) c.primary else c.line, RoundedCornerShape(99.dp))
            .tap(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(text, style = fdText(12.5f, color = fg))
        trailing?.invoke()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FdChips(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** `.sec-t`: 11px uppercase section heading with optional right-hand content. */
@Composable
fun FdSectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text.uppercase(), style = fdText(11f, FontWeight.SemiBold, Fd.c.muted, spacing = .09f), modifier = Modifier.weight(1f))
        trailing()
    }
}

// ---------------- lists & cards ----------------

/** `.list`: bordered rounded group of rows. */
@Composable
fun FdList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Fd.c
    Column(modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
        .border(1.dp, c.line, RoundedCornerShape(18.dp)), content = content)
}

/** `.lic`: 38px rounded icon tile. */
@Composable
fun FdLic(icon: String, tint: Color = Fd.c.primary) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Fd.c.surface2), contentAlignment = Alignment.Center) { FdIcon(icon, 19.dp, tint = tint) }
}

/** `.li`: one row of a [FdList]; [first] drops the top divider. */
@Composable
fun FdListItem(title: String, subtitle: String? = null, first: Boolean = false, icon: String? = null, mono: Boolean = false,
               onClick: (() -> Unit)? = null, leading: (@Composable () -> Unit)? = null, extraLines: List<String> = emptyList(),
               trailing: (@Composable RowScope.() -> Unit)? = null, chevron: Boolean = onClick != null && trailing == null) {
    val c = Fd.c
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.tap(onClick = onClick) else Modifier)
                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when { leading != null -> leading(); icon != null -> FdLic(icon) }
            Column(Modifier.weight(1f)) {
                Text(title, style = fdText(14f, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = fdText(12f, color = c.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                extraLines.forEach { Text(it, style = fdText(12f, color = c.muted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            trailing?.invoke(this)
            if (chevron) FdIcon("chev", 18.dp, tint = c.muted)
        }
    }
}

/** `.sw`: 46×28 switch, green when on. */
@Composable
fun FdSwitch(checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null) {
    val c = Fd.c
    val bg by animateColorAsState(if (checked) c.ok else c.line, tween(200), label = "swBg")
    val x by animateDpAsState(if (checked) 21.dp else 3.dp, tween(200), label = "swKnob")
    Box(Modifier.size(46.dp, 28.dp).clip(RoundedCornerShape(20.dp)).background(bg).tap(role = Role.Switch) { onChange(!checked) }) {
        Box(Modifier.offset(x = x, y = 3.dp).size(22.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}

/** `.vtog`: small segmented control (Cards / List / Icons). */
@Composable
fun <T> FdSegmented(options: List<Triple<T, String, String>>, selected: T, onSelect: (T) -> Unit) {
    val c = Fd.c
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (key, icon, label) ->
            val on = key == selected
            val bg by animateColorAsState(if (on) c.surface else Color.Transparent, tween(150), label = "seg")
            Row(
                Modifier.then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(8.dp)) else Modifier).clip(RoundedCornerShape(8.dp)).background(bg)
                    .tap { onSelect(key) }.padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                FdIcon(icon, 15.dp, tint = if (on) c.fg else c.muted)
                Text(label, style = fdText(11.5f, FontWeight.Medium, if (on) c.fg else c.muted))
            }
        }
    }
}

/** `.ocrbadge`: tinted info row. */
@Composable
fun FdBanner(icon: String, text: String, modifier: Modifier = Modifier, accent: Boolean = true, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = Fd.c
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (accent) c.accentSoft else c.surface2).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FdIcon(icon, 18.dp)
        Text(text, style = fdText(12.5f), modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** `.empty`: dashed placeholder box. */
@Composable
fun FdEmpty(text: String, modifier: Modifier = Modifier) {
    val c = Fd.c
    Box(modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth().dashedBorder(c.line, radius = 20.dp).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = fdText(13f, color = c.muted), textAlign = TextAlign.Center)
    }
}

/** `.progress`: 6px green bar; Drive work has no known length, so it sweeps. */
@Composable
fun FdProgress(modifier: Modifier = Modifier) {
    val c = Fd.c
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "progress")
    val x by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(1400, easing = androidx.compose.animation.core.LinearEasing)), label = "progressX")
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).background(c.surface2)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(x.coerceAtLeast(.04f)).clip(RoundedCornerShape(6.dp)).background(c.ok))
    }
}

// ---------------- sheets & toasts ----------------

/**
 * `.sheet`, desktop version: the same card (28px corners, surface colour, grab handle) over the same dark scrim, centred
 * in the window. Click outside or press Esc to close.
 */
@Composable
fun FdSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Fd.c
    // fades in: the window behind dims (in dark mode it goes darker, in light mode it greys and lightens a little),
    // the card rises and settles; clicking outside or Esc fades it out again
    var shown by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(leaving) { if (leaving) { shown = false; delay(170); onDismiss() } }
    val p by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 260 else 160, easing = FdEase), label = "sheet")
    val scrim = if (c.dark) Color(0xFF000000).copy(alpha = .62f * p) else Color(0xFF1B2233).copy(alpha = .34f * p)
    androidx.compose.ui.window.Dialog(onDismissRequest = { leaving = true },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, scrimColor = Color.Transparent)) {
        Box(Modifier.fillMaxSize().background(scrim).tap(role = Role.Button) { leaving = true }, contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth(.9f)
                    .graphicsLayer { alpha = p; val k = .94f + .06f * p; scaleX = k; scaleY = k; translationY = (1f - p) * 18.dp.toPx() }
                    .shadow(28.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)).background(c.surface)
                    .border(1.dp, if (c.dark) c.line else Color.Transparent, RoundedCornerShape(28.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(start = 24.dp, end = 24.dp, bottom = 22.dp),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(4.dp)).background(c.line))
                }
                content()
            }
        }
    }
}

@Composable
fun SheetTitle(title: String, text: String? = null) {
    Text(title, style = fdText(18f, FontWeight.Bold), modifier = Modifier.padding(bottom = 4.dp))
    if (text != null) Text(text, style = fdText(13f, color = Fd.c.muted), modifier = Modifier.padding(bottom = 16.dp))
}

/** Confirmation sheet used for deletes (title, explanation, red action, Cancel). */
@Composable
fun FdConfirmSheet(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    FdSheet(onDismiss) {
        SheetTitle(title, text)
        FdButton(action, onConfirm, kind = if (danger) BtnKind.Danger else BtnKind.Primary)
        Spacer(Modifier.height(10.dp))
        FdButton("Cancel", onDismiss, kind = BtnKind.Ghost)
    }
}

class FdToastState { var message by mutableStateOf<String?>(null); var serial by mutableStateOf(0)
    fun show(m: String) { message = m; serial++ } }

val LocalToast = staticCompositionLocalOf { FdToastState() }

/** `.toast`: dark pill near the bottom; fades/slides in, stays ~2 s, fades out. */
@Composable
fun BoxScope.FdToastHost(state: FdToastState) {
    val c = Fd.c
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(state.serial) { if (state.message != null) { visible = true; delay(1900); visible = false } }
    AnimatedVisibility(
        visible, Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp),
        enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 3 }, exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { it / 4 },
    ) {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(c.fg).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(state.message.orEmpty(), style = fdText(13f, color = c.bg))
        }
    }
}

