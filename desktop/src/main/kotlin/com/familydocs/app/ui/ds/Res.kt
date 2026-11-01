package com.familydocs.app.ui.ds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap

/**
 * The app's bundled pictures (card motifs, flags, characters, event art): the very same files as the Android app's
 * drawable-nodpi, copied in at build time, decoded once and shared.
 */
object Res {
    private val cache = HashMap<String, ImageBitmap?>()

    private fun bytes(name: String): ByteArray? =
        Res::class.java.getResourceAsStream("/drawable/$name.webp")?.use { it.readBytes() }
            ?: Res::class.java.getResourceAsStream("/drawable/$name.png")?.use { it.readBytes() }

    fun has(name: String): Boolean = get(name) != null

    fun get(name: String): ImageBitmap? = synchronized(cache) {
        cache.getOrPut(name) { bytes(name)?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() } }
    }
}

/** A bundled picture by its Android resource name ("card_m_passport"); transparent if it's missing. */
@Composable
fun fdPainter(name: String): Painter = remember(name) { Res.get(name)?.let { BitmapPainter(it) } ?: ColorPainter(androidx.compose.ui.graphics.Color.Transparent) }
