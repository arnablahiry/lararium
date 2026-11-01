package com.familydocs.app.ui.ds
// The phone's notes formatting (*bold*, _italic_, ~strike~, ```mono```), shared with the Mac so notes read the same.

import androidx.compose.ui.graphics.Color
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

/**
 * WhatsApp-style formatting for notes: *bold*, _italic_, ~strikethrough~ and ```monospace``` (or `code`),
 * nestable (_*bold italic*_). Within one line, like WhatsApp: a marker opens before a non-space and closes after one.
 */
object NoteFormat {
    private const val MARKS = "*_~`"
    private const val EDGE = ".,;:!?()[]{}\"'-–—/"
    /** One formatted run: markers at [start, start+len) and [end, end+len); the text between them is styled. */
    data class Span(val start: Int, val end: Int, val mark: Char, val len: Int)

    fun spans(s: String): List<Span> = mutableListOf<Span>().also { scan(s, 0, s.length, it) }

    private fun boundary(c: Char?) = c == null || c.isWhitespace() || c in MARKS || c in EDGE

    private fun scan(s: String, from: Int, to: Int, out: MutableList<Span>) {
        var i = from
        while (i < to) {
            val m = s[i]
            val len = if (m == '`' && s.startsWith("```", i)) 3 else 1
            val first = i + len
            if (m in MARKS && boundary(s.getOrNull(i - 1)) && first < to && !s[first].isWhitespace() && s[first] != m) {
                var j = first + 1
                var found = -1
                while (j + len <= to && s[j] != '\n') {
                    if (s[j] == m && (len == 1 || s.startsWith("```", j)) && !s[j - 1].isWhitespace() && boundary(s.getOrNull(j + len))) { found = j; break }
                    j++
                }
                if (found > 0) {
                    out += Span(i, found, m, len)
                    scan(s, first, found, out)
                    i = found + len
                    continue
                }
            }
            i++
        }
    }

    private fun style(m: Char) = when (m) {
        '*' -> SpanStyle(fontWeight = FontWeight.Bold)
        '_' -> SpanStyle(fontStyle = FontStyle.Italic)
        '~' -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        else -> SpanStyle(fontFamily = FontFamily.Monospace)
    }

    /** While you type: every character stays, the markers just fade, the text between them is styled. */
    fun editing(s: String, markColor: Color): AnnotatedString = AnnotatedString.Builder(s).apply {
        spans(s).forEach { sp ->
            addStyle(style(sp.mark), sp.start + sp.len, sp.end)
            addStyle(SpanStyle(color = markColor), sp.start, sp.start + sp.len)
            addStyle(SpanStyle(color = markColor), sp.end, sp.end + sp.len)
        }
    }.toAnnotatedString()

    /** Reading: the markers disappear. Also returns where each character went, for the cursor. */
    fun reading(s: String): TransformedText {
        val sp = spans(s)
        val hidden = BooleanArray(s.length)
        sp.forEach { x -> for (k in 0 until x.len) { hidden[x.start + k] = true; hidden[x.end + k] = true } }
        val o2t = IntArray(s.length + 1); val t2o = ArrayList<Int>(s.length + 1)
        val b = StringBuilder()
        for (i in s.indices) { o2t[i] = b.length; if (!hidden[i]) { t2o += i; b.append(s[i]) } }
        o2t[s.length] = b.length; t2o += s.length
        val out = AnnotatedString.Builder(b.toString())
        sp.forEach { x -> out.addStyle(style(x.mark), o2t[x.start + x.len], o2t[x.end]) }
        return TransformedText(out.toAnnotatedString(), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = o2t[offset.coerceIn(0, s.length)]
            override fun transformedToOriginal(offset: Int) = t2o[offset.coerceIn(0, t2o.lastIndex)]
        })
    }
}

