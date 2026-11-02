package com.familydocs.app.ui.ds

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.familydocs.desktop.Platform

/**
 * Notes box with formatting, as on the phone: styled as you type (markers faded), shown clean when you're not editing,
 * and while editing a small toolbar that wraps the selection (or puts the markers either side of the cursor).
 * ⌘B / ⌘I / ⌘⇧X (Ctrl on Windows and Linux) do the same from the keyboard. [onDone] runs when you click away.
 */
@Composable
fun FdNotesField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "", onDone: () -> Unit = {}) {
    val c = Fd.c
    var tv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) { if (value != tv.text) tv = TextFieldValue(value, TextRange(value.length)) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    var wasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) { if (focused) wasFocused = true else if (wasFocused) onDone() }
    val border by animateColorAsState(if (focused) c.primary else c.line, tween(150), label = "notesBorder")
    val mark = c.muted.copy(alpha = .55f)
    val transform = remember(focused, mark) {
        if (focused) VisualTransformation { t -> TransformedText(NoteFormat.editing(t.text, mark), OffsetMapping.Identity) }
        else VisualTransformation { t -> NoteFormat.reading(t.text) }
    }
    fun update(n: TextFieldValue) { tv = n; if (n.text != value) onValueChange(n.text) }
    fun wrap(m: String) {
        val a = tv.selection.min; val b = tv.selection.max
        val t = tv.text
        update(if (a == b) TextFieldValue(t.substring(0, a) + m + m + t.substring(a), TextRange(a + m.length))
               else TextFieldValue(t.substring(0, a) + m + t.substring(a, b) + m + t.substring(b), TextRange(a, b + m.length * 2)))
    }
    fun bullet() {
        val t = tv.text; val at = tv.selection.min
        val start = t.lastIndexOf('\n', (at - 1).coerceAtLeast(0)).let { if (at == 0 || it < 0) 0 else it + 1 }
        if (t.startsWith("• ", start)) update(TextFieldValue(t.removeRange(start, start + 2), TextRange((at - 2).coerceAtLeast(start))))
        else update(TextFieldValue(t.substring(0, start) + "• " + t.substring(start), TextRange(at + 2)))
    }
    Column(modifier.fillMaxWidth()) {
        BasicTextField(
            value = tv, onValueChange = ::update, interactionSource = source, minLines = 3,
            textStyle = fdText(15f, color = c.fg), cursorBrush = SolidColor(c.primary), visualTransformation = transform,
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !Platform.shortcut(e)) return@onPreviewKeyEvent false
                when {
                    e.key == Key.B -> { wrap("*"); true }
                    e.key == Key.I -> { wrap("_"); true }
                    e.key == Key.X && e.isShiftPressed -> { wrap("~"); true }
                    else -> false
                }
            },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
                    .border(1.5.dp, border, RoundedCornerShape(14.dp)).padding(12.dp)) {
                    if (tv.text.isEmpty()) Text(placeholder, style = fdText(15f, color = c.muted))
                    inner()
                }
            },
        )
        AnimatedVisibility(focused, enter = expandVertically(tween(180)) + fadeIn(tween(180)), exit = shrinkVertically(tween(150)) + fadeOut(tween(120))) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FormatKey("B", SpanStyle(fontWeight = FontWeight.Bold), "Bold") { wrap("*") }
                FormatKey("I", SpanStyle(fontStyle = FontStyle.Italic), "Italic") { wrap("_") }
                FormatKey("S", SpanStyle(textDecoration = TextDecoration.LineThrough), "Strikethrough") { wrap("~") }
                FormatKey("{}", SpanStyle(fontFamily = FontFamily.Monospace), "Monospace") { wrap("```") }
                FormatKey("•", SpanStyle(fontWeight = FontWeight.Bold), "Bullet") { bullet() }
                Text("*bold*  _italic_  ~strike~  ·  ${Platform.mod}B  ${Platform.mod}I", style = fdText(11f, color = c.muted),
                    modifier = Modifier.padding(start = 4.dp), maxLines = 1)
            }
        }
    }
}

@Composable
private fun FormatKey(label: String, style: SpanStyle, description: String, onClick: () -> Unit) {
    val c = Fd.c
    Box(Modifier.size(width = 40.dp, height = 34.dp).clip(RoundedCornerShape(10.dp)).background(c.surface2).border(1.dp, c.line, RoundedCornerShape(10.dp))
        .tap(onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(AnnotatedString(label, style), style = fdText(15f, color = c.fg))
    }
}
