package com.familydocs.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.familydocs.desktop.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A picture from the vault (profile photo, card picture, event photo), fetched from Drive and decrypted the first time
 * it's shown, then kept in memory. Same name as the Android app's, so the shared design system code works unchanged.
 */
@Composable
fun rememberVaultImage(fileId: String?, maxPx: Int): ImageBitmap? {
    val image by produceState<ImageBitmap?>(Files.cachedImage(fileId, maxPx), fileId, maxPx) {
        value = if (fileId == null) null else withContext(Dispatchers.IO) { Files.image(fileId, maxPx) }
    }
    return image
}

/** Bitmap decoding for the vault's pictures: scaled down to [maxPx] on the longer side. */
internal fun decodeScaled(bytes: ByteArray, maxPx: Int): ImageBitmap? = runCatching {
    val img = org.jetbrains.skia.Image.makeFromEncoded(bytes)
    val longer = maxOf(img.width, img.height)
    if (longer <= maxPx) return@runCatching img.toComposeImageBitmap()
    val k = maxPx.toFloat() / longer
    val w = (img.width * k).toInt().coerceAtLeast(1); val h = (img.height * k).toInt().coerceAtLeast(1)
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(img, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
    surface.makeImageSnapshot().toComposeImageBitmap()
}.getOrNull()
