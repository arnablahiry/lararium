package com.familydocs.app.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.familydocs.app.domain.GroupInvite
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdButton
import com.familydocs.app.ui.ds.FdSheet
import com.familydocs.app.ui.ds.SheetTitle
import com.familydocs.app.ui.ds.fdText
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.familydocs.app.ui.components.LocalContainer
import com.familydocs.app.ui.ds.FdChip
import com.familydocs.app.ui.ds.FdChips
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.LocalToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [text] as a QR code: black on white (whatever the theme), so every camera reads it. */
fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/**
 * Your invite as a QR code, for someone right next to you: their phone's camera opens the link, and Lararium joins
 * the group (or, if they're already in it, adds you on their phone).
 */
@Composable
fun InviteQrSheet(invite: GroupInvite, groupName: String, onDismiss: () -> Unit) {
    val c = Fd.c
    val image = remember(invite) { qrBitmap(invite.link()).asImageBitmap() }
    FdSheet(onDismiss) {
        SheetTitle("Scan to join $groupName", "Point their phone's camera at this. The link opens Lararium on their phone.")
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            Image(image, "Invite to $groupName as a QR code", Modifier.size(264.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(14.dp))
        }
        Text("Already in $groupName? Scanning it adds you on their phone, so you're synced.", style = fdText(12.5f, color = c.muted),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
        FdButton("Done", onDismiss, Modifier.padding(top = 16.dp))
        Spacer(Modifier.height(10.dp))
    }
}

/** The QR code in a photo or screenshot (null if there isn't a readable one). Slow: off the main thread. */
fun readQr(bmp: Bitmap): String? {
    val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
    // full size first, then smaller (a big photo of a screen reads better scaled down)
    for (k in listOf(1f, .5f, .3f)) {
        val b = if (k == 1f) bmp else Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        val src = RGBLuminanceSource(b.width, b.height, px)
        for (bin in listOf(HybridBinarizer(src), GlobalHistogramBinarizer(src), HybridBinarizer(src.invert()))) {
            runCatching { return QRCodeReader().decode(BinaryBitmap(bin), hints).text }
        }
    }
    return null
}

/**
 * "Scan QR code" (the camera, through Google Play services: no camera permission needed) and "From a photo" (a
 * screenshot or picture of someone's invite QR). A Lararium invite found either way goes to [onInvite].
 */
@Composable
fun QrInviteButtons(onInvite: (String) -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContainer.current
    val c = Fd.c
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val found by rememberUpdatedState(onInvite)
    fun use(text: String?) {
        if (text != null && GroupInvite.find(text) != null) found(text)
        else toast.show(if (text == null) "No QR code found there" else "That QR code isn't a Lararium invite")
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.Default) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { com.familydocs.app.ocr.Images.decode(it, 1600) }?.let(::readQr) }.getOrNull()
            }
            use(text)
        }
    }
    FdChips(modifier) {
        FdChip("Scan QR code", false, {
            app.appLock.expectExternalActivity()
            val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { b -> use(b.rawValue) }
                .addOnFailureListener { toast.show("Couldn't open the scanner: ${it.message ?: "try again"}") }
        }, leading = { FdIcon("scan", 16.dp, tint = c.fg) })
        FdChip("From a photo", false, {
            app.appLock.expectExternalActivity()
            photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }, leading = { FdIcon("image", 16.dp, tint = c.fg) })
    }
}
