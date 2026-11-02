package com.familydocs.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.familydocs.app.ui.ds.BtnKind
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdButton
import com.familydocs.app.ui.ds.FdChip
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdSheet
import com.familydocs.app.ui.ds.SheetTitle
import com.familydocs.desktop.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's photo cropper: drag the picture and zoom (the slider, or scroll) until the circle shows what you want.
 * [onDone] gets the round-ready JPEG ([out] pixels square).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PhotoCropSheet(bytes: ByteArray, title: String, onDone: (ByteArray) -> Unit, onDismiss: () -> Unit, out: Int = 700) {
    val c = Fd.c
    val source = remember(bytes) { runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes) }.getOrNull() }
    val shown = remember(source) { source?.let { runCatching { it.toComposeImageBitmap() }.getOrNull() } }
    var zoom by remember { mutableFloatStateOf(1f) }
    // where the picture is, as a fraction of the frame (so the screen and the saved photo agree)
    var pan by remember { mutableStateOf(Offset.Zero) }
    val iw = (source?.width ?: 1).toFloat(); val ih = (source?.height ?: 1).toFloat()
    fun wide(z: Float) = z * maxOf(1f, iw / ih)   // the picture's width, in frames
    fun tall(z: Float) = z * maxOf(1f, ih / iw)
    /** Never leaves a gap inside the frame. */
    fun clamp(p: Offset, z: Float): Offset {
        val mx = (wide(z) - 1f) / 2f; val my = (tall(z) - 1f) / 2f
        return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
    }
    fun setZoom(z: Float) { zoom = z.coerceIn(1f, 6f); pan = clamp(pan, zoom) }
    val box = 300.dp
    FdSheet(onDismiss) {
        SheetTitle(title, "Drag and zoom so the circle shows what you want.")
        if (source == null || shown == null) { FdButton("Close", onDismiss, kind = BtnKind.Ghost); return@FdSheet }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(box).clip(RoundedCornerShape(20.dp))
                .pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); pan = clamp(pan + Offset(d.x / size.width, d.y / size.height), zoom) } }
                .onPointerEvent(PointerEventType.Scroll) { e -> val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f; setZoom(zoom * (1f - dy * .08f)) }) {
                val v = size.width
                val w = wide(zoom) * v; val h = tall(zoom) * v
                val left = (v - w) / 2f + pan.x * v; val top = (v - h) / 2f + pan.y * v
                drawImage(shown, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(w.toInt(), h.toInt()))
                val hole = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, Size(v, v)))
                    addOval(Rect(Offset(v * .04f, v * .04f), Size(v * .92f, v * .92f)))
                }
                drawPath(hole, Color(0x99000000))
                drawCircle(Color.White.copy(alpha = .9f), radius = v * .46f, style = Stroke(2.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FdIcon("minus", 16.dp, tint = c.muted)
            Slider(zoom, { setZoom(it) }, Modifier.weight(1f), valueRange = 1f..6f,
                colors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary, inactiveTrackColor = c.line))
            FdIcon("plus", 16.dp, tint = c.muted)
        }
        Spacer(Modifier.height(14.dp))
        FdButton("Use this photo", {
            // the circle's square, in the picture's own pixels (the frame is 1 × 1 here)
            val w = wide(zoom); val h = tall(zoom)
            val left = (1f - w) / 2f + pan.x; val top = (1f - h) / 2f + pan.y
            val k = w / iw // frames per pixel
            val src = org.jetbrains.skia.Rect.makeXYWH((.04f - left) / k, (.04f - top) / k, .92f / k, .92f / k)
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(out, out)
            surface.canvas.drawImageRect(source, src, org.jetbrains.skia.Rect.makeWH(out.toFloat(), out.toFloat()))
            surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 88)?.bytes?.let(onDone)
        })
        Spacer(Modifier.height(10.dp))
        FdButton("Cancel", onDismiss, kind = BtnKind.Ghost)
    }
}

/**
 * Choose a photo / Edit photo / Remove for a person, an event or a group: picking opens the cropper, and Edit reopens
 * it on the photo there is (or the one just picked).
 */
@Composable
fun PhotoActions(title: String, currentFile: String?, hasPhoto: Boolean, onPhoto: (ByteArray) -> Unit, onRemove: (() -> Unit)?) {
    val c = Fd.c
    val toast = com.familydocs.app.ui.ds.LocalToast.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<ByteArray?>(null) }
    var cropping by remember { mutableStateOf<ByteArray?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FdChip("Choose a photo…", false, {
            pickFiles(title, images = true, onProblem = { toast.show(it) }).firstOrNull()?.let { picked = it.bytes; cropping = it.bytes }
        }, leading = { FdIcon("image", 15.dp, tint = c.fg) })
        if (hasPhoto) FdChip("Edit photo", false, {
            val p = picked
            if (p != null) cropping = p
            else scope.launch {
                val b = withContext(Dispatchers.IO) { Files.get(currentFile) }
                if (b != null) cropping = b else toast.show("Couldn't open the picture")
            }
        }, leading = { FdIcon("edit", 15.dp, tint = c.fg) })
        if (hasPhoto && onRemove != null) FdChip("Remove", false, { picked = null; onRemove() })
    }
    cropping?.let { b -> PhotoCropSheet(b, title, onDone = { onPhoto(it); cropping = null }, onDismiss = { cropping = null }) }
}
