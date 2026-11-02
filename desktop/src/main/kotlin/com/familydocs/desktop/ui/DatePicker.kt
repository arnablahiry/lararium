package com.familydocs.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdEase
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdIconButton
import com.familydocs.app.ui.ds.FdTextButton
import com.familydocs.app.ui.ds.fdText
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val SHOWN = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK)

/** A date, picked on a calendar (like an input box: click it). [min]: nothing earlier can be picked. */
@Composable
fun DateButton(date: LocalDate?, onPick: (LocalDate?) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Pick a date",
               min: LocalDate? = null, clearable: Boolean = false) {
    val c = Fd.c
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.5.dp, if (open) c.primary else c.line, RoundedCornerShape(14.dp)).clickable { open = true }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FdIcon("calendar", 20.dp, tint = c.muted)
            Text(date?.format(SHOWN) ?: placeholder, style = fdText(15f, color = if (date == null) c.muted else c.fg), modifier = Modifier.weight(1f), maxLines = 1)
            if (clearable && date != null) FdIconButton("close", { onPick(null) }, size = 28.dp, iconSize = 14.dp, tint = c.muted, description = "Clear")
        }
        if (open) CalendarPopup(date ?: min, { onPick(it); open = false }, { open = false }, min, clearable = clearable && date != null, onClear = { onPick(null); open = false })
    }
}

/** A calendar under whatever it's attached to; closes on a pick, a click elsewhere or Esc. */
@Composable
fun CalendarPopup(initial: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit, min: LocalDate? = null,
                  clearable: Boolean = false, onClear: () -> Unit = {}) {
    Popup(popupPositionProvider = Below, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Calendar(initial, onPick, min, clearable, onClear)
    }
}

/** Under the thing it's attached to (above it when there's no room below), kept inside the window. */
private object Below : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                                   layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize): IntOffset {
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val below = anchorBounds.bottom + 12
        val y = if (below + popupContentSize.height <= windowSize.height) below else (anchorBounds.top - popupContentSize.height - 12).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** The month grid: arrows for months, double arrows for years, Today, and the picked day filled in. */
@Composable
fun Calendar(initial: LocalDate?, onPick: (LocalDate) -> Unit, min: LocalDate? = null, clearable: Boolean = false, onClear: () -> Unit = {}) {
    val c = Fd.c
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.from(initial ?: today)) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val p by animateFloatAsState(if (shown) 1f else 0f, tween(200, easing = FdEase), label = "cal")
    Column(Modifier.width(318.dp).graphicsLayer { alpha = p; translationY = (1f - p) * -8.dp.toPx() }
        .shadow(20.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(c.surface)
        .border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FdIconButton("back", { month = month.minusYears(1) }, size = 30.dp, iconSize = 13.dp, tint = c.muted, description = "A year earlier")
            FdIconButton("back", { month = month.minusMonths(1) }, size = 30.dp, iconSize = 16.dp, description = "Previous month")
            Text("${month.month.getDisplayName(TextStyle.FULL, Locale.UK)} ${month.year}", style = fdText(15f, FontWeight.SemiBold),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            FdIconButton("chev", { month = month.plusMonths(1) }, size = 30.dp, iconSize = 16.dp, description = "Next month")
            FdIconButton("chev", { month = month.plusYears(1) }, size = 30.dp, iconSize = 13.dp, tint = c.muted, description = "A year later")
        }
        Spacer(Modifier.height(8.dp))
        Row {
            listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su").forEach {
                Text(it, style = fdText(11f, FontWeight.SemiBold, c.muted), modifier = Modifier.width(41.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1 // Monday first
        val days = month.lengthOfMonth()
        for (week in 0 until 6) {
            if (week * 7 - lead >= days) break
            Row {
                for (d in 0 until 7) {
                    val n = week * 7 + d - lead + 1
                    Box(Modifier.size(41.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..days) {
                            val day = month.atDay(n)
                            val picked = day == initial
                            val off = min != null && day.isBefore(min)
                            Box(Modifier.size(36.dp).clip(CircleShape).background(if (picked) c.primary else Color.Transparent)
                                .then(if (day == today && !picked) Modifier.border(1.5.dp, c.accent, CircleShape) else Modifier)
                                .clickable(enabled = !off) { onPick(day) }, contentAlignment = Alignment.Center) {
                                Text("$n", style = fdText(13.5f, if (picked || day == today) FontWeight.SemiBold else FontWeight.Normal,
                                    when { picked -> c.onPrimary; off -> c.line; else -> c.fg }))
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            FdTextButton("Today", { if (min == null || !today.isBefore(min)) onPick(today) else month = YearMonth.from(min) })
            if (clearable) FdTextButton("Clear", onClear, color = c.muted)
        }
    }
}
