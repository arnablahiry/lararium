package com.familydocs.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads the text on a scan, so a document added here fills itself in like on the phone (the phone's own reader,
 * [com.familydocs.app.ocr.DocumentParser], then turns the text into fields):
 *  - a PDF with text in it: that text
 *  - a photo or a scanned PDF: the computer's own text recognition: Apple's Vision on a Mac (the app's helper),
 *    Windows' built-in OCR, or Tesseract on Linux when it's installed
 * The scan never leaves the computer for this. Empty when nothing could be read.
 */
object TextRead {
    suspend fun read(bytes: ByteArray, mime: String): String = withContext(Dispatchers.IO) {
        runCatching {
            if (mime == "application/pdf") {
                val embedded = org.apache.pdfbox.Loader.loadPDF(bytes).use { pdf ->
                    org.apache.pdfbox.text.PDFTextStripper().apply { endPage = minOf(3, pdf.numberOfPages) }.getText(pdf)
                }
                if (embedded.count { it.isLetterOrDigit() } > 40) return@runCatching embedded
                // a scanned PDF: its first pages as pictures
                org.apache.pdfbox.Loader.loadPDF(bytes).use { pdf ->
                    val r = org.apache.pdfbox.rendering.PDFRenderer(pdf)
                    (0 until minOf(2, pdf.numberOfPages)).joinToString("\n") { i ->
                        val f = File.createTempFile("lararium-page", ".png")
                        try { javax.imageio.ImageIO.write(r.renderImageWithDPI(i, 220f), "png", f); ocr(f) } finally { f.delete() }
                    }
                }
            } else {
                val f = File.createTempFile("lararium-scan", ".png")
                try { f.writeBytes(scaled(bytes) ?: bytes); ocr(f) } finally { f.delete() }
            }
        }.getOrDefault("")
    }

    /** At most 2400 px on the longer side (Windows' reader refuses bigger pictures), as PNG. */
    private fun scaled(bytes: ByteArray, max: Int = 2400): ByteArray? = runCatching {
        val img = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        val k = minOf(1f, max.toFloat() / maxOf(img.width, img.height))
        val w = (img.width * k).toInt().coerceAtLeast(1); val h = (img.height * k).toInt().coerceAtLeast(1)
        val s = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
        s.canvas.drawImageRect(img, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
        s.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
    }.getOrNull()

    private fun run(cmd: List<String>): String {
        val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return "" }
        return if (p.exitValue() == 0) out else ""
    }

    private fun ocr(f: File): String = when {
        Platform.isMac -> Biometric.helper?.let { run(listOf(it.absolutePath, "--ocr", f.absolutePath)) }.orEmpty()
        Platform.isWindows -> run(listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", windows.absolutePath, f.absolutePath))
        File("/usr/bin/tesseract").exists() -> run(listOf("tesseract", f.absolutePath, "stdout"))
        else -> ""
    }

    private val windows by lazy {
        File.createTempFile("lararium-ocr", ".ps1").apply {
            deleteOnExit()
            writeText("""
                |[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                |Add-Type -AssemblyName System.Runtime.WindowsRuntime
                |${'$'}asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
                |function Await(${'$'}op, ${'$'}type) { ${'$'}t = ${'$'}asTask.MakeGenericMethod(${'$'}type).Invoke(${'$'}null, @(${'$'}op)); ${'$'}t.Wait(-1) | Out-Null; ${'$'}t.Result }
                |[Windows.Storage.StorageFile,Windows.Storage,ContentType=WindowsRuntime] | Out-Null
                |[Windows.Media.Ocr.OcrEngine,Windows.Foundation,ContentType=WindowsRuntime] | Out-Null
                |[Windows.Graphics.Imaging.BitmapDecoder,Windows.Graphics,ContentType=WindowsRuntime] | Out-Null
                |${'$'}file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync(${'$'}args[0])) ([Windows.Storage.StorageFile])
                |${'$'}stream = Await (${'$'}file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
                |${'$'}decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync(${'$'}stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
                |${'$'}bmp = Await (${'$'}decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
                |${'$'}engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
                |${'$'}res = Await (${'$'}engine.RecognizeAsync(${'$'}bmp)) ([Windows.Media.Ocr.OcrResult])
                |${'$'}res.Lines | ForEach-Object { ${'$'}_.Text }
                |""".trimMargin())
        }
    }
}
