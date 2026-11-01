package com.familydocs.app.ui.ds
// Ported from the Android app (app/src/main/java/com/familydocs/app/ui/ds), same look; Android-only bits swapped for desktop ones.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The pixel heart from the albedo site's footer: 7 × 6 cells, #ff7b80, crisp square pixels. */
private val HEART_ROWS = listOf(".##.##.", "#######", "#######", ".#####.", "..###..", "...#...")
private val HEART = Color(0xFFFF7B80)

@Composable
fun PixelHeart(height: Dp = 10.dp, modifier: Modifier = Modifier) {
    val cell = height / 6
    Canvas(modifier.size(cell * 7, height)) {
        val k = size.height / 6f
        HEART_ROWS.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch -> if (ch == '#') drawRect(HEART, Offset(x * k, y * k), Size(k + .5f, k + .5f)) }
        }
    }
}

/** "Made with ♥ by Arnab Lahiry": quiet footer line at the bottom of Home and Settings (same as the albedo site). */
@Composable
fun FdMadeWith(modifier: Modifier = Modifier) {
    val c = Fd.c
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Made with love by Arnab Lahiry" },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Made with", style = fdText(12f, color = c.muted))
        PixelHeart(10.dp)
        Text("by", style = fdText(12f, color = c.muted))
        Text("Arnab Lahiry", style = fdText(12f, FontWeight.SemiBold, c.fg.copy(alpha = .8f)))
    }
}
